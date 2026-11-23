package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.lruMap
import com.skystormer.skysmapexposer.packXZ
import com.mojang.blaze3d.platform.NativeImage
import com.skystormer.skysmapexposer.BiomePicks
import com.skystormer.skysmapexposer.BiomeZoom
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.SafeFiles
import com.skystormer.skysmapexposer.journeymap.JourneyMapFiles.REGION
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.client.util.UIState
import journeymap.api.v2.common.Context
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.core.RegistryAccess
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Tints JourneyMap's full-screen map and minimap wherever one of the picked biomes ([BiomePicks])
 * is, as Xaero's maps are tinted ([com.skystormer.skysmapexposer.xaero.BiomeHighlight]); the minimap
 * by the same switch ([Config.minimapBiomes]).
 *
 * Xaero keeps the biome of every pixel of its map; JourneyMap does not, but it keeps the chunks it
 * has mapped in its chunk cache (`<world>/<dimension>/chunk_cache`, one file per region), biomes
 * included. Those files are read ([JourneyMapChunkCache]), read-only and off the client thread, a
 * region (32 by 32 chunks) at a time, into a small grid of the biome every 4 blocks (the game keeps
 * biomes no finer): at the top block on the surface maps, at the slice shown on the cave map. Any
 * dimension the map shows, as on Xaero's. A region is read when it first comes on screen, and
 * again only once JourneyMap has written its cache file since.
 *
 * The game keeps biomes only every 4 blocks, but shows each block the biome of one of the nearest
 * of those cells, picked by a jitter from the world's seed, so borders wander block by block; Xaero's
 * map records each block's biome that way. So each pixel of the tint takes its biome by the same
 * rule ([BiomeZoom]), at the surface height kept with the grid, and the borders come out as on
 * Xaero's map rather than in 4-block steps.
 *
 * The tint is see-through pictures ([RegionPicture]) over BlueMap's terrain and under the grid,
 * outlines and pins: one per region close up, a pixel per block; zoomed out, one per square of
 * regions at a coarser pixel, so the whole screen is tinted at any zoom, as on Xaero's map. Pictures
 * are made off the client thread. Regions are read a few at a time, nearest first, and each grid is
 * kept in a small file of this mod's ([saved]), so a region is read from JourneyMap's cache only
 * once for each time JourneyMap writes it.
 */
internal object JourneyMapBiomes {

    /** A region of a dimension, at the surface ([layer] null) or a cave slice. */
    private data class Area(val dimension: String, val x: Int, val z: Int, val layer: Int?)

    /**
     * An area's biomes, one cell per 4 blocks: an index into [palette], or -1 where JourneyMap has
     * nothing; on the surface, the height of each cell's middle column ([NO_HEIGHT] where unknown);
     * and when JourneyMap last wrote the cache file they were read from.
     */
    private class Grid(val palette: List<ResourceKey<Biome>>, val cells: ShortArray, val heights: ShortArray?, val fileTime: Long)

    /** Lately read grids, least lately used first. Client thread only. */
    private val grids = lruMap<Area, Grid>(MAX_GRIDS)

    /** [group] by [group] regions of a dimension from region (`x * group`, `z * group`): one picture's worth, [side] pixels across. */
    private data class Square(val dimension: String, val x: Int, val z: Int, val layer: Int?, val group: Int, val side: Int) {
        val span: Int get() = REGION * group
    }

    /** A square's tint on one of the maps, from grids of [stamp] with the picks of [version]; no [picture] where nothing picked is. */
    private class Tint(val stamp: Long, val version: Int, val picture: RegionPicture?)

    private val shown = HashMap<Pair<Square, Context.UI>, Tint>()

    /** Areas being read; a few at a time ([READERS]), nearest first, so panning gets what is on screen soon. */
    private val reading = HashSet<Area>()

    /** Pictures being made off the client thread. */
    private val building = HashSet<Pair<Square, Context.UI>>()

    private val builder by lazy {
        Executors.newFixedThreadPool(BUILDERS) { Thread(it, "Map Exposer JourneyMap biome pictures").apply { isDaemon = true } }
    }

    /** Bumped on leaving, so a grid read for the last world is thrown away when it arrives. */
    private var generation = 0

