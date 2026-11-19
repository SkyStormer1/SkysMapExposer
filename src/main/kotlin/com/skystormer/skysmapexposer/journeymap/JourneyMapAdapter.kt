package com.skystormer.skysmapexposer.journeymap

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.skystormer.skysmapexposer.BiomePicks
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapAdapter
import com.skystormer.skysmapexposer.MapBorder
import com.skystormer.skysmapexposer.MapExposerClient
import com.skystormer.skysmapexposer.MapLayers
import com.skystormer.skysmapexposer.SeaFloor
import com.skystormer.skysmapexposer.Session
import com.skystormer.skysmapexposer.ShareFormat
import com.skystormer.skysmapexposer.SharedTerrain
import com.skystormer.skysmapexposer.ShareableWaypoint
import com.skystormer.skysmapexposer.Ui
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.client.util.UIState
import journeymap.api.v2.common.Context
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.util.UUID

/**
 * JourneyMap for the rest of the mod ([MapAdapter]), through its API. Only reached through
 * [com.skystormer.skysmapexposer.MapMods.adapter], so its classes load only with JourneyMap
 * installed. It keeps nothing but the API handle: everything it shows on JourneyMap is removed on
 * leaving a world.
 */
object JourneyMapAdapter : MapAdapter {

    const val MOD_ID = "skysmapexposer"

    /** Set by [MapExposerPlugin] once JourneyMap has started it; null until then. */
    @Volatile
    var api: IClientAPI? = null

    private var missingSaid = false

    /** When you joined, until this mod's leftover temporary waypoints have been tidied away; 0 once they have. */
    private var tidyAfter = 0L

    override fun init() = JourneyMapMenus.register()

    /**
     * This mod's two hooks into JourneyMap and the internals it calls ([missingInternals]), checked
     * once at start as Xaero's hooks are: whatever is missing is said in the log and in `/mapexposer`.
     */
    override fun checkHooks() {
        if (!dimensionHooked()) Log.warn("Could not hook JourneyMap's full-screen map; its dimension button will snap back to yours")
        if (!minimapHooked()) Log.warn("Could not hook JourneyMap's minimap; BlueMap's terrain and the biome tint will not show on it")
        missingInternals.forEach { Log.warn("This JourneyMap has no {}; {}", it.first, it.second) }
    }

    /**
     * JourneyMap's own classes and methods this mod calls beyond its API, each with what goes without
     * it; those this JourneyMap lacks. Looked up once: a JourneyMap update that renames one costs
     * that part, says so, and never crashes the game.
     */
    val missingInternals: List<Pair<String, String>> by lazy {
        listOf(
            Triple("journeymap.client.ui.fullscreen.Fullscreen", "getBlockAtMouse", "the block under the mouse (right-click biome entry, other dimension's coordinates)"),
            Triple("journeymap.client.ui.fullscreen.Fullscreen", "getZoom", "BlueMap's terrain over all of the full-screen map at its zoom"),
            Triple("journeymap.client.ui.UIManager", "getOrOpenFullscreenMap", "Go to from the player list"),
            Triple("journeymap.client.io.FileHandler", "getJMWorldDir", "downloads, clearing, imports, saving and the biome tint (its folders)"),
            Triple("journeymap.client.model.region.RegionImageCache", "getRegionImageSets", "downloads, clearing and imports (which regions it holds)"),
            Triple("journeymap.client.model.block.BlockMD", "get", "shared terrain added in its colours"),
            Triple("journeymap.client.render.JmRenderRouter", "addGuiElement", "BlueMap's terrain and the biome tint on the minimap"),
            Triple("journeymap.client.render.map.Renderer", "markOverlaySurfaceDirty", "the minimap redrawing when this mod's pictures change"),
        ).filter { (type, method, _) ->
            try {
                Class.forName(type).methods.none { it.name == method }
            } catch (e: Throwable) {
                true
            }
        }.map { (type, method, what) -> "${type.substringAfterLast('.')}.$method" to "$what will not work" }
    }

    private fun dimensionHooked() = MapExposerClient.injected("journeymap.client.model.map.MapState", "keepDimension")

