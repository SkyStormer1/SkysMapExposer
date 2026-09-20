package com.skystormer.skysmapexposer

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.context.CommandContext
import com.skystormer.skysmapexposer.gui.PlayerListScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

object MapExposerClient : ClientModInitializer {

    private var ticks = 0L
    private var hookChecked = false
    private var worldMapPinsAdded = false
    private var minimapPinsAdded = false

    /** The heads of locked players, as a HUD element of this mod's own. */
    private val BEACON: Identifier = Identifier.fromNamespaceAndPath("skysmapexposer", "locked_players")

    /**
     * Whether the mixin made it into Xaero's map screen. It is optional, so that an unsupported
     * Xaero version costs the backfill and not the game; this is how that case gets noticed.
     * Loading the class is harmless: Xaero's own screen code does nothing until it is opened.
     */
    fun hookInstalled(): Boolean = try {
        Class.forName("xaero.map.gui.GuiMap").declaredMethods.any { it.name.contains("drawBackfill") }
    } catch (e: Throwable) {
        Log.error("Could not look for the Xaero hook", e)
        false
    }

    fun minimapHookInstalled(): Boolean = try {
        Class.forName("xaero.common.mods.SupportXaeroWorldmap").declaredMethods.any { it.name.contains("drawBackfill") }
    } catch (e: Throwable) {
        false
    }

    override fun onInitializeClient() {
        Config.load()

        // Under the chat, so a locked player's head never covers what someone is saying.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, BEACON, PlayerBeacon)

        addPlayersButton()

        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            Session.start(addressOf(client))
            // Xaero's config is up by now; this is a one-off nudge to one of its own options.
            WaypointScope.matchToMapDimension()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            Session.end()
            MapView.forget()
        }

