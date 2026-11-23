package com.skystormer.skysmapexposer.xaero

import com.skystormer.skysmapexposer.levelKey
import com.skystormer.skysmapexposer.MapBorder
import com.skystormer.skysmapexposer.ShareableWaypoint
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.skystormer.skysmapexposer.CaveNotice
import com.skystormer.skysmapexposer.ChatButton
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapAdapter
import com.skystormer.skysmapexposer.MapExposerClient
import com.skystormer.skysmapexposer.MapLayers
import com.skystormer.skysmapexposer.MapMods
import com.skystormer.skysmapexposer.MapPrep
import com.skystormer.skysmapexposer.Session
import com.skystormer.skysmapexposer.SharedTerrain
import com.skystormer.skysmapexposer.ShareFormat
import com.skystormer.skysmapexposer.SkippedRegions
import com.skystormer.skysmapexposer.Ui
import com.skystormer.skysmapexposer.gui.MapBar
import java.util.UUID
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.resources.Identifier
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import xaero.map.WorldMapSession

/**
 * Xaero's World Map, and its Minimap when installed, for the rest of the mod ([MapAdapter]). Only
 * reached through [MapMods.adapter], so its classes load only with Xaero's World Map installed.
 */
object XaeroAdapter : MapAdapter {

    private var ticks = 0L

    /** Where the download and preparation line goes on the world map: below Xaero's three lines, which are 10 apart from 2. */
    private const val STATUS_TOP = 34

    private var worldMapPinsAdded = false