    /** Started the first time a grid is read. */
    private val worker by lazy {
        Executors.newFixedThreadPool(READERS) { Thread(it, "Map Exposer JourneyMap biomes").apply { isDaemon = true } }
    }

    private val lastActive = HashMap<Context.UI, Long>()
    private val checkedAt = HashMap<Context.UI, Long>()

    /** Why nothing is being tinted on the full-screen map just now, if anything stops it. */
    private var note = ""

    fun tick(api: IClientAPI) {
        tickFor(api, Context.UI.Fullscreen)
        tickFor(api, Context.UI.Minimap)
    }

    private fun tickFor(api: IClientAPI, ui: Context.UI) {
        val fullscreen = ui == Context.UI.Fullscreen
        val now = System.currentTimeMillis()
        val state = api.getUIState(ui)
        val wantedHere = BiomePicks.any && (fullscreen || Config.minimapBiomes)
        if (!wantedHere || state == null || !state.active) {
            if (fullscreen) note = if (BiomePicks.any) "the full-screen map is closed" else ""
            // Kept while the map is closed for a moment; let go once it has stayed closed, or nothing is wanted.
            if (shown.keys.any { it.second == ui } && (!wantedHere || now - (lastActive[ui] ?: 0L) > KEEP_CLOSED_MS)) clear(ui)
            return
        }
        lastActive[ui] = now
        val registries = Minecraft.getInstance().level?.registryAccess() ?: return
        val dimension = state.dimension?.identifier()?.toString() ?: return
        if (!JourneyMapChunkCache.knownFormat) {
            if (fullscreen) note = "this JourneyMap keeps its chunk cache in a format this mod does not know"
            return
        }
        val layer = layerOf(state)
        val bounds = ScreenSquares.areaOf(ui, state) ?: return
        val onScreen = ScreenSquares(bounds, REGION)
        // Regions on screen, nearest first: every one is read, however far out.
        val regions = onScreen.nearestFirst().take(MAX_GRIDS).map { (x, z) -> Area(dimension, x, z, layer) }
        if (fullscreen) note = if (onScreen.count > MAX_GRIDS) "zoomed too far out to tint it all" else ""
        // Pictures: a region each close up, more regions each the further out, so there are never more than MAX_SHOWN.
        var group = 1
        fun count(group: Int) = ScreenSquares(bounds, REGION * group).count
        while (group < MAX_GROUP && count(group) > MAX_SHOWN) group *= 2
        // A pixel per block close up, where blocks show; half that once more than a few pictures are up.
        val side = if (group == 1 && count(1) <= FINE_SHOWN) REGION else REGION / 2
        val wanted = ScreenSquares(bounds, REGION * group).nearestFirst().map { (x, z) -> Square(dimension, x, z, layer, group, side) }
        val wantedSet = wanted.toHashSet()
        // Another zoom's pictures stay until this zoom's over them are up, so zooming never blanks the tint.
        shown.keys.filter { (square, onUi) ->
            onUi == ui && square !in wantedSet && (square.group == group || square.dimension != dimension || wanted.all { shown[it to ui] != null })
        }.forEach(::remove)

        // Now and then, whether JourneyMap has written any of these regions' cache files since they were read.
        val recheck = now - (checkedAt[ui] ?: 0L) > RECHECK_MS
        if (recheck) checkedAt[ui] = now
        val cacheFolder = if (recheck) JourneyMapFiles.chunkCacheFolder(dimension) else null
        val importFolder = if (recheck) JourneyMapImportedBiomes.folder(dimension) else null
        for (area in regions) {
            if (reading.size >= READERS * 2) break
            if (area in reading) continue
            val grid = grids[area]
            if (grid == null || cacheFolder != null && stamp(cacheFolder, importFolder, area) != grid.fileTime) read(area, registries)
        }
        var built = 0
        for (square in wanted) {
            if (built >= PER_TICK) break
            if (square to ui in building) continue
            val stamp = gridsStamp(square)
            if (stamp == 0L) continue
            val old = shown[square to ui]
            if (old != null && old.stamp == stamp && old.version == BiomePicks.version) continue
            build(square, ui, stamp)
            built++
        }
    }

