package com.skystormer.skysmapexposer.journeymap

import com.mojang.blaze3d.platform.NativeImage
import com.skystormer.skysmapexposer.BiomePicks
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.Log
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.client.util.UIState
import journeymap.api.v2.common.Context
import journeymap.common.chunk.JMChunkCache
import journeymap.common.chunk.JMChunkSnapshot
import journeymap.common.chunk.JMChunkStorage
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.Heightmap
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Tints JourneyMap's full-screen map and minimap wherever one of the picked biomes ([BiomePicks])
 * is, as Xaero's maps are tinted ([com.skystormer.skysmapexposer.xaero.BiomeHighlight]); the minimap
 * by the same switch ([Config.minimapBiomes]).
 *
 * Xaero keeps the biome of every pixel of its map; JourneyMap does not, but it keeps the chunks it
 * has mapped in its chunk cache (`<world>/<dimension>/chunk_cache`, one file per region), biomes
 * included. Those are read through JourneyMap's own reader, read-only and off the client thread, a
 * region (32 by 32 chunks) at a time, into a small grid of the biome every 4 blocks (the game keeps
 * biomes no finer): at the top block on the surface maps, at the slice shown on the cave map. A
 * region is read when it first comes on screen, and again only once JourneyMap has written its
 * cache file since. JourneyMap keeps the cache of the dimension you are in only, so that is the one
 * tinted.
 *
 * Each region with something picked in it is one see-through picture ([RegionPicture]), a pixel
 * per 4 blocks, over BlueMap's terrain and under the outlines and pins.
 */
internal object JourneyMapBiomes {

    /** A region of a dimension, at the surface ([layer] null) or a cave slice. */
    private data class Area(val dimension: String, val x: Int, val z: Int, val layer: Int?)

    /**
     * An area's biomes, one cell per 4 blocks: an index into [palette], or -1 where JourneyMap has
     * nothing; and when JourneyMap last wrote the cache file they were read from.
     */
    private class Grid(val palette: List<ResourceKey<Biome>>, val cells: ShortArray, val fileTime: Long)

