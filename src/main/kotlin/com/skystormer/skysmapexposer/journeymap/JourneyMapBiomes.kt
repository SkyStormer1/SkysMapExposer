package com.skystormer.skysmapexposer.journeymap

import com.mojang.blaze3d.platform.NativeImage
import com.skystormer.skysmapexposer.BiomePicks
import com.skystormer.skysmapexposer.Log
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.client.display.ImageOverlay
import journeymap.api.v2.client.model.MapImage
import journeymap.api.v2.client.util.UIState
import journeymap.api.v2.common.Context
import journeymap.common.chunk.JMChunkCache
import journeymap.common.chunk.JMChunkSnapshot
import journeymap.common.chunk.JMChunkStorage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.Heightmap
import java.util.concurrent.Executors

/**
 * Tints JourneyMap's full-screen map wherever one of the picked biomes ([BiomePicks]) is, as
 * Xaero's map is tinted ([com.skystormer.skysmapexposer.xaero.BiomeHighlight]).
 *
 * Xaero keeps the biome of every pixel of its map; JourneyMap does not, but it keeps the chunks it
 * has mapped in its chunk cache (`<world>/<dimension>/chunk_cache`), biomes included. Those are
 * read through JourneyMap's own reader, read-only and off the client thread, a region (32 by 32
 * chunks) at a time into a small grid of the biome every 4 blocks (the game keeps biomes no finer):
 * at the top block on the surface maps, at the slice shown on the cave map. JourneyMap keeps the
 * cache of the dimension you are in only, so that is the one tinted.
 *
 * Each region with something picked in it is one see-through picture overlay, a pixel per 4
 * blocks, over BlueMap's terrain and under the outlines and pins. Grids are kept for the regions
 * seen lately, and those around you are read again now and then, as JourneyMap maps more.
 */
internal object JourneyMapBiomes {

    /** A region of a dimension, at the surface ([layer] null) or a cave slice. */
    private data class Key(val dimension: String, val x: Int, val z: Int, val layer: Int?)

    /** A region's biomes, one cell per 4 blocks: an index into [palette], or -1 where JourneyMap has nothing. */
    private class Grid(val palette: List<ResourceKey<Biome>>, val cells: ShortArray, val readAt: Long)

