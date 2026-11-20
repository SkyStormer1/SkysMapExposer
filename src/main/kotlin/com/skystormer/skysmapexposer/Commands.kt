package com.skystormer.skysmapexposer

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.skystormer.skysmapexposer.gui.PlayerListScreen
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/** The `/mapexposer` command and what each part of it does; the installed map adds its own ([MapAdapter.commands]). */
object Commands {

    fun register() = ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
        val root = ClientCommands.literal("mapexposer")
                .executes(::status)
                .then(ClientCommands.literal("on").executes { setEnabled(it, true) })
                .then(ClientCommands.literal("off").executes { setEnabled(it, false) })
                .then(ClientCommands.literal("reload").executes(::reload))
                .then(ClientCommands.literal("players").executes(::players))
                .then(
                    ClientCommands.literal("ping")
                        .executes { Ui.reply(it, PlayerPings.sendLookedAt()) }
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
                // What was shared with you, by the id its chat message gives: waypoints, or a terrain file.
                .then(
                    ClientCommands.literal("shared").then(
                        ClientCommands.argument("id", IntegerArgumentType.integer(0))
                            .then(ClientCommands.literal("waypoints").executes {
                                Ui.reply(it, ChatShare.addWaypoints(IntegerArgumentType.getInteger(it, "id")))
                            })
                            .then(ClientCommands.literal("blank").executes { addShared(it, unexploredOnly = true) }
                                .then(ClientCommands.literal("confirm").executes { addShared(it, unexploredOnly = true, confirmed = true) }))
                            .then(ClientCommands.literal("all").executes { addShared(it, unexploredOnly = false) }
                                .then(ClientCommands.literal("confirm").executes { addShared(it, unexploredOnly = false, confirmed = true) }))
                    )
                )
                .then(
                    ClientCommands.literal("season")
                        .executes { Ui.reply(it, Seasons.list()) }
                        .then(ClientCommands.literal("set").then(
                            ClientCommands.argument("time", StringArgumentType.greedyString()).executes { Ui.reply(it, setSeason(StringArgumentType.getString(it, "time"))) }
                        ))
                        .then(ClientCommands.literal("remove").executes { Ui.reply(it, setSeason(null)) })
                )
                .then(
                    ClientCommands.literal("terrain").then(ClientCommands.literal("open")
                        .then(ClientCommands.argument("file", StringArgumentType.greedyString())
                            .suggests { _, builder -> SharedTerrain.list().forEach(builder::suggest); builder.buildFuture() }
                            .executes(::openTerrain)))
                )
                .then(ClientCommands.literal("refresh").executes(::refresh))
                .then(
                    // What a clickable position in chat runs ([ChatCoords]).
                    ClientCommands.literal("chatpos").then(ClientCommands.argument("id", IntegerArgumentType.integer(0))
                        .executes { ChatCoords.clicked(IntegerArgumentType.getInteger(it, "id"))?.let { text -> Ui.reply(it, text) } ?: 1 })
                )
                .then(
                    ClientCommands.literal("surface").then(
                        ClientCommands.argument("dimension", StringArgumentType.greedyString()).executes {
                            val dimension = StringArgumentType.getString(it, "dimension")
                            Ui.reply(it, MapMods.adapter?.showSurface(dimension) ?: "Neither Xaero's World Map nor JourneyMap is installed")
                        }
                    )
                )
                .then(
                    ClientCommands.literal("cavenotice")
                        .then(ClientCommands.literal("on").executes { setCaveNotice(it, true) })
                        .then(ClientCommands.literal("off").executes { setCaveNotice(it, false) })
                )
                .then(
                    ClientCommands.literal("prepare")
                        .executes(::prepare)
                        .then(ClientCommands.literal("cancel").executes {
                            Ui.reply(it, if (MapPrep.cancel()) "Stopped preparing the map; what is done stays" else "The map is not being prepared")
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

    private fun status(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        val session = Session.current
        source.sendFeedback(Component.literal("§bSky's Map Exposer§r ${if (Config.enabled) "§aon" else "§coff"}§r, stale after ${Config.staleDays} day(s)"))
        val say = { line: String -> source.sendFeedback(Component.literal(line)) }
        MapMods.adapter?.status(say) ?: say("§eNo map to show anything on: install Xaero's World Map or JourneyMap")
        val off = HookErrors.switchedOff()
        if (off.isNotEmpty()) source.sendFeedback(Component.literal("§cOff after an error until you rejoin: ${off.joinToString()}§r (the log says why)"))
        if (!MapExposerClient.dropHookInstalled()) source.sendFeedback(Component.literal("§eDropping map files onto the game: §cmissing§r; /mapexposer terrain open opens them"))
        if (session == null) {
            source.sendFeedback(Component.literal("§eThis server has no BlueMap in config/skysmapexposer.json"))
        } else {
            val level = Minecraft.getInstance().level
            val visited = level?.let { session.visits.count(MapExposerClient.dimensionOf(it)) } ?: 0
            source.sendFeedback(Component.literal("BlueMap: ${session.server.url}  maps: ${session.server.maps}"))
            source.sendFeedback(
                Component.literal(
                    "Chunks recorded here: $visited, " +
                        "tiles in memory: ${session.tiles.size} (${session.tiles.countIn(TileStore.State.FAILED)} failed)"
                )
            )
            source.sendFeedback(Component.literal(MapExposerClient.textureMemory(session)))
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

    /** The season start of the dimension the map shows, or else the one you are in; not while the map is being written. */
    private fun setSeason(text: String?): String = Seasons.set(
        text,
        MapMods.adapter?.shownDimension() ?: Minecraft.getInstance().level?.dimension()?.identifier()?.toString(),
        busy = MapMods.adapter?.downloadStatus() != null,
    )

    /** Whether chat says why BlueMap's terrain is missing in cave mode ([CaveNotice]). */
    private fun setCaveNotice(context: CommandContext<FabricClientCommandSource>, on: Boolean): Int {
        Config.caveNotice = on
        Config.save()
        return Ui.reply(context, if (on) "Chat will say again when cave mode hides BlueMap's terrain" else
            "Chat won't say again when cave mode hides BlueMap's terrain; the line at the top of the map still does. /mapexposer cavenotice on brings it back")
    }

    /** A map file from the shared maps folder, as if dropped onto the game. */
    private fun openTerrain(context: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(context, "file")
        val path = SharedTerrain.folder.resolve(name).normalize()
        if (!path.startsWith(SharedTerrain.folder) || !java.nio.file.Files.isRegularFile(path)) {
            context.source.sendError(Component.literal("No map file called $name in the shared maps folder"))
            return 0
        }
        SharedTerrain.open(path)
        return 1
    }

    /** The buttons under a map file opened: adds it to the map in use. */
    private fun addShared(context: CommandContext<FabricClientCommandSource>, unexploredOnly: Boolean, confirmed: Boolean = false): Int {
        val id = IntegerArgumentType.getInteger(context, "id")
        val file = SharedTerrain.opened(id)
        val adapter = MapMods.adapter
        val text = when {
            file == null -> "That map file is no longer open; drop it onto the game again"
            adapter == null -> "Shared terrain goes into Xaero's World Map or JourneyMap; neither is installed"
            else -> adapter.addSharedTerrain(file, id, unexploredOnly, confirmed) ?: return 1
        }
        return Ui.reply(context, text)
    }

    /** Pings a block of the dimension you are in. */
    private fun ping(context: CommandContext<FabricClientCommandSource>): Int {
        val dimension = MapExposerClient.dimensionOf(context.source.level)
        return Ui.reply(context, PlayerPings.send(dimension, IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "z")))
    }

    /** Who you trust among those online. Anyone else stays trusted, but only shows here once they are on. */
    private fun trusted(context: CommandContext<FabricClientCommandSource>): Int {
        val online = PlayerPings.trustedOnline()
        return Ui.reply(context, if (online.isEmpty()) "Nobody you trust is online; /mapexposer trust <player> adds someone" else "Trusted and online: ${online.joinToString()}")
    }

    private fun setTrusted(context: CommandContext<FabricClientCommandSource>, trusted: Boolean): Int {
        val wanted = StringArgumentType.getString(context, "player")
        val (uuid, name) = PlayerPings.online(wanted) ?: return Ui.reply(context, "$wanted is not on the server")
        PlayerPings.setTrusted(uuid, trusted)
        return Ui.reply(context, if (trusted) "Taking pings from $name; /mapexposer pingto add $name sends yours to them" else "No longer trusting $name")
    }

    /** Who online gets your pings. */
    private fun pingTo(context: CommandContext<FabricClientCommandSource>): Int {
        val online = PlayerPings.recipients()
        return Ui.reply(context, if (online.isEmpty()) "Nobody you ping is online; /mapexposer pingto add <player> picks someone you trust" else "Your pings go to: ${online.joinToString()}")
    }

    private fun setPingedTo(context: CommandContext<FabricClientCommandSource>, on: Boolean): Int {
        val wanted = StringArgumentType.getString(context, "player")
        val (uuid, name) = PlayerPings.online(wanted) ?: return Ui.reply(context, "$wanted is not on the server")
        if (on && !PlayerPings.isTrusted(uuid)) return Ui.reply(context, "Trust $name first: /mapexposer trust $name")
        PlayerPings.setPingedTo(uuid, on)
        return Ui.reply(context, if (on) "Your pings go to $name, once they trust you too" else "No longer pinging $name")
    }

    /** Every waypoint of the dimension you are in, to one player; the map's menu picks some. */
    private fun shareWaypoints(context: CommandContext<FabricClientCommandSource>): Int {
        val to = StringArgumentType.getString(context, "player")
        val mine = MapMods.adapter?.myWaypoints()
        val said = if (mine == null) "No waypoints to share: your map has no waypoint world open"
        else ChatShare.shareWaypoints(to, mine.first, mine.second.map { it.waypoint })
        return Ui.reply(context, said)
    }

    private fun cancelShare(context: CommandContext<FabricClientCommandSource>): Int {
        return Ui.reply(context, if (ChatShare.cancel()) "Stopped sharing your map" else "No map is being shared")
    }

    private fun setEnabled(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
        Config.enabled = enabled
        Config.save()
        return Ui.reply(context, "Sky's Map Exposer ${if (enabled) "§aon" else "§coff"}")
    }

    private fun setStale(context: CommandContext<FabricClientCommandSource>): Int {
        Config.staleDays = DoubleArgumentType.getDouble(context, "days")
        Config.save()
        return Ui.reply(context, "Your map now counts as out of date after ${Config.staleDays} day(s)")
    }

    private fun reload(context: CommandContext<FabricClientCommandSource>): Int {
        Config.load()
        Session.start(MapExposerClient.addressOf(Minecraft.getInstance()))
        return Ui.reply(context, "Reloaded config/skysmapexposer.json")
    }

    /** The BlueMap session, or null after saying in chat that this server has none. */
    private fun sessionOrSay(context: CommandContext<FabricClientCommandSource>): Session? =
        Session.current ?: null.also { context.source.sendError(Component.literal("This server has no BlueMap in config/skysmapexposer.json")) }

    private fun refresh(context: CommandContext<FabricClientCommandSource>): Int {
        val session = sessionOrSay(context) ?: return 0
        session.tiles.refetchBefore = System.currentTimeMillis()
        return Ui.reply(context, "Downloading BlueMap's tiles again as they are shown")
    }

    private fun prepare(context: CommandContext<FabricClientCommandSource>): Int {
        val session = sessionOrSay(context) ?: return 0
        return Ui.reply(context, MapPrep.status()?.takeIf { MapPrep.running } ?: MapPrep.start(session, MapMods.adapter?.shownDimension()))
    }
}