    /** Which grids [square] would be drawn from just now (0 if none is read yet), so its picture is made again only when one changes. */
    private fun gridsStamp(square: Square): Long {
        var stamp = 0L
        var any = false
        for (z in 0 until square.group) for (x in 0 until square.group) {
            val grid = grids[Area(square.dimension, square.x * square.group + x, square.z * square.group + z, square.layer)]
            stamp = stamp * 1_000_003L + (if (grid == null) 7 else System.identityHashCode(grid).toLong().also { any = true })
        }
        return if (any) stamp or 1L else 0L
    }

    /** The cave map's slice, else null for the top block. */
    private fun layerOf(state: UIState): Int? = if (state.mapType == Context.MapType.Underground) state.chunkY else null

    /**
     * For `BlockInfoBiomeMixin`: the biome to name on JourneyMap's line under the mouse at ([x], [z])
     * on a map of [dimension] you are not in (the dimension toggle), where JourneyMap would name the
     * biome at those coordinates in the world you are in. Empty where it knows none there; null on
     * a map of your own dimension, left to JourneyMap.
     */
    @JvmStatic
    fun otherDimensionName(dimension: ResourceKey<Level>?, x: Int, z: Int): String? {
        val here = Minecraft.getInstance().level?.dimension() ?: return null
        if (dimension == null || dimension == here) return null
        val state = JourneyMapAdapter.api?.getUIState(Context.UI.Fullscreen) ?: return ""
        if (state.dimension != dimension) return ""
        return biomeAt(state, x, z)?.let(BiomePicks::nameOf) ?: ""
    }

    /**
     * The biome at block ([x], [z]) on JourneyMap's full-screen map, by the same rule as the tint,
     * or null where it knows none. Client thread; may read one chunk from JourneyMap's cache.
     */
    fun biomeAt(state: UIState?, x: Int, z: Int): ResourceKey<Biome>? {
        val registries = Minecraft.getInstance().level?.registryAccess() ?: return null
        val dimension = state?.dimension?.identifier()?.toString() ?: return null
        val layer = layerOf(state)
        val area = Area(dimension, Math.floorDiv(x, REGION), Math.floorDiv(z, REGION), layer)
        grids[area]?.let { grid ->
            // By the game's rule, as the tint is drawn, where the cell it picks has been read.
            val seed = BiomeZoom.seed()
            if (seed != null) {
                val y = sliceY(layer) ?: grid.heights?.get(cellIndex(Math.floorMod(x, REGION) / 4, Math.floorMod(z, REGION) / 4))?.toInt()
                    ?.takeIf { it != NO_HEIGHT.toInt() } ?: SEA_LEVEL
                val picked = BiomeZoom.cellOf(seed, x, y, z)
                val cellX = (picked shr 32).toInt()
                val cellZ = picked.toInt()
                grids[Area(dimension, Math.floorDiv(cellX, CELLS), Math.floorDiv(cellZ, CELLS), layer)]?.let { at ->
                    val cell = at.cells[cellIndex(Math.floorMod(cellX, CELLS), Math.floorMod(cellZ, CELLS))].toInt()
                    if (cell >= 0) return at.palette[cell]
                }
            }
            val cell = grid.cells[cellIndex(Math.floorMod(x, REGION) / 4, Math.floorMod(z, REGION) / 4)].toInt()
            if (cell >= 0) return grid.palette[cell]
        }
        val folder = JourneyMapFiles.chunkCacheFolder(dimension) ?: return null
        val chunk = Math.floorMod(z shr 4, CHUNKS) * CHUNKS + Math.floorMod(x shr 4, CHUNKS)
        val own = try {
            JourneyMapChunkCache.readChunk(cacheFile(folder, area), chunk, registries)?.biomeAt(x and 15, z and 15, sliceY(layer))
        } catch (e: Exception) {
            null
        }
        // Where JourneyMap knows none: a shared map file's, on the surface.
        return own ?: if (layer == null) JourneyMapImportedBiomes.biomeAt(dimension, x, z) else null
    }

    /**
     * When [area]'s biomes last changed: JourneyMap's cache file, and on the surface the shared
     * map files' biomes ([JourneyMapImportedBiomes]) too.
     */
    private fun stamp(cacheFolder: Path, importFolder: Path?, area: Area): Long =
        fileTime(cacheFolder, area) * 31 + (if (area.layer == null && importFolder != null) JourneyMapImportedBiomes.fileTime(importFolder, area.x, area.z) else 0L)

    private fun cacheFile(folder: Path, area: Area): Path = JourneyMapChunkCache.file(folder, area.x, area.z)