        // A chunk arriving from the server is what makes Xaero redraw it, so this is the moment
        // your map of it becomes current.
        ClientChunkEvents.CHUNK_LOAD.register { level, chunk ->
            val session = Session.current ?: return@register
            val dimension = dimensionOf(level)
            session.visits.record(dimension, chunk.pos.x(), chunk.pos.z(), Clock.nowMinutes())
            session.forgetGap(dimension, chunk.pos.x(), chunk.pos.z())
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!hookChecked) {
                hookChecked = true
                if (!hookInstalled()) Log.warn("This version of Xaero's World Map is not supported; nothing will be drawn on it")
                if (FabricLoader.getInstance().isModLoaded("xaerominimap") && !minimapHookInstalled()) {
                    Log.warn("This version of Xaero's Minimap is not supported; nothing will be drawn on it")
                }
            }
            addPins()
            MapView.tick(client)
            val session = Session.current ?: return@register
            ticks++
            // Chunks you stay near are kept up to date by Xaero, so they stay current here too.
            if (ticks % 400 == 0L) recordLoadedChunks(client, session)
            if (ticks % 6000 == 0L) session.visits.save()
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("mapexposer")
                    .executes(::status)
                    .then(ClientCommands.literal("on").executes { setEnabled(it, true) })
                    .then(ClientCommands.literal("off").executes { setEnabled(it, false) })
                    .then(ClientCommands.literal("reload").executes(::reload))
                    .then(ClientCommands.literal("players").executes(::players))
                    .then(ClientCommands.literal("refresh").executes(::refresh))
                    .then(
                        ClientCommands.literal("stale").then(
                            ClientCommands.argument("days", DoubleArgumentType.doubleArg(0.0, 3650.0)).executes(::setStale)
                        )
                    )
            )
        }
    }

    /**
     * A Players button in the top-left corner of Xaero's world map, under Xaero's own settings
     * button — the right-click menu has the same thing, but nobody finds a right-click menu.
     *
     * Other mods put buttons in that same corner (Sky's Map Shapes puts a Shapes button there), so
     * rather than guess at a free spot, this looks at what is actually on the screen and takes the
     * first gap down the left edge. It is placed twice: once as the screen is built, and again on
     * the first frame after that. The second time is the one that counts — a mod whose own
     * `AFTER_INIT` runs after this one has added its button by then, and this one steps below it.
     */
    private fun addPlayersButton() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen.javaClass.name != "xaero.map.gui.GuiMap") return@register
            try {
                val widgets = Screens.getWidgets(screen)
                val button = Button.builder(Component.literal("Players")) {
                    Screens.getMinecraft(screen).gui.setScreen(PlayerListScreen(screen))
                }
                    .bounds(0, freeSlot(widgets, null), MapButtons.WIDTH, MapButtons.HEIGHT)
                    .tooltip(Tooltip.create(Component.literal(
                        "Everyone BlueMap can see: search them, jump the map to them, lock on, or copy their coordinates."
                    )))
                    .build()
                widgets.add(button)
                var placed = false
                ScreenEvents.beforeExtract(screen).register { _, _, _, _, _ ->
                    if (!placed) {
                        placed = true
                        button.y = freeSlot(widgets, button)
                    }
                }
            } catch (e: Throwable) {
                Log.error("Could not add the Players button to Xaero's world map", e)
            }
        }
    }

    /**
     * Where the Players button goes, given what is already on [widgets]. [mine] is the button being
     * placed, which does not count as being in its own way. The arithmetic is [MapButtons].
     */
    private fun freeSlot(widgets: List<AbstractWidget>, mine: AbstractWidget?): Int =
        MapButtons.firstFreeSlot(
            widgets.filter { it !== mine && it.visible }.map { intArrayOf(it.x, it.y, it.width, it.height) }
        )

    /**
     * Registers the marker pins with Xaero's world map and minimap, once each has started. A
     * failure here costs the pins, not the game.
     */
    private fun addPins() {
        if (!worldMapPinsAdded) {
            worldMapPinsAdded = try {
                MarkerElements.register()
            } catch (e: Throwable) {
                Log.error("Could not add BlueMap markers to Xaero's world map", e)
                true
            }
        }
        if (!minimapPinsAdded) {
            minimapPinsAdded = if (!FabricLoader.getInstance().isModLoaded("xaerominimap")) {
                true
            } else {
                try {
                    MinimapMarkerElements.register()
                } catch (e: Throwable) {
                    Log.error("Could not add BlueMap markers to Xaero's minimap", e)
                    true
                }
            }
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
        val hook = when {
            Overlay.hookRan -> "§aworking"
            hookInstalled() -> "§einstalled; open the world map"
            else -> "§cmissing: this Xaero's World Map version is not supported"
        }
        source.sendFeedback(Component.literal("Xaero hook: $hook"))
        fun ran(hook: Boolean) = if (hook) "§aworking§r" else "§enot seen yet§r"
        source.sendFeedback(
            Component.literal(
                "On another dimension's map — arrow hidden: ${ran(MapView.arrowHookRan)}, " +
                    "entities hidden: ${ran(MapView.radarHookRan)} (open one to check)"
            )
        )
        if (session == null) {
            source.sendFeedback(Component.literal("§eThis server has no BlueMap in config/skysmapexposer.json"))
        } else {
            val level = Minecraft.getInstance().level
            val visited = level?.let { session.visits.count(dimensionOf(it)) } ?: 0
            source.sendFeedback(Component.literal("BlueMap: ${session.server.url}  maps: ${session.server.maps}"))
            source.sendFeedback(
                Component.literal(
                    "Chunks recorded here: $visited, Xaero region files: ${session.regionAges.count()}, " +
                        "tiles in memory: ${session.tiles.size} (${session.tiles.countIn(TileStore.State.FAILED)} failed)"
                )
            )
        }
        val locked = LockedPlayers.count()
        if (locked > 0) source.sendFeedback(Component.literal("Locked on to $locked player(s); /mapexposer players lists everyone"))
        source.sendFeedback(Component.literal("§7${Overlay.lastSummary}"))
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
        session.tiles.clear()
        context.source.sendFeedback(Component.literal("Fetching BlueMap's tiles again"))
        return 1
    }
}