    private var minimapPinsAdded = false

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
     * This mod's bar ([MapBar]) on Xaero's world map, and a line at the top of the map saying how a
     * download is going.
     */
    private fun addToMapScreen() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen.javaClass.name != "xaero.map.gui.GuiMap") return@register
            try {
                com.skystormer.skysmapexposer.gui.MapBar.addTo(screen)

                ScreenEvents.afterExtract(screen).register { _, graphics, _, _, _ ->
                    val status = BlueMapDownload.status() ?: TerrainFiles.status() ?: MapPrep.status() ?: CaveNotice.line() ?: return@register
                    val font = Screens.getMinecraft(screen).font
                    val half = font.width(status) / 2 + 4
                    val middle = screen.width / 2
                    // Under Xaero's own lines at the top (the block under the mouse, its biome, and
                    // the other dimension's coordinates), never over them.
                    val top = STATUS_TOP
                    graphics.fill(middle - half, top, middle + half, top + 12, 0xA0000000.toInt())
                    graphics.centeredText(font, status, middle, top + 2, 0xFFFFFFFF.toInt())
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
            minimapPinsAdded = if (!MapMods.xaeroMinimap) {
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
     * Every two seconds, asks Xaero which chunks around you it has actually mapped, so the ones it
     * has not (the ring at the edge of render distance) stay filled from BlueMap instead of
     * showing black once you zoom out or walk on.
     */
    private fun learnGaps(client: Minecraft, session: Session) {
        val level = client.level ?: return
        val player = client.player ?: return
        val mapProcessor = xaero.map.WorldMapSession.getCurrentSession()?.mapProcessor ?: return
        val dimension = level.dimension().identifier().toString()
        // Only while Xaero is mapping the dimension you are in: its open regions are around you.
        val mapped = mapProcessor.mapWorld?.currentDimension?.dimId?.identifier()?.toString() ?: return
        if (mapped != dimension) return
        // Only from the layer the overlay draws over, so cave gaps never count as surface gaps.
        XaeroWriter.blueMapLayer(dimension, session.server.mapFor(dimension), mapProcessor.currentCaveLayer) ?: return
        Backfill.learnGapsAround(
            mapProcessor, session.xaeroGapsIn(dimension),
            player.chunkPosition().x(), player.chunkPosition().z(),
            client.options.effectiveRenderDistance + 2,
        )
    }

    /**
     * Downloads everything BlueMap has into the dimension the world map is showing; with
     * [unexploredOnly], only into chunks you have never mapped.
     */
    private fun download(context: CommandContext<FabricClientCommandSource>, unexploredOnly: Boolean): Int {
        return Ui.reply(context, BlueMapDownload.start(null, unexploredOnly))
    }

    /** The chunks between two block positions of a command, inclusive: left, top, right, bottom. */
    private fun chunks(context: CommandContext<FabricClientCommandSource>): IntArray {
        fun chunk(name: String) = Math.floorDiv(IntegerArgumentType.getInteger(context, name), 16)
        val (x1, x2) = chunk("x1") to chunk("x2")
        val (z1, z2) = chunk("z1") to chunk("z2")
        return intArrayOf(minOf(x1, x2), minOf(z1, z2), maxOf(x1, x2), maxOf(z1, z2))
    }

    /**
     * The terrain of the chunks between two block positions, inclusive, or of the whole map
     * ([whole]), to a file in the shared maps folder. A big one asks first ([SharedTerrain.reply]).
     */
    private fun saveTerrain(context: CommandContext<FabricClientCommandSource>, whole: Boolean, confirmed: Boolean): Int {
        val area = if (whole) null else chunks(context).let { (left, top, right, bottom) -> ShareFormat.Area(left, top, right, bottom) }
        return SharedTerrain.reply(context, TerrainFiles.save(area, confirmed))
    }

    override fun addSharedTerrain(file: SharedTerrain.Opened, id: Int, unexploredOnly: Boolean, confirmed: Boolean): String? =
        TerrainFiles.add(file, id, unexploredOnly, confirmed)

    private fun cancelDownloadCommand(context: CommandContext<FabricClientCommandSource>): Int {
        if (BlueMapDownload.cancel()) {
            context.source.sendFeedback(Component.literal("Stopping the download after the region it is on"))
        } else {
            context.source.sendFeedback(Component.literal("No download from BlueMap is running"))
        }
        return 1
    }

    /** Copies every region of the layer on screen, to retreat to. */
    private fun backup(context: CommandContext<FabricClientCommandSource>): Int {
        val now = MapBackup.snapshot { done ->
            Minecraft.getInstance().player?.sendSystemMessage(Component.literal(done))
        }
        if (now.isNotEmpty()) Ui.reply(context, now)
        return 1
    }

    /** Every region the last download, clear or import skipped, and why. */
    private fun skipped(context: CommandContext<FabricClientCommandSource>): Int {
        val all = BlueMapDownload.skippedList()
        if (all.isEmpty()) context.source.sendFeedback(Component.literal("Nothing was skipped"))
        else {
            context.source.sendFeedback(Component.literal("${all.size} regions skipped (region x,z, then blocks):"))
            all.forEach { context.source.sendFeedback(Component.literal("  $it")) }
            context.source.sendFeedback(Component.literal("§7${SkippedRegions.ADVICE}"))
        }
        return 1
    }

    private fun backups(context: CommandContext<FabricClientCommandSource>): Int {
        val all = MapBackup.names()
        if (all.isEmpty()) {
            return Ui.reply(context, "No copies of the map yet; /mapexposer backup takes one")
        }
        context.source.sendFeedback(Component.literal("Copies of the map, newest first:"))
        all.take(10).forEach { context.source.sendFeedback(Component.literal("  §b$it")) }
        if (all.size > 10) Ui.reply(context, "  §7and ${all.size - 10} more")
        return 1
    }

    /**
     * Says what putting copy [name] back would do, with a button; [confirmed], does it. [plain] is
     * `/mapexposer restore` with no name, which never takes a copy that does not say where it came
     * from. Xaero holds regions open, so each one restored is marked for Xaero to read the file now
     * there when it is next on screen.
     */
    private fun restore(context: CommandContext<FabricClientCommandSource>, name: String?, plain: Boolean, confirmed: Boolean): Int {
        if (name == null) {
            context.source.sendError(Component.literal("There are no copies to put back"))
            return 0
        }
        val root = MapBackup.backupRoot()
        if (root == null) {
            context.source.sendError(Component.literal("This server has no folder for copies"))
            return 0
        }
        val (preview, why) = MapBackup.preview(root, name, plain, MapBackup.server(), MapBackup.shownTarget()) { Ui.dimensionName(it) ?: it }
        if (preview == null) {
            context.source.sendError(Component.literal(why!!))
            return 0
        }
        val target = preview.target
        val where = (if (target.layer == MapLayers.SURFACE) "" else "cave layer ${target.layer} of ") + "the ${Ui.dimensionName(target.dimensionId) ?: target.dimensionId}"
        val count = preview.regions.size
        if (!confirmed) {
            val notes = buildString {
                if (preview.origin == null) append(" §eThis copy is from before 1.9.2 and does not say which map it came from; it goes into the map on screen.§r")
                val unsaved = preview.origin?.unsaved?.size ?: 0
                if (unsaved > 0) append(" §7$unsaved of its regions were copied without Xaero's newest exploring, which it did not save in time.§r")
            }
            context.source.sendFeedback(
                Component.literal("Restore $count region${if (count == 1) "" else "s"} of $where from ${preview.created}? " +
                    "Your current map of those regions is copied aside first.$notes  ")
                    .append(ChatButton.of("[Restore]", "/mapexposer restore \"$name\" confirm", "Puts $name back now."))
            )
            return 1
        }
        val (restored, failed) = MapBackup.restore(preview)
        if (restored == null) {
            context.source.sendError(Component.literal(failed!!))
            return 0
        }
        context.source.sendFeedback(
            Component.literal("Put §b${restored.regions.size}§r regions of $where back from §b$name§r.  ")
                .append(ChatButton.of("[Undo restore]", "/mapexposer restore \"${restored.before}\" confirm", "Puts back your map as it was just before: ${restored.before}"))
        )
        if (MapBackup.nearYou > 0) {
            context.source.sendFeedback(Component.literal(
                "§7${MapBackup.nearYou} of them are right around you, where Xaero is mapping what you see; " +
                    "they show as put back once you move a few regions away, or after a restart"
            ))
        }
        return Ui.reply(context, "§7Zoom in over them to have Xaero read them again")
    }

    /** `clear <kind>` says what would go; `clear <kind> confirm` takes it off the map. */
    private fun clearCommand(kind: MapClear.Kind) =
        ClientCommands.literal(kind.word)
            .executes { context ->
                MapClear.preview(kind)?.let { context.source.sendFeedback(Component.literal(it)) }
                1
            }
            .then(confirmClear { context, previewed ->
                Ui.reply(context, MapClear.confirm(kind, previewed = previewed))
            })
            .also { if (kind == MapClear.Kind.BORDER) it.then(borderBox()) }

    /**
     * `confirm`, typed; or `confirm "<dimension>" <layer>`, as the preview's button sends it, which
     * refuses if the world map has moved on to another map since.
     */
    private fun confirmClear(run: (CommandContext<FabricClientCommandSource>, Pair<String, Int>?) -> Int) =
        ClientCommands.literal("confirm").executes { run(it, null) }
            .then(ClientCommands.argument("dimension", StringArgumentType.string())
                .then(ClientCommands.argument("layer", IntegerArgumentType.integer()).executes {
                    run(it, StringArgumentType.getString(it, "dimension") to IntegerArgumentType.getInteger(it, "layer"))
                }))

    /** `clear border <radius> [<centerX> <centerZ>] [confirm]`: a border given by hand. */
    private fun borderBox() = ClientCommands.argument("radius", IntegerArgumentType.integer(1))
        .executes { clearBox(it, false, false, null) }
        .then(confirmClear { context, previewed -> clearBox(context, false, true, previewed) })
        .then(ClientCommands.argument("centerX", IntegerArgumentType.integer())
            .then(ClientCommands.argument("centerZ", IntegerArgumentType.integer())
                .executes { clearBox(it, true, false, null) }
                .then(confirmClear { context, previewed -> clearBox(context, true, true, previewed) })))

    private fun clearBox(context: CommandContext<FabricClientCommandSource>, centred: Boolean, confirm: Boolean, previewed: Pair<String, Int>?): Int {
        val box = MapBorder.Box(
            IntegerArgumentType.getInteger(context, "radius"),
            if (centred) IntegerArgumentType.getInteger(context, "centerX") else 0,
            if (centred) IntegerArgumentType.getInteger(context, "centerZ") else 0,
        )
        val said = if (confirm) MapClear.confirm(MapClear.Kind.BORDER, box, previewed) else MapClear.preview(MapClear.Kind.BORDER, box)
        said?.let { context.source.sendFeedback(Component.literal(it)) }
        return 1
    }

    override fun init() {
        addToMapScreen()
        // Every frame, even under a screen: saving a terrain file reads a thin slice of your map.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("skysmapexposer", "terrain_save"), object : HudElement {
            override fun extractRenderState(graphics: GuiGraphicsExtractor, delta: DeltaTracker) = TerrainFiles.frame()
        })
    }

    override fun checkHooks() {
        if (!hookInstalled()) Log.warn("This version of Xaero's World Map is not supported; nothing will be drawn on it")
        if (MapMods.xaeroMinimap && !minimapHookInstalled()) {
            Log.warn("This version of Xaero's Minimap is not supported; nothing will be drawn on it")
        }
        if (!MapExposerClient.injected("xaero.map.gui.GuiMap", "drawBiomeHighlight")) {
            Log.warn("Could not hook Xaero's map drawing for biome highlights; picking a biome will show nothing")
        }
        if (!arrowHooked()) Log.warn("Could not hook Xaero's player arrow; it will show on another dimension's map")
        if (!elementsHooked()) Log.warn("Could not hook the minimap's elements on Xaero's map; they will show on another dimension's map")
        if (MapMods.xaeroMinimap) {
            // The drawing gate is the one that has to hold; the other is only a shortcut.
            if (!radarHooked()) {
                Log.warn("Could not hook Xaero's entity radar; entities will show on another dimension's map")
            } else if (!MapExposerClient.injected(RADAR, "hideRadarInOtherDimension")) {
                Log.warn("Only half of the entity radar hook applied; entities are still hidden, less cheaply")
            }
            if (!trackerHooked()) Log.warn("Could not hook Xaero's tracked players; they will show on another dimension's map")
        }
    }

    private fun arrowHooked() = MapExposerClient.injected("xaero.map.gui.GuiMap", "hideArrowInOtherDimension")
    private fun elementsHooked() = MapExposerClient.injected("xaero.map.mods.minimap.element.MinimapElementRendererWrapper", "hideMinimapElementsInOtherDimension")
    private fun radarHooked() = MapExposerClient.injected(RADAR, "hideRadarDot")
    private fun trackerHooked() = MapExposerClient.injected("xaero.hud.minimap.player.tracker.PlayerTrackerMinimapElementRenderer", "hideTrackerDot")
    private const val RADAR = "xaero.hud.minimap.radar.render.element.RadarRenderer"

    // Xaero's config is up by now; this is a one-off nudge to one of its own options.
    override fun join() = WaypointScope.matchToMapDimension()

    override fun leave() {
        BlueMapDownload.forget()
        TerrainFiles.forget()
        MapView.forget()
        MapBackup.leave()
    }

    override fun tick(client: Minecraft, session: Session?) {
        addPins()
        MapView.tick(client)
        XaeroMenus.tick(client)
        BlueMapDownload.tick()
        MapBackup.tick()
        TerrainFiles.tick()
        if (session != null && ++ticks % 40 == 0L) learnGaps(client, session)
    }

    override fun commands(root: LiteralArgumentBuilder<FabricClientCommandSource>) {
        root
            .then(
                ClientCommands.literal("download")
                    .then(ClientCommands.literal("all").executes { download(it, unexploredOnly = false) })
                    .then(ClientCommands.literal("unexplored").executes { download(it, unexploredOnly = true) })
                    .then(ClientCommands.literal("cancel").executes(::cancelDownloadCommand))
            )
            .then(
                ClientCommands.literal("terrain")
                    .then(ClientCommands.literal("save")
                        .then(ClientCommands.argument("x1", IntegerArgumentType.integer())
                            .then(ClientCommands.argument("z1", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("x2", IntegerArgumentType.integer())
                                    .then(ClientCommands.argument("z2", IntegerArgumentType.integer())
                                        .executes { saveTerrain(it, whole = false, confirmed = false) }
                                        .then(ClientCommands.literal("confirm").executes { saveTerrain(it, whole = false, confirmed = true) })))))
                        .then(ClientCommands.literal("all")
                            .executes { saveTerrain(it, whole = true, confirmed = false) }
                            .then(ClientCommands.literal("confirm").executes { saveTerrain(it, whole = true, confirmed = true) })))
                    .then(ClientCommands.literal("cancel").executes {
                        Ui.reply(it, if (TerrainFiles.cancel()) "Stopped saving the map file" else "No map file is being saved")
                    })
            )
            .then(
                ClientCommands.literal("clear")
                    .then(clearCommand(MapClear.Kind.OLD_SEASON))
                    .then(clearCommand(MapClear.Kind.BORDER))
            )
            .then(ClientCommands.literal("backup").executes(::backup))
            .then(ClientCommands.literal("backups").executes(::backups))
            .then(
                ClientCommands.literal("restore")
                    .executes { restore(it, MapBackup.names().firstOrNull(), plain = true, confirmed = false) }
                    .then(
                        ClientCommands.argument("name", StringArgumentType.string())
                            .suggests { _, builder ->
                                MapBackup.names().filter { it.startsWith(builder.remaining) }.forEach(builder::suggest)
                                builder.buildFuture()
                            }
                            .executes { restore(it, StringArgumentType.getString(it, "name"), plain = false, confirmed = false) }
                            .then(ClientCommands.literal("confirm").executes {
                                restore(it, StringArgumentType.getString(it, "name"), plain = false, confirmed = true)
                            })
                    )
            )
            .then(ClientCommands.literal("skipped").executes(::skipped))
            .then(ClientCommands.literal("debug").then(ClientCommands.literal("regions").executes {
                Overlay.debugRegions = true
                Ui.reply(it, "Open the world map where it shows black; the next frame writes what Xaero has of each region there to the log")
            }))
    }

    override fun status(say: (String) -> Unit) {
        say("Xaero hook: " + if (hookInstalled()) "§aok" else "§cmissing: this Xaero's World Map version is not supported")
        fun mark(ok: Boolean) = if (ok) "§aok§r" else "§cmissing§r"
        say(
            "Hidden on another dimension's map — arrow: ${mark(arrowHooked())}, minimap elements: ${mark(elementsHooked())}" +
                if (MapMods.xaeroMinimap) ", entities: ${mark(radarHooked())}, tracked players: ${mark(trackerHooked())}" else ""
        )
        val session = Session.current ?: return
        say("Xaero region files: ${session.regionAges.count()}")
        val unshowable = session.unshowable.count
        if (unshowable > 0) say("§e$unshowable region(s) Xaero has a file for but can't show are covered by BlueMap§r (the log names them)")
    }

    override fun shownDimension(): String? = MarkerElements.worldMapDimension()

    override fun unknownBiomes(dimension: String?) = BiomeHighlight.unknownIn(dimension)

    override val tintsMinimap: Boolean get() = MapMods.xaeroMinimap

    override val unknownBiomesVersion: Int get() = BiomeHighlight.unknownVersion

    override fun cavesShown(session: Session): String? {
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return null
        val dimension = processor.mapWorld?.currentDimension?.dimId?.identifier()?.toString() ?: return null
        // Only caves the overlay does not draw over: the Nether's usual cave layer is drawn over.
        return dimension.takeIf { XaeroWriter.blueMapLayer(it, session.server.mapFor(it), processor.currentCaveLayer) == null }
    }

    override fun goTo(dimension: String, x: Int, z: Int, from: Screen?, player: UUID?): Boolean =
        MapView.goTo(dimension, x, z, from, player)

    override fun fullMapOpen(screen: Screen?): Boolean = screen?.javaClass?.name == "xaero.map.gui.GuiMap"

    /**
     * Sets [dimension]'s cave mode type to Off, as the cave mode button on Xaero's world map does:
     * Xaero then shows the surface even when its cave mode comes on, until that button turns it back.
     */
    override fun showSurface(dimension: String): String {
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return "Xaero's World Map has not started for this world yet"
        val name = Ui.dimensionName(dimension) ?: dimension
        val mapDimension = try {
            processor.mapWorld?.getDimension(levelKey(dimension))
        } catch (e: Exception) {
            null
        } ?: return "Xaero's World Map has no map of the $name yet"
        if (mapDimension.caveModeType == 0) return "Cave mode is already off on Xaero's world map of the $name"
        repeat(3) { if (mapDimension.caveModeType != 0) mapDimension.toggleCaveModeType(true) }
        synchronized(processor.uiSync) { mapDimension.saveConfigUnsynced() }
        return "Cave mode is off on Xaero's world map of the $name, so BlueMap's terrain shows; the cave button on the map turns it back on"
    }

    override fun summary(): String = Overlay.lastSummary

    override fun myWaypoints(dimension: String?): Pair<String, List<ShareableWaypoint>>? =
        if (!Waypoints.available()) null else WaypointShare.mine(dimension)

    override fun addWaypoints(dimension: String, from: String, waypoints: List<ShareFormat.SharedWaypoint>): String =
        WaypointShare.add(dimension, from, waypoints)

    override fun downloadStatus(): String? =
        if (BlueMapDownload.running) BlueMapDownload.status() ?: "A download from BlueMap is running." else null

    override fun cancelDownload(): Boolean = BlueMapDownload.cancel()

    override val canDownload: Boolean get() = true

    override fun download(unexploredOnly: Boolean): String = BlueMapDownload.start(null, unexploredOnly)
}