    /** Lately read grids, least lately used first. Client thread only. */
    private val grids = object : LinkedHashMap<Key, Grid>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Grid>) = size > MAX_GRIDS
    }

    private class Shown(val overlay: ImageOverlay, val texture: Identifier, val grid: Grid, val version: Int)

    private val shown = HashMap<Key, Shown>()

    /** Regions being read; one at a time, nearest first, so panning gets what is on screen soon. */
    private val reading = HashSet<Key>()

    /** Bumped on leaving, so a grid read for the last world is thrown away when it arrives. */
    private var generation = 0

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Map Exposer JourneyMap biomes").apply { isDaemon = true } }

    private var lastActive = 0L

    /** Why nothing is being tinted just now, if anything stops it. */
    private var note = ""

    fun tick(api: IClientAPI) {
        val now = System.currentTimeMillis()
        val state = api.getUIState(Context.UI.Fullscreen)
        if (!BiomePicks.any || state == null || !state.active) {
            note = if (BiomePicks.any) "the full-screen map is closed" else ""
            // Kept while the map is closed for a moment; let go once it has stayed closed, or nothing is picked.
            if (shown.isNotEmpty() && (!BiomePicks.any || now - lastActive > KEEP_CLOSED_MS)) clear(api)
            return
        }
        lastActive = now
        val level = Minecraft.getInstance().level ?: return
        val dimension = state.dimension?.identifier()?.toString() ?: return
        val here = level.dimension().identifier().toString()
        if (dimension != here) {
            clear(api)
            note = "JourneyMap keeps biomes for the dimension you are in only"
            return
        }
        val layer = layerOf(state)
        val area = JourneyMapMenus.shownArea(state) ?: return
        val left = Math.floorDiv(area[0].toInt(), REGION)
        val top = Math.floorDiv(area[1].toInt(), REGION)
        val right = Math.floorDiv(area[2].toInt(), REGION)
        val bottom = Math.floorDiv(area[3].toInt(), REGION)
        val centreX = (left + right) / 2.0
        val centreZ = (top + bottom) / 2.0
        val wanted = buildList {
            for (z in top..bottom) for (x in left..right) add(Key(dimension, x, z, layer))
        }.sortedBy { (it.x - centreX) * (it.x - centreX) + (it.z - centreZ) * (it.z - centreZ) }.take(MAX_SHOWN)
        note = if ((right - left + 1) * (bottom - top + 1) > MAX_SHOWN) "zoomed too far out to tint it all" else ""
        val wantedSet = wanted.toHashSet()
        shown.keys.filter { it !in wantedSet }.forEach { remove(api, it) }

        val player = Minecraft.getInstance().player
        val playerX = player?.let { Math.floorDiv(it.blockX, REGION) }
        val playerZ = player?.let { Math.floorDiv(it.blockZ, REGION) }
        var built = 0
        for (key in wanted) {
            val grid = grids[key]
            // Around you JourneyMap is still mapping, so what it has there is read again now and then.
            val nearYou = playerX != null && playerZ != null && Math.abs(key.x - playerX) <= 1 && Math.abs(key.z - playerZ) <= 1
            val due = grid == null || (nearYou && now - grid.readAt > REREAD_MS)
            if (due && reading.isEmpty()) read(key, level)
            if (grid == null || built >= PER_TICK) continue
            val old = shown[key]
            if (old != null && old.grid === grid && old.version == BiomePicks.version) continue
            show(api, key, grid)
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
        val layer = state.let(::layerOf)
        val key = Key(dimension, Math.floorDiv(x, REGION), Math.floorDiv(z, REGION), layer)
        grids[key]?.let { grid ->
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

    /** Off the client thread: [key]'s grid, from JourneyMap's chunk cache. */
    private fun read(key: Key, level: Level) {
        val storage = storage() ?: return run { note = "JourneyMap's chunk cache is off (its Advanced options)" }
        reading.add(key)
        val generation = generation
        worker.execute {
            val grid = try {
                readGrid(storage, level, key)
            } catch (e: Exception) {
                Log.warn("Could not read JourneyMap's biomes for region {}, {}: {}", key.x, key.z, e.toString())
                null
            }
            Minecraft.getInstance().execute {
                if (generation != this.generation) return@execute
                reading.remove(key)
                if (grid != null) grids[key] = grid
            }
        }
    }

    private fun readGrid(storage: JMChunkStorage, level: Level, key: Key): Grid {
        val palette = ArrayList<ResourceKey<Biome>>()
        val indexOf = HashMap<ResourceKey<Biome>, Int>()
        val cells = ShortArray(CELLS * CELLS) { -1 }
        for (chunkZ in 0 until CHUNKS) for (chunkX in 0 until CHUNKS) {
            val snapshot = storage.read(ChunkPos(key.x * CHUNKS + chunkX, key.z * CHUNKS + chunkZ), level) ?: continue
            for (quarterZ in 0 until 4) for (quarterX in 0 until 4) {
                val biome = biomeIn(snapshot, quarterX * 4 + 2, quarterZ * 4 + 2, key.layer) ?: continue
                val index = indexOf.getOrPut(biome) { palette.add(biome); palette.size - 1 }
                if (index > Short.MAX_VALUE) continue
                cells[cellIndex(chunkX * 4 + quarterX, chunkZ * 4 + quarterZ)] = index.toShort()
            }
        }
        return Grid(palette, cells, System.currentTimeMillis())
    }

    /** The biome at ([x], [z]) within [snapshot]'s chunk: at the top block, or the middle of cave slice [layer]. */
    private fun biomeIn(snapshot: JMChunkSnapshot, x: Int, z: Int, layer: Int?): ResourceKey<Biome>? {
        val y = layer?.let { it * 16 + 8 } ?: (snapshot.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1)
        if (y < snapshot.minBuildHeight || y >= snapshot.maxBuildHeight) return null
        return snapshot.getBiomeHolder(x, y, z)?.unwrapKey()?.orElse(null)
    }

    /**
     * JourneyMap's chunk cache of the dimension you are in, or null while it has none (the cache
     * switched off). Read as JourneyMap reads it, from any thread.
     */
    private fun storage(): JMChunkStorage? = try {
        STORAGE?.get(JMChunkCache.INSTANCE) as JMChunkStorage?
    } catch (e: Exception) {
        null
    }

    private val STORAGE = try {
        JMChunkCache::class.java.getDeclaredField("storage").apply { isAccessible = true }
    } catch (e: Exception) {
        Log.warn("This JourneyMap keeps its chunk cache somewhere else; biomes will not be tinted on it: {}", e.toString())
        null
    }

    /** Client thread: [key]'s picture of the picked biomes, replacing the old one; none if nothing picked is there. */
    private fun show(api: IClientAPI, key: Key, grid: Grid) {
        remove(api, key)
        val colours = IntArray(grid.palette.size) { BiomePicks.colourOf(grid.palette[it]) ?: 0 }
        if (colours.all { it == 0 }) {
            // Nothing picked here: remembered as shown, with no picture, so it is not looked at again until something changes.
            shown[key] = Shown(EMPTY, EMPTY_TEXTURE, grid, BiomePicks.version)
            return
        }
        val image = NativeImage(CELLS, CELLS, true)
        for (z in 0 until CELLS) for (x in 0 until CELLS) {
            val cell = grid.cells[cellIndex(x, z)].toInt()
            image.setPixel(x, z, if (cell >= 0) colours[cell] else 0)
        }
        val texture = Identifier.fromNamespaceAndPath("skysmapexposer", "journeymap_biomes/${nextTexture++}")
        Minecraft.getInstance().textureManager.register(texture, DynamicTexture({ "skysmapexposer biomes ${key.x},${key.z}" }, image))
        val overlay = ImageOverlay(
            JourneyMapAdapter.MOD_ID,
            BlockPos(key.x * REGION, 0, key.z * REGION),
            BlockPos(key.x * REGION + REGION, 0, key.z * REGION + REGION),
            MapImage(texture, CELLS, CELLS),
        )
        overlay.setDimension(ResourceKey.create(Registries.DIMENSION, Identifier.parse(key.dimension)))
        overlay.setDisplayOrder(BIOME_ORDER)
        overlay.setActiveUIs(Context.UI.Fullscreen)
        try {
            api.show(overlay)
            shown[key] = Shown(overlay, texture, grid, BiomePicks.version)
        } catch (e: Exception) {
            Minecraft.getInstance().textureManager.release(texture)
            Log.warn("JourneyMap would not show the biome tint of region {}, {}: {}", key.x, key.z, e.toString())
        }
    }

    private fun remove(api: IClientAPI, key: Key) {
        val gone = shown.remove(key) ?: return
        if (gone.overlay === EMPTY) return
        api.remove(gone.overlay)
        Minecraft.getInstance().textureManager.release(gone.texture)
    }

    private fun clear(api: IClientAPI) {
        shown.keys.toList().forEach { remove(api, it) }
    }

    /** Leaving a world: JourneyMap drops our overlays ([JourneyMapAdapter.leave]); this frees the pictures and grids. */
    fun forget() {
        generation++
        shown.values.filter { it.overlay !== EMPTY }.forEach { Minecraft.getInstance().textureManager.release(it.texture) }
        shown.clear()
        grids.clear()
        reading.clear()
    }

    /** "Tinting 2 biomes in 5 regions" for `/mapexposer`, or nothing while none is picked. */
    fun summary(): String? {
        if (!BiomePicks.any) return null
        val regions = shown.values.count { it.overlay !== EMPTY }
        val biomes = BiomePicks.all.size
        return "tinting $biomes biome${if (biomes == 1) "" else "s"} in $regions region${if (regions == 1) "" else "s"}" +
            if (note.isEmpty()) "" else " ($note)"
    }

    private fun cellIndex(x: Int, z: Int) = z * CELLS + x

    private var nextTexture = 0

    private const val REGION = 512
    private const val CHUNKS = REGION / 16
    private const val CELLS = REGION / 4

    /** About 32 KB each. */
    private const val MAX_GRIDS = 256
    private const val MAX_SHOWN = 64
    private const val PER_TICK = 8
    private const val REREAD_MS = 30_000L
    private const val KEEP_CLOSED_MS = 30_000L

    /** Over BlueMap's terrain ([JourneyMapTerrain]), under the outlines and pins ([JourneyMapPins]). */
    private const val BIOME_ORDER = 75

    private val EMPTY_TEXTURE = Identifier.fromNamespaceAndPath("skysmapexposer", "journeymap_biomes/none")
    private val EMPTY by lazy {
        ImageOverlay(JourneyMapAdapter.MOD_ID, BlockPos.ZERO, BlockPos.ZERO, MapImage(EMPTY_TEXTURE, 1, 1))
    }
}
