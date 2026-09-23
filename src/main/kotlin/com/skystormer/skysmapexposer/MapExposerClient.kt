package com.skystormer.skysmapexposer

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
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

    /**
     * Whether one of this mod's optional injections reached [className]. Every injection here is
     * `require = 0`, so a Xaero that has moved the code costs the feature and not the game — but
     * that also means a failed injection is silent. Loading the class and looking for the method
     * mixin would have added is the only way to tell, and it beats wondering why nothing happened.
     */
    private fun injected(className: String, method: String): Boolean = try {
        Class.forName(className).declaredMethods.any { it.name.contains(method) }
    } catch (e: Throwable) {
        false
    }

    override fun onInitializeClient() {
        Config.load()

        // Under the chat, so a locked player's head never covers what someone is saying.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, BEACON, PlayerBeacon)

        addMapButtons()

        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            Session.start(addressOf(client))
            // Xaero's config is up by now; this is a one-off nudge to one of its own options.
            WaypointScope.matchToMapDimension()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            Session.end()
            MapView.forget()
            MapBackup.forget()
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
                if (!hookInstalled()) Log.warn("This version of Xaero's World Map is not supported; nothing will be drawn on it")
                if (FabricLoader.getInstance().isModLoaded("xaerominimap") && !minimapHookInstalled()) {
                    Log.warn("This version of Xaero's Minimap is not supported; nothing will be drawn on it")
                }
                if (!injected("xaero.map.gui.GuiMap", "hideArrowInOtherDimension")) {
                    Log.warn("Could not hook Xaero's player arrow; it will show on another dimension's map")
                }
                if (FabricLoader.getInstance().isModLoaded("xaerominimap")) {
                    val radar = "xaero.hud.minimap.radar.render.element.RadarRenderer"
                    // The drawing gate is the one that has to hold; the other is only a shortcut.
                    if (!injected(radar, "hideRadarDot")) {
                        Log.warn("Could not hook Xaero's entity radar; entities will show on another dimension's map")
                    } else if (!injected(radar, "hideRadarInOtherDimension")) {
                        Log.warn("Only half of the entity radar hook applied; entities are still hidden, less cheaply")
                    }
                    val tracker = "xaero.hud.minimap.player.tracker.PlayerTrackerMinimapElementRenderer"
                    if (!injected(tracker, "hideTrackerDot")) {
                        Log.warn("Could not hook Xaero's tracked players; they will show on another dimension's map")
                    }
                }
            }
            addPins()
            MapView.tick(client)
            val session = Session.current ?: return@register
            ticks++
            // Chunks you stay near are kept up to date by Xaero, so they stay current here too.
            if (ticks % 400 == 0L) recordLoadedChunks(client, session)
            if (ticks % 40 == 0L) learnGaps(client, session)
            if (ticks % 6000 == 0L) {
                session.visits.save()
                session.gaps.save()
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("mapexposer")
                    .executes(::status)
                    .then(ClientCommands.literal("on").executes { setEnabled(it, true) })
                    .then(ClientCommands.literal("off").executes { setEnabled(it, false) })
                    .then(ClientCommands.literal("reload").executes(::reload))
                    .then(ClientCommands.literal("players").executes(::players))
                    .then(ClientCommands.literal("backup").executes(::backup))
                    .then(ClientCommands.literal("backups").executes(::backups))
                    .then(
                        ClientCommands.literal("restore").then(
                            ClientCommands.argument("name", StringArgumentType.string()).executes(::restore)
                        )
                    )
                    .then(
                        ClientCommands.literal("reloadregion")
                            .executes(::whichRegion)
                            .then(
                                ClientCommands.argument("x", IntegerArgumentType.integer(-30000, 30000)).then(
                                    ClientCommands.argument("z", IntegerArgumentType.integer(-30000, 30000))
                                        .executes(::reloadRegion)
                                )
                            )
                    )
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
     * Two buttons in the top-left corner of Xaero's world map, under Xaero's own settings button:
     * **Players**, which opens the player list (the right-click menu has the same thing, but nobody
     * finds a right-click menu), and **Markers**, which shows or hides BlueMap's markers in one
     * click.
     *
     * Other mods put buttons in that same corner (Sky's Map Shapes puts a Shapes button there), so
     * rather than guess at a free spot, each looks at what is actually on the screen and takes the
     * first gap down the left edge. They are placed twice: once as the screen is built, and again
     * on the first frame after that. The second time is the one that counts — a mod whose own
     * `AFTER_INIT` runs after this one has added its button by then, and these step below it.
     */
    private fun addMapButtons() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen.javaClass.name != "xaero.map.gui.GuiMap") return@register
            try {
                val widgets = Screens.getWidgets(screen)
                val players = Button.builder(Component.literal("Players")) {
                    Screens.getMinecraft(screen).gui.setScreen(PlayerListScreen(screen))
                }
                    .bounds(0, freeSlot(widgets, null), MapButtons.WIDTH, MapButtons.HEIGHT)
                    .tooltip(Tooltip.create(Component.literal(
                        "Everyone BlueMap can see: search them, jump the map to them, lock on, or copy their coordinates."
                    )))
                    .build()
                widgets.add(players)

                val markers = Button.builder(markersLabel()) { button ->
                    Config.showMarkers = !Config.showMarkers
                    Config.save()
                    button.message = markersLabel()
                    button.setTooltip(markersTooltip())
                }
                    .bounds(0, freeSlot(widgets, null), MapButtons.WIDTH, MapButtons.HEIGHT)
                    .tooltip(markersTooltip())
                    .build()
                widgets.add(markers)

                var placed = false
                ScreenEvents.beforeExtract(screen).register { _, _, _, _, _ ->
                    if (!placed) {
                        placed = true
                        // In order, so Markers takes the gap below wherever Players ends up.
                        players.y = freeSlot(widgets, players, markers)
                        markers.y = freeSlot(widgets, markers)
                    }
                }
            } catch (e: Throwable) {
                Log.error("Could not add the Players and Markers buttons to Xaero's world map", e)
            }
        }
    }

    /** "Markers", struck through and grey while BlueMap's markers are hidden. */
    private fun markersLabel(): Component =
        if (Config.showMarkers) Component.literal("Markers")
        else Component.literal("Markers").withStyle { it.withStrikethrough(true).withColor(0x888888) }

    private fun markersTooltip(): Tooltip = Tooltip.create(Component.literal(
        if (Config.showMarkers) "BlueMap's markers are shown on the world map and minimap. Click to hide them."
        else "BlueMap's markers are hidden. Click to show them on the world map and minimap again."
    ))

    /**
     * Where a button goes, given what is already on [widgets]. [mine] is the button being placed,
     * which does not count as being in its own way, and neither does anything in [ignoring] (a
     * button still to be placed after it). The arithmetic is [MapButtons].
     */
    private fun freeSlot(widgets: List<AbstractWidget>, mine: AbstractWidget?, vararg ignoring: AbstractWidget): Int =
        MapButtons.firstFreeSlot(
            widgets.filter { it !== mine && it !in ignoring && it.visible }.map { intArrayOf(it.x, it.y, it.width, it.height) }
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

    /**
     * Every two seconds, asks Xaero which chunks around you it has actually mapped, so the ones it
     * has not (the ring at the edge of render distance) stay filled from BlueMap instead of
     * showing black once you zoom out or walk on.
     */
    private fun learnGaps(client: Minecraft, session: Session) {
        val level = client.level ?: return
        val player = client.player ?: return
        val mapProcessor = xaero.map.WorldMapSession.getCurrentSession()?.mapProcessor ?: return
        val dimension = dimensionOf(level)
        // Only while Xaero is mapping the dimension you are in: its open regions are around you.
        val mapped = mapProcessor.mapWorld?.currentDimension?.dimId?.identifier()?.toString() ?: return
        if (mapped != dimension) return
        Backfill.learnGapsAround(
            mapProcessor, session.xaeroGapsIn(dimension),
            player.chunkPosition().x(), player.chunkPosition().z(),
            client.options.effectiveRenderDistance + 2,
        )
    }

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
                "On another dimension's map — arrow: ${ran(MapView.arrowHookRan)}, " +
                    "minimap elements: ${ran(MapView.wrapperHookRan)}, " +
                    "entities: ${ran(MapView.radarHookRan)}, " +
                    "tracked players: ${ran(MapView.trackerHookRan)} (open one to check)"
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

    /**
     * Which map region you are standing in, and where Xaero keeps it. A region is 512 blocks
     * square, so this is just the block position divided by 512, but getting that wrong by hand
     * wastes more time than the command costs.
     */
    private fun whichRegion(context: CommandContext<FabricClientCommandSource>): Int {
        val player = Minecraft.getInstance().player ?: return 0
        val x = Math.floorDiv(player.blockX, 512)
        val z = Math.floorDiv(player.blockZ, 512)
        context.source.sendFeedback(Component.literal("You are in region §b${x}_${z}§r (${x}_${z}.zip)"))
        val dimension = xaero.map.WorldMapSession.getCurrentSession()?.mapProcessor?.mapWorld?.currentDimension
        val folder = dimension?.mainFolderPath?.resolve(dimension.currentMultiworld ?: "")
        context.source.sendFeedback(Component.literal("§7${folder ?: "Xaero has not opened a map folder yet"}"))
        context.source.sendFeedback(Component.literal("§7/mapexposer reloadregion $x $z makes Xaero read it from disk again"))
        return 1
    }

    /**
     * Drops one region from Xaero's memory so that it is read from disk again the next time it is
     * needed, without restarting. `removeMapRegion` only unhooks it from Xaero's maps and does not
     * save on the way out, so a file put there by hand is not overwritten by the copy being
     * dropped. This is the piece a region-sharing feature would stand on.
     */
    private fun reloadRegion(context: CommandContext<FabricClientCommandSource>): Int {
        val x = IntegerArgumentType.getInteger(context, "x")
        val z = IntegerArgumentType.getInteger(context, "z")
        return try {
            val processor = xaero.map.WorldMapSession.getCurrentSession()?.mapProcessor
            if (processor == null) {
                context.source.sendError(Component.literal("Xaero's world map has no session yet; open the map first"))
                return 0
            }
            // Int.MAX_VALUE is Xaero's cave layer for the surface.
            val region = processor.getLeafMapRegion(Int.MAX_VALUE, x, z, false)
            if (region == null) {
                context.source.sendFeedback(
                    Component.literal("Region §b${x}_${z}§r is not in memory, so Xaero will read it from disk when it is next shown")
                )
                return 1
            }
            processor.removeMapRegion(region)
            context.source.sendFeedback(
                Component.literal("Dropped region §b${x}_${z}§r from memory. Pan the map over it to make Xaero read the file again.")
            )
            1
        } catch (e: Throwable) {
            Log.error("Could not drop region ${x}_${z} from memory", e)
            context.source.sendError(Component.literal("Could not drop that region: ${e.message ?: e.javaClass.simpleName}"))
            0
        }
    }

    /** Copies every surface region of the dimension on screen, to retreat to. */
    private fun backup(context: CommandContext<FabricClientCommandSource>): Int {
        val taken = MapBackup.snapshot()
        if (taken == null) {
            context.source.sendError(Component.literal("Could not copy the map; open the world map first"))
            return 0
        }
        val (name, files) = taken
        context.source.sendFeedback(Component.literal("Copied §b$files§r regions as §b$name"))
        context.source.sendFeedback(Component.literal("§7/mapexposer restore $name puts them back"))
        return 1
    }

    private fun backups(context: CommandContext<FabricClientCommandSource>): Int {
        val all = MapBackup.snapshots()
        if (all.isEmpty()) {
            context.source.sendFeedback(Component.literal("No copies of the map yet; /mapexposer backup takes one"))
            return 1
        }
        context.source.sendFeedback(Component.literal("Copies of the map, newest first:"))
        all.take(10).forEach { context.source.sendFeedback(Component.literal("  §b$it")) }
        if (all.size > 10) context.source.sendFeedback(Component.literal("  §7and ${all.size - 10} more"))
        return 1
    }

    /**
     * Puts a copy back. Xaero holds regions open, so each one restored is dropped from its memory
     * afterwards; pan away and back to see the file that is now there.
     */
    private fun restore(context: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(context, "name")
        val restored = MapBackup.restore(name)
        if (restored < 0) {
            context.source.sendError(Component.literal("No copy called '$name'; /mapexposer backups lists them"))
            return 0
        }
        context.source.sendFeedback(Component.literal("Put §b$restored§r regions back from §b$name"))
        context.source.sendFeedback(Component.literal("§7Pan the map over them to see it read the files again"))
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