    private fun minimapHooked() = MapExposerClient.injected("journeymap.client.render.map.PolygonGeometryDrawPass", "drawTerrain")

    override val tintsMinimap: Boolean get() = true

    // JourneyMap starts its plugins with itself, so by the time a world opens it has, or never will.
    override fun join() {
        tidyAfter = System.currentTimeMillis() + TIDY_DELAY_MS
        if (api == null && !missingSaid) {
            missingSaid = true
            Log.warn("JourneyMap has not started this mod's plugin; nothing will be drawn on it")
        }
    }

    override fun leave() {
        JourneyMapEdits.forget()
        api?.let(JourneyMapWaypoints::forget)
        api?.removeAll(MOD_ID)
        JourneyMapPins.forget()
        JourneyMapTerrain.forget()
        JourneyMapBiomes.forget()
        JourneyMapMinimap.forget()
        JourneyMapMenus.forget()
    }

    override fun tick(client: Minecraft, session: Session?) {
        val api = api ?: return
        if (client.level == null) return
        if (tidyAfter != 0L && System.currentTimeMillis() >= tidyAfter) {
            tidyAfter = 0L
            JourneyMapWaypoints.tidy(api)
        }
        JourneyMapMenus.tick(client)
        JourneyMapEdits.tick()
        JourneyMapPins.tick(api, shownDimension())
        JourneyMapTerrain.tick(api, session)
        JourneyMapBiomes.tick(api)
    }

