package com.skystormer.skysmapexposer

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.skystormer.skysmapexposer.gui.PlayerListScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

object MapExposerClient : ClientModInitializer {

    private var ticks = 0L

    private var hookChecked = false
    /** The one line saying there is no map mod: once a game, not every world. */
    private var noMapSaid = false
    /** The heads of locked players, as a HUD element of this mod's own. */
    private val BEACON: Identifier = Identifier.fromNamespaceAndPath("skysmapexposer", "locked_players")

    /** Pings the block you are looking at. Unbound until you pick a key in Controls. */
    private val pingKey = KeyMapping(
        "key.skysmapexposer.ping",
        //? if >=26.3 {
        InputConstants.Type.KEYBOARD,
        //?} else {
        /*InputConstants.Type.KEYSYM,
        *///?}
        InputConstants.UNKNOWN.value,
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("skysmapexposer", "map_exposer")),
    )

    /**
     * Whether one of this mod's optional injections reached [className]. Every injection here is
     * `require = 0`, so a Xaero that has moved the code costs the feature and not the game — but
     * that also means a failed injection is silent. Loading the class and looking for the method
     * mixin would have added is the only way to tell, and it beats wondering why nothing happened.
     */
    fun injected(className: String, method: String): Boolean = try {
        Class.forName(className).declaredMethods.any { it.name.contains(method) }
    } catch (e: Throwable) {
        false
    }

    /** "Texture memory 180 / 256 MB", and why it is held back, if it is. */
    fun textureMemory(session: Session): String {
        val pacer = FramePacer.game
        val mb = 1024 * 1024
        val notes = listOfNotNull(
            "drawn ${session.tiles.coarser} step(s) coarser to fit".takeIf { session.tiles.coarser > 0 },
            "budget halved: Java memory is low".takeIf { pacer.heapLow },
            "uploading ${pacer.uploads} per frame: the game is busy".takeIf { pacer.slow },
        )
        return "Texture memory ${session.tiles.heldBytes / mb} / ${pacer.budgetBytes() / mb} MB" + if (notes.isEmpty()) "" else " (${notes.joinToString("; ")})"
    }

    /** Whether dropping map files onto the game window reaches [TerrainFiles] (`MouseHandlerMixin`). */
    private fun dropHookInstalled() = injected("net.minecraft.client.MouseHandler", "skysmapexposer\$onDrop")

    override fun onInitializeClient() {
        Config.load()
        KeyMappingHelper.registerKeyMapping(pingKey)

        // Under the whole HUD, like anything else out in the world: the minimap (Xaero's draws
        // just after this point), crosshair, hotbar, health and XP bars and chat all go over it.
        HudElementRegistry.attachElementBefore(PlayerBeacon.DRAWN_BEFORE, BEACON, PlayerBeacon)

        MapMods.adapter?.init()
        ChatShare.register()

        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            Session.start(addressOf(client))
            MapMods.adapter?.join()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            Session.end()
            com.skystormer.skysmapexposer.terrain.TerrainFill.forget()
            ChatShare.forget()
            PlayerPings.forget()
            MapMods.adapter?.leave()
            HookErrors.forget()
        }

        // A chunk arriving from the server is what makes Xaero redraw it, so this is the moment
        // your map of it becomes current.
        ClientChunkEvents.CHUNK_LOAD.register { level, chunk ->
            val session = Session.current ?: return@register
            val dimension = dimensionOf(level)
            session.visits.record(dimension, chunk.pos.x(), chunk.pos.z(), Clock.nowMinutes())
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!hookChecked) {
                hookChecked = true
                MapMods.adapter?.checkHooks()
                if (!dropHookInstalled()) Log.warn("Could not hook dropping files onto the game; open map files with /mapexposer terrain open")
            }
            client.player?.let { player ->
                Config.takeLoadProblem()?.let { player.sendSystemMessage(Component.literal("§e$it")) }
                if (!noMapSaid && !MapMods.any) {
                    noMapSaid = true
                    player.sendSystemMessage(Component.literal("§eSky's Map Exposer has no map to show anything on: install Xaero's World Map or JourneyMap"))
                }
            }
            MapMods.adapter?.tick(client, Session.current)
            ChatShare.tick()
            PlayerPings.tick()
            while (pingKey.consumeClick()) Ui.say(PlayerPings.sendLookedAt())
            MapPrep.tick(Session.current)
            AutoTest.tick(client)
            val session = Session.current ?: return@register
            ticks++
            // Chunks you stay near are kept up to date by Xaero, so they stay current here too.
            if (ticks % 400 == 0L) recordLoadedChunks(client, session)
            if (ticks % 6000 == 0L) {
                session.visits.save()
                session.gaps.save()
                session.unshowable.save()
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            val root = ClientCommands.literal("mapexposer")
                    .executes(::status)
                    .then(ClientCommands.literal("on").executes { setEnabled(it, true) })
                    .then(ClientCommands.literal("off").executes { setEnabled(it, false) })
                    .then(ClientCommands.literal("reload").executes(::reload))
                    .then(ClientCommands.literal("players").executes(::players))
                    .then(
                        ClientCommands.literal("ping")
                            .executes { say(it, PlayerPings.sendLookedAt()) }
                            .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("z", IntegerArgumentType.integer()).executes(::ping)))
                    )
                    .then(
                        ClientCommands.literal("trust")
                            .executes(::trusted)
                            .then(ClientCommands.argument("player", StringArgumentType.word())
                                .suggests { _, builder -> ChatShare.others().forEach(builder::suggest); builder.buildFuture() }
                                .executes { setTrusted(it, true) })
                    )
                    .then(
                        ClientCommands.literal("untrust").then(
                            ClientCommands.argument("player", StringArgumentType.word())
                                .suggests { _, builder -> PlayerPings.trustedOnline().forEach(builder::suggest); builder.buildFuture() }
                                .executes { setTrusted(it, false) }
                        )
                    )
                    .then(
                        ClientCommands.literal("pingto")
                            .executes(::pingTo)
                            .then(ClientCommands.literal("add").then(
                                ClientCommands.argument("player", StringArgumentType.word())
                                    .suggests { _, builder -> PlayerPings.trustedOnline().forEach(builder::suggest); builder.buildFuture() }
                                    .executes { setPingedTo(it, true) }
                            ))
                            .then(ClientCommands.literal("remove").then(
                                ClientCommands.argument("player", StringArgumentType.word())
                                    .suggests { _, builder -> PlayerPings.recipients().forEach(builder::suggest); builder.buildFuture() }
                                    .executes { setPingedTo(it, false) }
                            ))
                    )
                    .then(
                        ClientCommands.literal("share")
                            .then(ClientCommands.literal("cancel").executes(::cancelShare))
                            .then(
                                ClientCommands.argument("player", StringArgumentType.word())
                                    .suggests { _, builder -> ChatShare.others().forEach(builder::suggest); builder.buildFuture() }
                                    .then(ClientCommands.literal("waypoints").executes(::shareWaypoints))
                            )
                    )
                    .then(
                        ClientCommands.literal("shared").then(
                            ClientCommands.argument("id", IntegerArgumentType.integer(0))
                                .then(ClientCommands.literal("waypoints").executes {
                                    it.source.sendFeedback(Component.literal(ChatShare.addWaypoints(IntegerArgumentType.getInteger(it, "id"))))
                                    1
                                })
                        )
                    )
                    .then(ClientCommands.literal("refresh").executes(::refresh))
                    .then(
                        ClientCommands.literal("prepare")
                            .executes(::prepare)
                            .then(ClientCommands.literal("cancel").executes {
                                it.source.sendFeedback(Component.literal(if (MapPrep.cancel()) "Stopped preparing the map; what is done stays" else "The map is not being prepared"))
                                1
                            })
                    )
                    .then(
                        ClientCommands.literal("stale").then(
                            ClientCommands.argument("days", DoubleArgumentType.doubleArg(0.0, 3650.0)).executes(::setStale)
                        )
                    )
            MapMods.adapter?.commands(root)
            dispatcher.register(root)
        }
    }

    /**
     * The server's address, or `singleplayer` for a world on this computer: BlueMap also runs in
     * single player, on its own local web server, and can be listed in the config under that name.
     */
    fun addressOf(client: Minecraft): String? =
        client.currentServer?.ip ?: if (client.hasSingleplayerServer()) "singleplayer" else null

    private fun dimensionOf(level: ClientLevel): String = level.dimension().identifier().toString()

    private fun recordLoadedChunks(client: Minecraft, session: Session) {
        val level = client.level ?: return
        val player = client.player ?: return
        val dimension = dimensionOf(level)
        val now = Clock.nowMinutes()
        val radius = client.options.effectiveRenderDistance
        val centreX = player.chunkPosition().x()
        val centreZ = player.chunkPosition().z()
        for (chunkX in centreX - radius..centreX + radius) {
            for (chunkZ in centreZ - radius..centreZ + radius) {
                if (level.chunkSource.hasChunk(chunkX, chunkZ)) session.visits.record(dimension, chunkX, chunkZ, now)
            }
        }
    }

    private fun status(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        val session = Session.current
        source.sendFeedback(Component.literal("§bSky's Map Exposer§r ${if (Config.enabled) "§aon" else "§coff"}§r, stale after ${Config.staleDays} day(s)"))
        val say = { line: String -> source.sendFeedback(Component.literal(line)) }
        MapMods.adapter?.status(say) ?: say("§eNo map to show anything on: install Xaero's World Map or JourneyMap")
        val off = HookErrors.switchedOff()
        if (off.isNotEmpty()) source.sendFeedback(Component.literal("§cOff after an error until you rejoin: ${off.joinToString()}§r (the log says why)"))
        if (!dropHookInstalled()) source.sendFeedback(Component.literal("§eDropping map files onto the game: §cmissing§r; /mapexposer terrain open opens them"))
        if (session == null) {
            source.sendFeedback(Component.literal("§eThis server has no BlueMap in config/skysmapexposer.json"))
        } else {
            val level = Minecraft.getInstance().level
            val visited = level?.let { session.visits.count(dimensionOf(it)) } ?: 0
            source.sendFeedback(Component.literal("BlueMap: ${session.server.url}  maps: ${session.server.maps}"))
            source.sendFeedback(
                Component.literal(
                    "Chunks recorded here: $visited, " +
                        "tiles in memory: ${session.tiles.size} (${session.tiles.countIn(TileStore.State.FAILED)} failed)"
                )
            )
            source.sendFeedback(Component.literal(textureMemory(session)))
            MapPrep.status()?.let { source.sendFeedback(Component.literal(it)) }
        }
        val locked = LockedPlayers.count()
        if (locked > 0) source.sendFeedback(Component.literal("Locked on to $locked player(s); /mapexposer players lists everyone"))
        MapMods.adapter?.summary()?.takeIf { it.isNotEmpty() }?.let { say("§7$it") }
        return 1
    }

    /**
     * Opens the player list. The chat screen is still up while a command runs, so this waits for
     * the next tick, by which time it has closed and there is a screen to replace.
     */
    private fun players(context: CommandContext<FabricClientCommandSource>): Int {
        val minecraft = Minecraft.getInstance()
        minecraft.execute { minecraft.gui.setScreen(PlayerListScreen(null)) }
        return 1
    }

    private fun say(context: CommandContext<FabricClientCommandSource>, message: String): Int {
        context.source.sendFeedback(Component.literal(message))
        return 1
    }

    /** Pings a block of the dimension you are in. */
    private fun ping(context: CommandContext<FabricClientCommandSource>): Int {
        val dimension = dimensionOf(context.source.level)
        return say(context, PlayerPings.send(dimension, IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "z")))
    }

    /** Who you trust among those online. Anyone else stays trusted, but only shows here once they are on. */
    private fun trusted(context: CommandContext<FabricClientCommandSource>): Int {
        val online = PlayerPings.trustedOnline()
        return say(context, if (online.isEmpty()) "Nobody you trust is online; /mapexposer trust <player> adds someone" else "Trusted and online: ${online.joinToString()}")
    }

    private fun setTrusted(context: CommandContext<FabricClientCommandSource>, trusted: Boolean): Int {
        val wanted = StringArgumentType.getString(context, "player")
        val (uuid, name) = PlayerPings.online(wanted) ?: return say(context, "$wanted is not on the server")
        PlayerPings.setTrusted(uuid, trusted)
        return say(context, if (trusted) "Taking pings from $name; /mapexposer pingto add $name sends yours to them" else "No longer trusting $name")
    }

    /** Who online gets your pings. */
    private fun pingTo(context: CommandContext<FabricClientCommandSource>): Int {
        val online = PlayerPings.recipients()
        return say(context, if (online.isEmpty()) "Nobody you ping is online; /mapexposer pingto add <player> picks someone you trust" else "Your pings go to: ${online.joinToString()}")
    }

    private fun setPingedTo(context: CommandContext<FabricClientCommandSource>, on: Boolean): Int {
        val wanted = StringArgumentType.getString(context, "player")
        val (uuid, name) = PlayerPings.online(wanted) ?: return say(context, "$wanted is not on the server")
        if (on && !PlayerPings.isTrusted(uuid)) return say(context, "Trust $name first: /mapexposer trust $name")
        PlayerPings.setPingedTo(uuid, on)
        return say(context, if (on) "Your pings go to $name, once they trust you too" else "No longer pinging $name")
    }

    /** Every waypoint of the dimension you are in, to one player; the map's menu picks some. */
    private fun shareWaypoints(context: CommandContext<FabricClientCommandSource>): Int {
        val to = StringArgumentType.getString(context, "player")
        val mine = MapMods.adapter?.myWaypoints()
        val said = if (mine == null) "No waypoints to share: your map has no waypoint world open"
        else ChatShare.shareWaypoints(to, mine.first, mine.second)
        context.source.sendFeedback(Component.literal(said))
        return 1
    }

    private fun cancelShare(context: CommandContext<FabricClientCommandSource>): Int {
        context.source.sendFeedback(Component.literal(if (ChatShare.cancel()) "Stopped sharing your map" else "No map is being shared"))
        return 1
    }

    private fun setEnabled(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
        Config.enabled = enabled
        Config.save()
        context.source.sendFeedback(Component.literal("Sky's Map Exposer ${if (enabled) "§aon" else "§coff"}"))
        return 1
    }

    private fun setStale(context: CommandContext<FabricClientCommandSource>): Int {
        Config.staleDays = DoubleArgumentType.getDouble(context, "days")
        Config.save()
        context.source.sendFeedback(Component.literal("Your map now counts as out of date after ${Config.staleDays} day(s)"))
        return 1
    }

    private fun reload(context: CommandContext<FabricClientCommandSource>): Int {
        Config.load()
        Session.start(addressOf(Minecraft.getInstance()))
        context.source.sendFeedback(Component.literal("Reloaded config/skysmapexposer.json"))
        return 1
    }

    private fun refresh(context: CommandContext<FabricClientCommandSource>): Int {
        val session = Session.current
        if (session == null) {
            context.source.sendError(Component.literal("This server has no BlueMap in config/skysmapexposer.json"))
            return 0
        }
        session.tiles.refetchBefore = System.currentTimeMillis()
        context.source.sendFeedback(Component.literal("Downloading BlueMap's tiles again as they are shown"))
        return 1
    }

    private fun prepare(context: CommandContext<FabricClientCommandSource>): Int {
        val session = Session.current
        if (session == null) {
            context.source.sendError(Component.literal("This server has no BlueMap in config/skysmapexposer.json"))
            return 0
        }
        context.source.sendFeedback(Component.literal(MapPrep.status()?.takeIf { MapPrep.running } ?: MapPrep.start(session, MapMods.adapter?.shownDimension())))
        return 1
    }
}