    /** Lately read grids, least lately used first. Client thread only. */
    private val grids = object : LinkedHashMap<Area, Grid>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Area, Grid>) = size > MAX_GRIDS
    }

    /** An area's tint on one of the maps, from [grid] with the picks of [version]; no [picture] where nothing picked is. */
    private class Tint(val grid: Grid, val version: Int, val picture: RegionPicture?)

    private val shown = HashMap<Pair<Area, Context.UI>, Tint>()

    /** Areas being read; one at a time, nearest first, so panning gets what is on screen soon. */
    private val reading = HashSet<Area>()

    /** Bumped on leaving, so a grid read for the last world is thrown away when it arrives. */
    private var generation = 0

    /** Started the first time a grid is read. */
    private val worker by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "Map Exposer JourneyMap biomes").apply { isDaemon = true } }
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
            if (shown.keys.any { it.second == ui } && (!wantedHere || now - (lastActive[ui] ?: 0L) > KEEP_CLOSED_MS)) clear(api, ui)
            return
        }
        lastActive[ui] = now
        val level = Minecraft.getInstance().level ?: return
        val dimension = state.dimension?.identifier()?.toString() ?: return
        if (dimension != level.dimension().identifier().toString()) {
            clear(api, ui)
            if (fullscreen) note = "JourneyMap keeps biomes for the dimension you are in only"
            return
        }
        if (STORAGE == null) {
            if (fullscreen) note = "this JourneyMap keeps its chunk cache where this mod cannot read it"
            return
        }
        val layer = layerOf(state)
        val bounds = if (fullscreen) JourneyMapMenus.shownArea(state) ?: return
        else JourneyMapMinimap.area ?: return
        val left = Math.floorDiv(bounds[0].toInt(), REGION)
        val top = Math.floorDiv(bounds[1].toInt(), REGION)
        val right = Math.floorDiv(bounds[2].toInt(), REGION)
        val bottom = Math.floorDiv(bounds[3].toInt(), REGION)
        val centreX = (left + right) / 2.0
        val centreZ = (top + bottom) / 2.0
        val wanted = buildList {
            for (z in top..bottom) for (x in left..right) add(Area(dimension, x, z, layer))
        }.sortedBy { (it.x - centreX) * (it.x - centreX) + (it.z - centreZ) * (it.z - centreZ) }.take(MAX_SHOWN)
        if (fullscreen) note = if ((right - left + 1) * (bottom - top + 1) > MAX_SHOWN) "zoomed too far out to tint it all" else ""
        val wantedSet = wanted.toHashSet()
        shown.keys.filter { it.second == ui && it.first !in wantedSet }.forEach { remove(api, it) }

        // Now and then, whether JourneyMap has written any of these regions' cache files since they were read.
        val recheck = now - (checkedAt[ui] ?: 0L) > RECHECK_MS
        if (recheck) checkedAt[ui] = now
        val cacheFolder = if (recheck) JourneyMapFiles.chunkCacheFolder(dimension) else null
        var built = 0
        for (area in wanted) {
            val grid = grids[area]
            if (reading.isEmpty() && (grid == null || cacheFolder != null && fileTime(cacheFolder, area) != grid.fileTime)) read(area, level)
            if (grid == null || built >= PER_TICK) continue
            val old = shown[area to ui]
            if (old != null && old.grid === grid && old.version == BiomePicks.version) continue
            show(api, area, ui, grid)
            built++
        }
    }

    /** The cave map's slice, else null for the top block. */
    private fun layerOf(state: UIState): Int? = if (state.mapType == Context.MapType.Underground) state.chunkY else null

    /**
     * The biome at block ([x], [z]) on JourneyMap's full-screen map, by the same rule as the tint,
     * or null where it knows none. Client thread; may read one chunk from JourneyMap's cache.
     */
    fun biomeAt(state: UIState?, x: Int, z: Int): ResourceKey<Biome>? {
        val level = Minecraft.getInstance().level ?: return null
        val dimension = state?.dimension?.identifier()?.toString() ?: return null
        if (dimension != level.dimension().identifier().toString()) return null
        val layer = layerOf(state)
        grids[Area(dimension, Math.floorDiv(x, REGION), Math.floorDiv(z, REGION), layer)]?.let { grid ->
            val cell = grid.cells[cellIndex(Math.floorMod(x, REGION) / 4, Math.floorMod(z, REGION) / 4)].toInt()
            if (cell >= 0) return grid.palette[cell]
        }
        return try {
            val snapshot = storage()?.read(ChunkPos(x shr 4, z shr 4), level) ?: return null
            biomeIn(snapshot, x and 15, z and 15, layer)
        } catch (e: Exception) {
            null
        }
    }

    /** When JourneyMap last wrote [area]'s cache file, or 0 if it has none. */
    private fun fileTime(folder: Path, area: Area): Long = try {
        Files.getLastModifiedTime(folder.resolve("c.${area.x}.${area.z}.jmc")).toMillis()
    } catch (e: Exception) {
        0L
    }

    /** Off the client thread: [area]'s grid, from JourneyMap's chunk cache. */
    private fun read(area: Area, level: Level) {
        val storage = storage() ?: return
        val folder = JourneyMapFiles.chunkCacheFolder(area.dimension) ?: return
        reading.add(area)
        val generation = generation
        worker.execute {
            val grid = try {
                // The file's date first: a write while this reads is caught by the next check.
                readGrid(storage, level, area, fileTime(folder, area))
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

    private fun readGrid(storage: JMChunkStorage, level: Level, area: Area, fileTime: Long): Grid {
        val palette = ArrayList<ResourceKey<Biome>>()
        val indexOf = HashMap<ResourceKey<Biome>, Int>()
        val cells = ShortArray(CELLS * CELLS) { -1 }
        for (chunkZ in 0 until CHUNKS) for (chunkX in 0 until CHUNKS) {
            val snapshot = storage.read(ChunkPos(area.x * CHUNKS + chunkX, area.z * CHUNKS + chunkZ), level) ?: continue
            for (quarterZ in 0 until 4) for (quarterX in 0 until 4) {
                val biome = biomeIn(snapshot, quarterX * 4 + 2, quarterZ * 4 + 2, area.layer) ?: continue
                val index = indexOf.getOrPut(biome) { palette.add(biome); palette.size - 1 }
                if (index > Short.MAX_VALUE) continue
                cells[cellIndex(chunkX * 4 + quarterX, chunkZ * 4 + quarterZ)] = index.toShort()
            }
        }
        return Grid(palette, cells, fileTime)
    }

    /** The biome at ([x], [z]) within [snapshot]'s chunk: at the top block, or the middle of cave slice [layer]. */
    private fun biomeIn(snapshot: JMChunkSnapshot, x: Int, z: Int, layer: Int?): ResourceKey<Biome>? {
        val y = layer?.let { it * 16 + 8 } ?: (snapshot.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1)
        if (y < snapshot.minBuildHeight || y >= snapshot.maxBuildHeight) return null
        return snapshot.getBiomeHolder(x, y, z)?.unwrapKey()?.orElse(null)
    }

    /** Whether this JourneyMap's chunk cache can be read, for `/mapexposer` and the hook check. */
    val available: Boolean get() = STORAGE != null

    /**
     * JourneyMap's chunk cache of the dimension you are in, or null while it has none (its chunk
     * caching switched off). Read as JourneyMap reads it, from any thread.
     */
    private fun storage(): JMChunkStorage? = try {
        STORAGE?.get(JMChunkCache.INSTANCE) as JMChunkStorage?
    } catch (e: Exception) {
        null
    }

    /** JourneyMap keeps the open cache in a private field; read through it, never written. */
    private val STORAGE = try {
        JMChunkCache::class.java.getDeclaredField("storage").apply { isAccessible = true }
    } catch (e: Exception) {
        null
    }

    /** Client thread: [area]'s tint on [ui], replacing the old one. */
    private fun show(api: IClientAPI, area: Area, ui: Context.UI, grid: Grid) {
        val key = area to ui
        remove(api, key)
        val colours = IntArray(grid.palette.size) { BiomePicks.colourOf(grid.palette[it]) ?: 0 }
        val picture = if (colours.all { it == 0 }) null else {
            val image = NativeImage(CELLS, CELLS, true)
            for (z in 0 until CELLS) for (x in 0 until CELLS) {
                val cell = grid.cells[cellIndex(x, z)].toInt()
                image.setPixel(x, z, if (cell >= 0) colours[cell] else 0)
            }
            val fullscreen = ui == Context.UI.Fullscreen
            RegionPicture.show(api, "biomes", area.dimension, area.x * REGION, area.z * REGION, 4, image, fullscreen, BIOME_ORDER)
                ?.also { if (!fullscreen) JourneyMapMinimap.changed() }
        }
        shown[key] = Tint(grid, BiomePicks.version, picture)
    }

    private fun remove(api: IClientAPI, key: Pair<Area, Context.UI>) {
        val gone = shown.remove(key)?.picture ?: return
        gone.remove(api)
        if (key.second == Context.UI.Minimap) JourneyMapMinimap.changed()
    }

    private fun clear(api: IClientAPI, ui: Context.UI) {
        shown.keys.filter { it.second == ui }.forEach { remove(api, it) }
    }

    /** The minimap's tint of [dimension], for [JourneyMapMinimap] to draw. */
    fun minimapPictures(dimension: String): List<RegionPicture> =
        shown.mapNotNull { (key, tint) -> tint.picture.takeIf { key.second == Context.UI.Minimap && key.first.dimension == dimension } }

    /** Leaving a world: JourneyMap drops our overlays ([JourneyMapAdapter.leave]); this frees the pictures and grids. */
    fun forget() {
        generation++
        shown.values.forEach { it.picture?.release() }
        shown.clear()
        grids.clear()
        reading.clear()
    }

    /** "tinting 2 biomes in 5 regions" for `/mapexposer`, or nothing while none is picked. */
    fun summary(): String? {
        if (!BiomePicks.any) return null
        fun regionsOn(ui: Context.UI) = shown.count { it.key.second == ui && it.value.picture != null }
        val regions = regionsOn(Context.UI.Fullscreen)
        val biomes = BiomePicks.all.size
        return "tinting $biomes biome${if (biomes == 1) "" else "s"} in $regions region${if (regions == 1) "" else "s"}" +
            (if (note.isEmpty()) "" else " ($note)") + ", ${regionsOn(Context.UI.Minimap)} on the minimap"
    }

    private fun cellIndex(x: Int, z: Int) = z * CELLS + x

    private const val REGION = 512
    private const val CHUNKS = REGION / 16
    private const val CELLS = REGION / 4

    /** About 32 KB each. */
    private const val MAX_GRIDS = 256
    private const val MAX_SHOWN = 64
    private const val PER_TICK = 8

    /** How often the cache files of the regions on screen are looked at for changes. */
    private const val RECHECK_MS = 5_000L
    private const val KEEP_CLOSED_MS = 30_000L

    /** Over BlueMap's terrain ([JourneyMapTerrain]), under the outlines and pins ([JourneyMapPins]). */
    private const val BIOME_ORDER = 75
}
