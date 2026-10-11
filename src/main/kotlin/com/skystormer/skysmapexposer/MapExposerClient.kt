package com.skystormer.skysmapexposer

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.skystormer.skysmapexposer.gui.MapBar
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
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.nio.file.Files

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

        addToMapScreen()
        ChatShare.register()

        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            Session.start(addressOf(client))
            // Xaero's config is up by now; this is a one-off nudge to one of its own options.
            WaypointScope.matchToMapDimension()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            Session.end()
            BlueMapDownload.forget()
            com.skystormer.skysmapexposer.terrain.TerrainFill.forget()
            ChatShare.forget()
            TerrainFiles.forget()
            SharedBiomes.forget()
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
                if (!injected("xaero.map.gui.GuiMap", "drawBiomeHighlight")) {
                    Log.warn("Could not hook Xaero's map drawing for biome highlights; picking a biome will show nothing")
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
            BlueMapDownload.tick()
            ChatShare.tick()
            TerrainFiles.tick()
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
                    .then(
                        ClientCommands.literal("download")
                            .then(ClientCommands.literal("all").executes { download(it, unexploredOnly = false) })
                            .then(ClientCommands.literal("unexplored").executes { download(it, unexploredOnly = true) })
                            .then(ClientCommands.literal("cancel").executes(::cancelDownload))
                    )
                    .then(
                        ClientCommands.literal("share")
                            .then(ClientCommands.literal("cancel").executes(::cancelShare))
                            .then(
                                ClientCommands.argument("player", StringArgumentType.word())
                                    .suggests { _, builder -> ChatShare.others().forEach(builder::suggest); builder.buildFuture() }
                                    .then(ClientCommands.argument("x1", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("z1", IntegerArgumentType.integer())
                                            .then(ClientCommands.argument("x2", IntegerArgumentType.integer())
                                                .then(ClientCommands.argument("z2", IntegerArgumentType.integer())
                                                    .executes(::share)))))
                                    .then(ClientCommands.literal("waypoints").executes(::shareWaypoints))
                            )
                    )
                    .then(
                        ClientCommands.literal("shared").then(
                            ClientCommands.argument("id", IntegerArgumentType.integer(0))
                                .then(ClientCommands.literal("blank").executes { addShared(it, unexploredOnly = true) })
                                .then(ClientCommands.literal("all").executes { addShared(it, unexploredOnly = false) })
                                .then(ClientCommands.literal("biomes").executes {
                                    it.source.sendFeedback(Component.literal(ChatShare.addBiomes(IntegerArgumentType.getInteger(it, "id"))))
                                    1
                                })
                                .then(ClientCommands.literal("waypoints").executes {
                                    it.source.sendFeedback(Component.literal(ChatShare.addWaypoints(IntegerArgumentType.getInteger(it, "id"))))
                                    1
                                })
                        )
                    )
                    .then(
                        ClientCommands.literal("terrain")
                            .then(ClientCommands.literal("save")
                                .then(ClientCommands.argument("x1", IntegerArgumentType.integer())
                                    .then(ClientCommands.argument("z1", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("x2", IntegerArgumentType.integer())
                                            .then(ClientCommands.argument("z2", IntegerArgumentType.integer()).executes(::saveTerrain))))))
                            .then(ClientCommands.literal("open")
                                .then(ClientCommands.argument("file", StringArgumentType.greedyString())
                                    .suggests { _, builder -> TerrainFiles.list().forEach(builder::suggest); builder.buildFuture() }
                                    .executes(::openTerrain)))
                            .then(ClientCommands.literal("cancel").executes {
                                it.source.sendFeedback(Component.literal(if (TerrainFiles.cancel()) "Stopped saving the map file" else "No map file is being saved"))
                                1
                            })
                    )
                    .then(
                        ClientCommands.literal("clear")
                            .then(clearCommand(MapClear.Kind.OLD_SEASON))
                            .then(clearCommand(MapClear.Kind.BORDER))
                    )
                    .then(
                        ClientCommands.literal("season")
                            .executes { it.source.sendFeedback(Component.literal(MapClear.seasons())); 1 }
                            .then(ClientCommands.literal("set").then(
                                ClientCommands.argument("time", StringArgumentType.greedyString()).executes {
                                    it.source.sendFeedback(Component.literal(MapClear.setSeason(StringArgumentType.getString(it, "time"))))
                                    1
                                }
                            ))
                            .then(ClientCommands.literal("remove").executes {
                                it.source.sendFeedback(Component.literal(MapClear.setSeason(null)))
                                1
                            })
                    )
                    .then(ClientCommands.literal("backup").executes(::backup))
                    .then(ClientCommands.literal("backups").executes(::backups))
                    .then(
                        ClientCommands.literal("restore")
                            .executes { restore(it, MapBackup.names().firstOrNull()) }
                            .then(
                                ClientCommands.argument("name", StringArgumentType.string())
                                    .suggests { _, builder ->
                                        MapBackup.names().filter { it.startsWith(builder.remaining) }.forEach(builder::suggest)
                                        builder.buildFuture()
                                    }
                                    .executes { restore(it, StringArgumentType.getString(it, "name")) }
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
     * This mod's bar ([MapBar]) on Xaero's world map, and a line at the top of the map saying how a
     * download is going.
     */
    private fun addToMapScreen() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen.javaClass.name != "xaero.map.gui.GuiMap") return@register
            try {
                MapBar.addTo(screen)

                ScreenEvents.afterExtract(screen).register { _, graphics, _, _, _ ->
                    val status = BlueMapDownload.status() ?: return@register
                    val font = Screens.getMinecraft(screen).font
                    val half = font.width(status) / 2 + 4
                    val middle = screen.width / 2
                    graphics.fill(middle - half, 2, middle + half, 14, 0xA0000000.toInt())
                    graphics.centeredText(font, status, middle, 4, 0xFFFFFFFF.toInt())
                }
            } catch (e: Throwable) {
                Log.error("Could not add the bar to Xaero's world map", e)
            }
        }
    }

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
     * Downloads everything BlueMap has into the dimension the world map is showing; with
     * [unexploredOnly], only into chunks you have never mapped.
     */
    private fun download(context: CommandContext<FabricClientCommandSource>, unexploredOnly: Boolean): Int {
        context.source.sendFeedback(Component.literal(BlueMapDownload.start(null, unexploredOnly)))
        return 1
    }

    /** The chunks between two block positions of a command, inclusive: left, top, right, bottom. */
    private fun chunks(context: CommandContext<FabricClientCommandSource>): IntArray {
        fun chunk(name: String) = Math.floorDiv(IntegerArgumentType.getInteger(context, name), 16)
        val (x1, x2) = chunk("x1") to chunk("x2")
        val (z1, z2) = chunk("z1") to chunk("z2")
        return intArrayOf(minOf(x1, x2), minOf(z1, z2), maxOf(x1, x2), maxOf(z1, z2))
    }

    /** The biomes of the chunks between two block positions, inclusive, of the dimension the world map is showing, to one player. */
    private fun share(context: CommandContext<FabricClientCommandSource>): Int {
        val (left, top, right, bottom) = chunks(context)
        val to = StringArgumentType.getString(context, "player")
        context.source.sendFeedback(Component.literal(ChatShare.shareBiomes(to, left, top, right, bottom)))
        return 1
    }

    /** Every waypoint of the dimension you are in, to one player; the map's menu picks some. */
    private fun shareWaypoints(context: CommandContext<FabricClientCommandSource>): Int {
        val to = StringArgumentType.getString(context, "player")
        val mine = if (Waypoints.available()) WaypointShare.mine() else null
        val said = if (mine == null) "Sharing waypoints needs Xaero's Minimap, with a waypoint world open"
        else ChatShare.shareWaypoints(to, mine.first, mine.second.map { it.shared() })
        context.source.sendFeedback(Component.literal(said))
        return 1
    }

    /** The terrain of the chunks between two block positions, inclusive, to a file in the shared maps folder. */
    private fun saveTerrain(context: CommandContext<FabricClientCommandSource>): Int {
        val (left, top, right, bottom) = chunks(context)
        context.source.sendFeedback(Component.literal(TerrainFiles.save(left, top, right, bottom)))
        return 1
    }

    /** A map file from the shared maps folder, as if dropped onto the game. */
    private fun openTerrain(context: CommandContext<FabricClientCommandSource>): Int {
        val name = StringArgumentType.getString(context, "file")
        val path = TerrainFiles.folder.resolve(name).normalize()
        if (!path.startsWith(TerrainFiles.folder) || !Files.isRegularFile(path)) {
            context.source.sendError(Component.literal("No map file called $name in the shared maps folder"))
            return 0
        }
        TerrainFiles.open(path)
        return 1
    }

    private fun cancelShare(context: CommandContext<FabricClientCommandSource>): Int {
        context.source.sendFeedback(Component.literal(if (ChatShare.cancel()) "Stopped sharing your map" else "No map is being shared"))
        return 1
    }

    /** The buttons under a map file opened. */
    private fun addShared(context: CommandContext<FabricClientCommandSource>, unexploredOnly: Boolean): Int {
        context.source.sendFeedback(Component.literal(TerrainFiles.add(IntegerArgumentType.getInteger(context, "id"), unexploredOnly)))
        return 1
    }

    private fun cancelDownload(context: CommandContext<FabricClientCommandSource>): Int {
        if (BlueMapDownload.cancel()) {
            context.source.sendFeedback(Component.literal("Stopping the download after the region it is on"))
        } else {
            context.source.sendFeedback(Component.literal("No download from BlueMap is running"))
        }
        return 1
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
     * Puts a copy back. Xaero holds regions open, so each one restored is marked for Xaero to read
     * the file now there when it is next on screen.
     */
    private fun restore(context: CommandContext<FabricClientCommandSource>, name: String?): Int {
        if (name == null) {
            context.source.sendError(Component.literal("There are no copies to put back"))
            return 0
        }
        val restored = MapBackup.restore(name)
        if (restored < 0) {
            context.source.sendError(Component.literal("No copy called '$name'; /mapexposer backups lists them"))
            return 0
        }
        context.source.sendFeedback(Component.literal("Put §b$restored§r regions back from §b$name"))
        if (MapBackup.nearYou > 0) {
            context.source.sendFeedback(Component.literal(
                "§7${MapBackup.nearYou} of them are right around you, where Xaero is mapping what you see; " +
                    "they show as put back once you move a few regions away, or after a restart"
            ))
        }
        context.source.sendFeedback(Component.literal("§7Zoom in over them to have Xaero read them again"))
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

    /** `clear <kind>` says what would go; `clear <kind> confirm` takes it off the map. */
    private fun clearCommand(kind: MapClear.Kind) =
        ClientCommands.literal(kind.word)
            .executes { context ->
                MapClear.preview(kind)?.let { context.source.sendFeedback(Component.literal(it)) }
                1
            }
            .then(ClientCommands.literal("confirm").executes { context ->
                context.source.sendFeedback(Component.literal(MapClear.confirm(kind)))
                1
            })
            .also { if (kind == MapClear.Kind.BORDER) it.then(borderBox()) }

    /** `clear border <radius> [<centerX> <centerZ>] [confirm]`: a border given by hand. */
    private fun borderBox() = ClientCommands.argument("radius", IntegerArgumentType.integer(1))
        .executes { clearBox(it, false, false) }
        .then(ClientCommands.literal("confirm").executes { clearBox(it, false, true) })
        .then(ClientCommands.argument("centerX", IntegerArgumentType.integer())
            .then(ClientCommands.argument("centerZ", IntegerArgumentType.integer())
                .executes { clearBox(it, true, false) }
                .then(ClientCommands.literal("confirm").executes { clearBox(it, true, true) })))

    private fun clearBox(context: CommandContext<FabricClientCommandSource>, centred: Boolean, confirm: Boolean): Int {
        val box = MapClear.Box(
            IntegerArgumentType.getInteger(context, "radius"),
            if (centred) IntegerArgumentType.getInteger(context, "centerX") else 0,
            if (centred) IntegerArgumentType.getInteger(context, "centerZ") else 0,
        )
        val said = if (confirm) MapClear.confirm(MapClear.Kind.BORDER, box) else MapClear.preview(MapClear.Kind.BORDER, box)
        said?.let { context.source.sendFeedback(Component.literal(it)) }
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