    override fun commands(root: LiteralArgumentBuilder<FabricClientCommandSource>) {
        root.then(
            ClientCommands.literal("download")
                .then(ClientCommands.literal("all").executes { Ui.reply(it, JourneyMapEdits.download(unexploredOnly = false)) })
                .then(ClientCommands.literal("unexplored").executes { Ui.reply(it, JourneyMapEdits.download(unexploredOnly = true)) })
                .then(ClientCommands.literal("region").then(
                    ClientCommands.argument("x", IntegerArgumentType.integer()).then(
                        ClientCommands.argument("z", IntegerArgumentType.integer())
                            .executes { Ui.reply(it, JourneyMapEdits.download(true, regionOf(it))) }
                            .then(ClientCommands.literal("all").executes { Ui.reply(it, JourneyMapEdits.download(false, regionOf(it))) })
                    )
                ))
                // The chunks between two block positions: the area picked on the map ([JourneyMapSelection]).
                .then(ClientCommands.literal("area").then(ClientCommands.argument("x1", IntegerArgumentType.integer())
                    .then(ClientCommands.argument("z1", IntegerArgumentType.integer())
                        .then(ClientCommands.argument("x2", IntegerArgumentType.integer())
                            .then(ClientCommands.argument("z2", IntegerArgumentType.integer())
                                .executes { Ui.reply(it, JourneyMapEdits.download(true, areaOf(it))) }
                                .then(ClientCommands.literal("all").executes { Ui.reply(it, JourneyMapEdits.download(false, areaOf(it))) }))))))
                .then(ClientCommands.literal("cancel").executes {
                    Ui.reply(it, if (JourneyMapEdits.cancel()) "Stopping after the region it is on" else "Nothing is being written into JourneyMap's map")
                })
        )
        root.then(ClientCommands.literal("undo").executes { Ui.reply(it, JourneyMapEdits.undo()) })
        // `clear <kind>` says what would go; `clear <kind> confirm` takes it off; a border can be given by hand.
        val clear = ClientCommands.literal("clear")
        for (kind in JourneyMapEdits.Clear.entries) {
            val node = ClientCommands.literal(kind.word)
                .executes { it.source.sendFeedback(JourneyMapEdits.clearPreview(kind, null)); 1 }
                .then(ClientCommands.literal("confirm").executes { Ui.reply(it, JourneyMapEdits.clear(kind, null)) })
            if (kind == JourneyMapEdits.Clear.BORDER) {
                node.then(ClientCommands.argument("radius", IntegerArgumentType.integer(1))
                    .executes { it.source.sendFeedback(JourneyMapEdits.clearPreview(kind, boxOf(it, false))); 1 }
                    .then(ClientCommands.literal("confirm").executes { Ui.reply(it, JourneyMapEdits.clear(kind, boxOf(it, false))) })
                    .then(ClientCommands.argument("centerX", IntegerArgumentType.integer())
                        .then(ClientCommands.argument("centerZ", IntegerArgumentType.integer())
                            .executes { it.source.sendFeedback(JourneyMapEdits.clearPreview(kind, boxOf(it, true))); 1 }
                            .then(ClientCommands.literal("confirm").executes { Ui.reply(it, JourneyMapEdits.clear(kind, boxOf(it, true))) }))))
            }
            clear.then(node)
        }
        root.then(clear)
        root.then(biomeCommand())
        // The same as Xaero's: the chunks between two block positions, from the map shown.
        root.then(ClientCommands.literal("terrain")
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
                Ui.reply(it, if (JourneyMapTerrainSave.cancel()) "Stopping the terrain file save…" else "No map file is being saved")
            }))
    }

    /** The chunks between two block positions, inclusive, or the whole map ([whole]) shown, to a terrain file; a big one asks first. */
    private fun saveTerrain(context: CommandContext<FabricClientCommandSource>, whole: Boolean, confirmed: Boolean): Int {
        val dimension = shownDimension() ?: return Ui.reply(context, "Not in a world")
        val area = if (whole) null else areaOf(context)
        return SharedTerrain.reply(context, JourneyMapTerrainSave.save(dimension, area, confirmed))
    }

    /**
     * `biome` lists the biomes highlighted, `biome <name or id>` highlights one or stops, `biome clear`
     * stops them all; on Xaero's map the map bar does this, and JourneyMap's right-click menu offers
     * the biome under the click.
     */
    private fun biomeCommand(): LiteralArgumentBuilder<FabricClientCommandSource> {
        fun biomes() = Minecraft.getInstance().level?.registryAccess()?.lookupOrThrow(Registries.BIOME)?.listElementIds()?.toList().orEmpty()
        return ClientCommands.literal("biome")
            .executes {
                Ui.reply(it, if (!BiomePicks.any) "No biomes are highlighted" else BiomePicks.all.keys.joinToString(", ", "Highlighted: ") { key -> BiomePicks.nameOf(key) })
            }
            .then(ClientCommands.literal("clear").executes { BiomePicks.clear(); Ui.reply(it, "Biome highlights cleared") })
            .then(ClientCommands.argument("biome", StringArgumentType.greedyString())
                .suggests { _, builder ->
                    val typed = builder.remaining.lowercase()
                    biomes().map { it.identifier().toString() }.filter { it.contains(typed) }.sorted().forEach(builder::suggest)
                    builder.buildFuture()
                }
                .executes {
                    val typed = StringArgumentType.getString(it, "biome").trim()
                    val biome = biomes().firstOrNull { key -> key.identifier().toString() == typed || key.identifier().path == typed }
                        ?: biomes().firstOrNull { key -> BiomePicks.nameOf(key).equals(typed, ignoreCase = true) }
                        ?: return@executes Ui.reply(it, "No biome called '$typed' here")
                    BiomePicks.toggle(biome)
                    Ui.reply(it, (if (BiomePicks.isPicked(biome)) "Highlighting " else "No longer highlighting ") + BiomePicks.nameOf(biome))
                })
    }

    /** The region holding block (`x`, `z`) of a command. */
    /** The region around block x, z, as the chunks it covers. */
    private fun regionOf(context: CommandContext<FabricClientCommandSource>): ShareFormat.Area {
        val rx = Math.floorDiv(IntegerArgumentType.getInteger(context, "x"), JourneyMapFiles.REGION)
        val rz = Math.floorDiv(IntegerArgumentType.getInteger(context, "z"), JourneyMapFiles.REGION)
        return ShareFormat.Area(rx * 32, rz * 32, rx * 32 + 31, rz * 32 + 31)
    }

    /** The chunks between block positions x1, z1 and x2, z2, inclusive. */
    private fun areaOf(context: CommandContext<FabricClientCommandSource>): ShareFormat.Area {
        fun chunk(name: String) = Math.floorDiv(IntegerArgumentType.getInteger(context, name), 16)
        return ShareFormat.Area(minOf(chunk("x1"), chunk("x2")), minOf(chunk("z1"), chunk("z2")), maxOf(chunk("x1"), chunk("x2")), maxOf(chunk("z1"), chunk("z2")))
    }

    private fun boxOf(context: CommandContext<FabricClientCommandSource>, centred: Boolean) = MapBorder.Box(
        IntegerArgumentType.getInteger(context, "radius"),
        if (centred) IntegerArgumentType.getInteger(context, "centerX") else 0,
        if (centred) IntegerArgumentType.getInteger(context, "centerZ") else 0,
    )

    override fun status(say: (String) -> Unit) {
        say("JourneyMap plugin: " + if (api != null) "§aloaded" else "§cnot started (needs JourneyMap's API 2)")
        if (api != null) say("On JourneyMap: ${JourneyMapPins.summary()}, ${JourneyMapTerrain.summary()}" + (JourneyMapBiomes.summary()?.let { ", $it" } ?: ""))
        fun mark(ok: Boolean) = if (ok) "§aok§r" else "§cmissing§r"
        say("JourneyMap hooks: dimension ${mark(dimensionHooked())}, minimap ${mark(minimapHooked() && JourneyMapMinimap.working)}, chunk cache ${mark(JourneyMapChunkCache.knownFormat)}, " +
            "internals " + if (missingInternals.isEmpty()) mark(true) else "§c${missingInternals.joinToString { it.first }} missing§r")
    }

    /** The full-screen map's dimension while it is open; otherwise yours, which it opens on. */
    override fun shownDimension(): String? {
        val shown = fullscreen()?.dimension ?: Minecraft.getInstance().level?.dimension()
        return shown?.identifier()?.toString()
    }

    /** The dimension shown, while JourneyMap shows a layer BlueMap's picture does not belong in. */
    override fun cavesShown(session: Session): String? {
        val state = fullscreen() ?: return null
        val dimension = state.dimension?.identifier()?.toString() ?: return null
        val map = session.server.mapFor(dimension) ?: return null
        return dimension.takeIf { !blueMapLayerShown(session, dimension, map, state) }
    }

    /**
     * Whether JourneyMap is showing the layer BlueMap's picture belongs in, by Xaero's rule
     * ([MapLayers.blueMapLayer]): its day map is the surface and its cave map a cave layer.
     */
    fun blueMapLayerShown(session: Session, dimension: String, map: String, state: UIState): Boolean {
        val shown = if (state.mapType == Context.MapType.Underground) state.chunkY ?: 0 else MapLayers.SURFACE
        return MapLayers.blueMapLayer(shown, { SeaFloor.hasCeiling(dimension) }) { session.view(map) } != null
    }

    override fun goTo(dimension: String, x: Int, z: Int, from: Screen?, player: UUID?): Boolean =
        JourneyMapMenus.goTo(dimension, x, z, from)

    override fun addSharedTerrain(file: SharedTerrain.Opened, id: Int, unexploredOnly: Boolean, confirmed: Boolean): String =
        JourneyMapEdits.addShared(file, unexploredOnly)

    override fun downloadStatus(): String? = JourneyMapEdits.status()?.takeIf { JourneyMapEdits.running }

    override fun cancelDownload(): Boolean = JourneyMapEdits.cancel()

    override fun myWaypoints(): Pair<String, List<ShareableWaypoint>>? = api?.let(JourneyMapWaypoints::mine)

    override fun addWaypoints(dimension: String, from: String, waypoints: List<ShareFormat.SharedWaypoint>): String =
        api?.let { JourneyMapWaypoints.addShared(it, dimension, from, waypoints) } ?: "JourneyMap has not started this mod's plugin"

    /** How long after joining before leftover temporary waypoints are looked for: a server sends its waypoints a moment after you join. */
    private const val TIDY_DELAY_MS = 5_000L

    /** The game's key for dimension [id] ("minecraft:the_nether"). */
    fun levelKey(id: String): ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, Identifier.parse(id))

    private fun fullscreen() = api?.getUIState(Context.UI.Fullscreen)?.takeIf { it.active }
}