    /** The height a cave slice is read at: its middle. */
    private fun sliceY(layer: Int?): Int? = layer?.let { it * 16 + 8 }

    /** When JourneyMap last wrote [area]'s cache file, or 0 if it has none. */
    private fun fileTime(folder: Path, area: Area): Long = try {
        Files.getLastModifiedTime(cacheFile(folder, area)).toMillis()
    } catch (e: Exception) {
        0L
    }

    /** Off the client thread: [area]'s grid, from this mod's copy if JourneyMap has not written since ([saved]), else from JourneyMap's chunk cache. */
    private fun read(area: Area, registries: RegistryAccess) {
        val folder = JourneyMapFiles.chunkCacheFolder(area.dimension) ?: return
        val importFolder = if (area.layer == null) JourneyMapImportedBiomes.folder(area.dimension) else null
        val savedFile = saved(area)
        reading.add(area)
        val generation = generation
        worker.execute {
            val grid = try {
                // The file's date first: a write while this reads is caught by the next check.
                val fileTime = stamp(folder, importFolder, area)
                loadSaved(savedFile, fileTime) ?: run {
                    val own = gridOf(JourneyMapChunkCache.readRegion(cacheFile(folder, area), registries), area.layer, fileTime)
                    val grid = importFolder?.let { JourneyMapImportedBiomes.read(it, area.x, area.z) }?.let { withImported(own, it) } ?: own
                    savedFile?.let { save(it, grid) }
                    grid
                }
            } catch (e: Exception) {
                Log.warn("Could not read JourneyMap's biomes for region {}, {}: {}", area.x, area.z, e.toString())
                null
            }
            Minecraft.getInstance().execute {
                if (generation != this.generation) return@execute
                reading.remove(area)
                if (grid != null) grids[area] = grid
            }
        }
    }

    private fun gridOf(chunks: Array<JourneyMapChunkCache.Chunk?>, layer: Int?, fileTime: Long): Grid {
        val palette = ArrayList<ResourceKey<Biome>>()
        val indexOf = HashMap<ResourceKey<Biome>, Int>()
        val cells = ShortArray(CELLS * CELLS) { -1 }
        val heights = if (layer == null) ShortArray(CELLS * CELLS) { NO_HEIGHT } else null
        val y = sliceY(layer)
        for (chunkZ in 0 until CHUNKS) for (chunkX in 0 until CHUNKS) {
            val chunk = chunks[chunkZ * CHUNKS + chunkX] ?: continue
            for (quarterZ in 0 until 4) for (quarterX in 0 until 4) {
                val biome = chunk.biomeAt(quarterX * 4 + 2, quarterZ * 4 + 2, y) ?: continue
                val index = indexOf.getOrPut(biome) { palette.add(biome); palette.size - 1 }
                if (index > Short.MAX_VALUE) continue
                val cell = cellIndex(chunkX * 4 + quarterX, chunkZ * 4 + quarterZ)
                cells[cell] = index.toShort()
                chunk.surface?.let { surface -> heights?.set(cell, (surface[(quarterX * 4 + 2) + (quarterZ * 4 + 2) * 16] - 1).toShort()) }
            }
        }
        return Grid(palette, cells, heights, fileTime)
    }

    /** [grid] with a shared map file's biomes where JourneyMap knows none. */
    private fun withImported(grid: Grid, imported: JourneyMapImportedBiomes.Grid): Grid {
        val palette = ArrayList(grid.palette)
        val indexOf = HashMap<ResourceKey<Biome>, Int>().apply { palette.forEachIndexed { i, key -> put(key, i) } }
        val cells = grid.cells.copyOf()
        for (cellZ in 0 until CELLS) for (cellX in 0 until CELLS) {
            val index = cellIndex(cellX, cellZ)
            if (cells[index] >= 0) continue
            val biome = imported.at(cellX, cellZ) ?: continue
            val at = indexOf.getOrPut(biome) { palette.add(biome); palette.size - 1 }
            if (at <= Short.MAX_VALUE) cells[index] = at.toShort()
        }
        return Grid(palette, cells, grid.heights, grid.fileTime)
    }

    /** Where [area]'s grid is kept between games, in this mod's folder for the server; null outside one. Client thread. */
    private fun saved(area: Area): Path? = JourneyMapEdits.serverFolder()?.resolve("journeymap-biome-grids")?.resolve(SafeFiles.name(area.dimension))
        ?.resolve(area.layer?.let { "cave$it" } ?: "surface")?.resolve("${area.x},${area.z}.grid")

    /**
     * The grid kept in [file], if it was read from JourneyMap's files as they are now ([fileTime]);
     * else null. "SMEG", format 1, then deflated: the file time, the biomes used, each cell's index
     * (-1 none), and on the surface each cell's height.
     */
    private fun loadSaved(file: Path?, fileTime: Long): Grid? {
        if (file == null || !Files.isRegularFile(file)) return null
        return try {
            DataInputStream(Files.newInputStream(file).buffered()).use { raw ->
                if (raw.readInt() != SAVED_MAGIC || raw.readInt() != SAVED_FORMAT) return null
                DataInputStream(InflaterInputStream(raw).buffered()).use { input ->
                    if (input.readLong() != fileTime) return null
                    val count = input.readInt()
                    if (count !in 0..Short.MAX_VALUE) return null
                    val palette = List(count) { ResourceKey.create(Registries.BIOME, Identifier.parse(input.readUTF())) }
                    val cells = ShortArray(CELLS * CELLS) { input.readShort() }
                    val heights = if (input.readBoolean()) ShortArray(CELLS * CELLS) { input.readShort() } else null
                    if (cells.any { it >= palette.size }) return null
                    Grid(palette, cells, heights, fileTime)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun save(file: Path, grid: Grid) {
        try {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { raw ->
                raw.writeInt(SAVED_MAGIC)
                raw.writeInt(SAVED_FORMAT)
                DataOutputStream(DeflaterOutputStream(raw)).use { out ->
                    out.writeLong(grid.fileTime)
                    out.writeInt(grid.palette.size)
                    grid.palette.forEach { out.writeUTF(it.identifier().toString()) }
                    grid.cells.forEach { out.writeShort(it.toInt()) }
                    out.writeBoolean(grid.heights != null)
                    grid.heights?.forEach { out.writeShort(it.toInt()) }
                }
            }
            SafeFiles.write(file, bytes.toByteArray())
        } catch (e: Exception) {
            Log.warn("Could not keep the biomes of {}: {}", file.fileName, e.toString())
        }
    }

    /**
     * Client thread: [square]'s tint on [ui] made off the client thread ([picture]) from the grids
     * read just now ([stamp]), then put up in place of the old one.
     */
    private fun build(square: Square, ui: Context.UI, stamp: Long) {
        val key = square to ui
        // Its regions' grids and the ring around them, which the game's jitter reaches into at the edges.
        val near = HashMap<Long, Grid>()
        for (z in -1..square.group) for (x in -1..square.group) {
            val regionX = square.x * square.group + x
            val regionZ = square.z * square.group + z
            grids[Area(square.dimension, regionX, regionZ, square.layer)]?.let { near[packXZ(regionX, regionZ)] = it }
        }
        val colours = HashMap(BiomePicks.all)
        val version = BiomePicks.version
        val seed = BiomeZoom.seed()
        val generation = generation
        building.add(key)
        builder.execute {
            val image = try {
                picture(square, near, colours, seed)
            } catch (e: Exception) {
                Log.warn("Could not make the biome tint of {}: {}", square, e.toString())
                null
            }
            Minecraft.getInstance().execute {
                building.remove(key)
                // Picks changed or a world left meanwhile: made again from the new ones next tick.
                if (generation != this.generation || version != BiomePicks.version) {
                    image?.close()
                    return@execute
                }
                remove(key)
                shown[key] = Tint(stamp, version, image?.let { RegionPicture.show("biomes", square.x * square.span, square.z * square.span, square.span / square.side, it, ui) })
            }
        }
    }

    /**
     * Off the client thread: [square]'s tint, [Square.side] pixels across, each the biome its middle
     * block shows by the game's rule ([BiomeZoom], with the world's [seed]; without one, the cell it
     * is in), coloured by [colours]; null where nothing picked is.
     */
    private fun picture(square: Square, near: Map<Long, Grid>, colours: Map<ResourceKey<Biome>, Int>, seed: Long?): NativeImage? {
        val side = square.side
        val perPixel = square.span / side
        val sliceY = sliceY(square.layer)
        val colourOf = HashMap<Grid, IntArray>()
        fun gridOfCell(cellX: Int, cellZ: Int): Grid? = near[packXZ(Math.floorDiv(cellX, CELLS), Math.floorDiv(cellZ, CELLS))]
        val image = NativeImage(side, side, true)
        var any = false
        for (pz in 0 until side) for (px in 0 until side) {
            val blockX = square.x * square.span + px * perPixel + perPixel / 2
            val blockZ = square.z * square.span + pz * perPixel + perPixel / 2
            var cellX = blockX shr 2
            var cellZ = blockZ shr 2
            if (seed != null) {
                val y = sliceY ?: gridOfCell(cellX, cellZ)?.heights?.get(cellIndex(Math.floorMod(cellX, CELLS), Math.floorMod(cellZ, CELLS)))
                    ?.toInt()?.takeIf { it != NO_HEIGHT.toInt() } ?: SEA_LEVEL
                val picked = BiomeZoom.cellOf(seed, blockX, y, blockZ)
                cellX = (picked shr 32).toInt()
                cellZ = picked.toInt()
            }
            val grid = gridOfCell(cellX, cellZ)
            val cell = grid?.cells?.get(cellIndex(Math.floorMod(cellX, CELLS), Math.floorMod(cellZ, CELLS)))?.toInt() ?: -1
            val colour = if (grid == null || cell < 0) 0 else colourOf.getOrPut(grid) { IntArray(grid.palette.size) { colours[grid.palette[it]] ?: 0 } }[cell]
            if (colour != 0) any = true
            image.setPixel(px, pz, colour)
        }
        if (!any) {
            image.close()
            return null
        }
        return image
    }

    private fun remove(key: Pair<Square, Context.UI>) {
        val gone = shown.remove(key)?.picture ?: return
        gone.remove()
    }

    private fun clear(ui: Context.UI) {
        shown.keys.filter { it.second == ui }.forEach(::remove)
    }

    /** [ui]'s tint of [dimension], for [JourneyMapMinimap] to draw. */
    fun pictures(ui: Context.UI, dimension: String): List<RegionPicture> =
        shown.mapNotNull { (key, tint) -> tint.picture.takeIf { key.second == ui && key.first.dimension == dimension } }

    /** Leaving a world: JourneyMap drops our overlays ([JourneyMapAdapter.leave]); this frees the pictures and grids. */
    fun forget() {
        generation++
        shown.values.forEach { it.picture?.release() }
        shown.clear()
        grids.clear()
        reading.clear()
        building.clear()
    }

    /** "tinting 2 biomes in 5 regions" for `/mapexposer`, or nothing while none is picked. */
    fun summary(): String? {
        if (!BiomePicks.any) return null
        fun regionsOn(ui: Context.UI) = shown.entries.filter { it.key.second == ui && it.value.picture != null }.sumOf { it.key.first.group * it.key.first.group }
        val regions = regionsOn(Context.UI.Fullscreen)
        val biomes = BiomePicks.all.size
        return "tinting $biomes biome${if (biomes == 1) "" else "s"} in $regions region${if (regions == 1) "" else "s"}" +
            (if (note.isEmpty()) "" else " ($note)") + ", ${regionsOn(Context.UI.Minimap)} on the minimap"
    }

    private fun cellIndex(x: Int, z: Int) = z * CELLS + x

    private const val CHUNKS = REGION / 16
    private const val CELLS = REGION / 4

    /** About 32 KB each: enough for every region on a zoomed-out screen. */
    private const val MAX_GRIDS = 1024
    private const val MAX_SHOWN = 64
    /** At most this many regions on screen get a pixel per block. */
    private const val FINE_SHOWN = 16
    private const val MAX_GROUP = 32
    private val READERS = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(3, 6)
    private const val BUILDERS = 2
    private const val PER_TICK = 8
    private const val NO_HEIGHT: Short = Short.MIN_VALUE
    /** The height a block of unknown height is taken at, for the game's jitter. */
    private const val SEA_LEVEL = 63
    private const val SAVED_MAGIC = 0x534D4547
    private const val SAVED_FORMAT = 1

    /** How often the cache files of the regions on screen are looked at for changes. */
    private const val RECHECK_MS = 5_000L
    private const val KEEP_CLOSED_MS = 30_000L
}
