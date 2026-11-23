package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.SafeFiles
import com.skystormer.skysmapexposer.SharedTerrain
import com.skystormer.skysmapexposer.levelKey
import com.skystormer.skysmapexposer.packXZ
import com.skystormer.skysmapexposer.journeymap.JourneyMapCacheWrites.Kind
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.common.Context
import journeymap.client.cartography.render.BaseRenderer
import journeymap.client.cartography.render.BiomeRenderer
import journeymap.client.cartography.render.SurfaceRenderer
import journeymap.client.cartography.render.TopoRenderer
import journeymap.client.data.DataCache
import journeymap.client.io.FileHandler
import journeymap.client.model.chunk.ChunkMD
import journeymap.client.model.chunk.ChunkRenderContext
import journeymap.client.model.chunk.SnapshotChunkMD
import journeymap.client.model.map.MapType
import journeymap.client.model.region.RegionCoord
import journeymap.client.model.region.RegionImageCache
import journeymap.common.chunk.JMChunkCache
import journeymap.common.chunk.JMChunkSnapshot
import journeymap.common.chunk.JMChunkStorage
import net.minecraft.client.Minecraft
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/**
 * JourneyMap's night, topo and biome maps of the downloaded and shared areas. A download writes
 * BlueMap's ground into JourneyMap's day map, and every column of it into JourneyMap's chunk cache
 * ([JourneyMapCacheWrites]); JourneyMap draws its other maps only of chunks it sees itself, so they
 * stayed empty there. Here JourneyMap's own renderers draw them from those cached chunks, as it
 * draws them from the chunks it sees: the same colours, contour lines and shading.
 *
 * Only into a chunk's picture where it is empty, never over anything JourneyMap drew; and which
 * were drawn is kept (one byte per chunk in a file per region), so that once a download is undone
 * ([JourneyMapCacheWrites.Kind.WITHDRAWN]) exactly those are wiped again. Only the map you are
 * looking at is drawn (night, topo or biome, on the full-screen map or the minimap): the regions on
 * screen first, nearest first, then the rest of JourneyMap's cache, a few at a time off the client thread ([WORKERS], each with renderers of
 * its own), of the dimension the map shows: yours, or one toggled to, drawn as that dimension ([JourneyMapOtherWorld]).
 *
 * Hooks into JourneyMap's inner classes: if they change, this stops with a line in the log, and
 * those maps stay as JourneyMap leaves them.
 */
internal object JourneyMapOtherMaps {

    /** False once JourneyMap's classes turned out not to be as expected. */
    @Volatile
    private var working = true

    /** Region ([regionX], [regionZ]) of [dimension] to draw into the maps [wanted] (bits of [NIGHT], [TOPO], [BIOME]). */
    private class Job(val dimension: String, val regionX: Int, val regionZ: Int, val wanted: Int, val drawnFolder: Path)

    /** Which maps each region has been done for since it last changed, by dimension. */
    private val done = ConcurrentHashMap<String, ConcurrentHashMap<Long, Int>>()

    /** Regions being drawn. */
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    private val worker by lazy {
        Executors.newFixedThreadPool(WORKERS) { Thread(it, "SkysMapExposer JourneyMap other maps").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    /** JourneyMap's renderers keep what they are drawing in themselves: one set for each worker. */
    private val renderers = ThreadLocal.withInitial { Renderers() }

    /** Saving and JourneyMap's texture updates, one worker at a time. */
    private val saving = Any()

    private class Renderers {
        val surface = SurfaceRenderer()
        val topo: BaseRenderer = TopoRenderer()
        val biome: BaseRenderer = BiomeRenderer()
    }

    /** Client thread, each tick: the next regions on screen to draw into the map shown, while a worker is free. */
    fun tick(api: IClientAPI) {
        if (!working || inFlight.size >= WORKERS) return
        val level = Minecraft.getInstance().level ?: return
        val server = JourneyMapEdits.serverFolder() ?: return
        for (ui in UIS) {
            val state = api.getUIState(ui) ?: continue
            if (!state.active) continue
            val wanted = when (state.mapType) {
                Context.MapType.Night -> NIGHT
                Context.MapType.Topo -> TOPO
                Context.MapType.Biome -> BIOME
                else -> continue
            }
            // The dimension the map shows, yours or one toggled to ([JourneyMapOtherWorld]).
            val here = state.dimension?.identifier()?.toString() ?: continue
            if (here != level.dimension().identifier().toString() && JourneyMapOtherWorld.typeOf(here, level.registryAccess()) == null) continue
            val drawnFolder = server.resolve("journeymap-drawn").resolve(SafeFiles.name(here))
            val doneHere = done.getOrPut(here) { ConcurrentHashMap() }
            val screen = ScreenSquares(ScreenSquares.areaOf(ui, state) ?: continue, JourneyMapFiles.REGION)
            fun needed(x: Int, z: Int) = packXZ(x, z).let { (doneHere[it] ?: 0) and wanted != wanted && it !in inFlight }
            // The regions on screen first, nearest first; once they are done, every other region of
            // JourneyMap's cache in this dimension, so the whole map fills without panning around it.
            val onScreen = cacheRegions(here).filter { (x, z) -> screen.contains(x, z) }
            val waiting = (onScreen.filter { (x, z) -> needed(x, z) }.ifEmpty { cacheRegions(here).filter { (x, z) -> needed(x, z) } })
                .sortedBy { (x, z) -> screen.distance(x, z) }
            for ((x, z) in waiting) {
                if (inFlight.size >= WORKERS) return
                val region = packXZ(x, z)
                inFlight.add(region)
                val job = Job(here, x, z, wanted, drawnFolder)
                worker.execute {
                    try {
                        run(job)
                    } catch (e: Throwable) {
                        stop("drawing region ${job.regionX}, ${job.regionZ}", e)
                    } finally {
                        doneHere.merge(region, wanted, Int::or)
                        inFlight.remove(region)
                    }
                }
            }
        }
    }

    /** The regions of [dimension] JourneyMap has a chunk cache file for, looked up again now and then. Client thread. */
    private fun cacheRegions(dimension: String): Set<Pair<Int, Int>> {
        val now = System.currentTimeMillis()
        val known = regionList
        if (known != null && known.first == dimension && now - listedAt < LIST_MS) return known.second
        val folder = JourneyMapFiles.chunkCacheFolder(dimension) ?: return emptySet()
        val regions = SharedTerrain.regionFiles(folder, JourneyMapChunkCache.FILE_NAME)
        regionList = dimension to regions
        listedAt = now
        return regions
    }

    private var regionList: Pair<String, Set<Pair<Int, Int>>>? = null
    private var listedAt = 0L

    /** Region ([regionX], [regionZ]) of [dimension] changed in the cache (a download, a shared map, an undo): done again when next on screen. Any thread. */
    fun changed(dimension: String, regionX: Int, regionZ: Int) {
        done[dimension]?.remove(packXZ(regionX, regionZ))
    }

    /** All of [dimension] done again when next on screen. Any thread. */
    fun changed(dimension: String) {
        done.remove(dimension)
    }

    fun forget() {
        done.clear()
        regionList = null
    }

    private fun stop(why: String, e: Throwable) {
        if (!working) return
        working = false
        Log.warn("JourneyMap's night, topo and biome maps will not be drawn of downloaded and shared areas ({}): {}", why, e.toString())
    }

    /** Off the client thread: one region's chunks drawn into (or wiped from) the other maps. */
    private fun run(job: Job) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        // A dimension you are not in is drawn as that dimension ([JourneyMapOtherWorld]), read from its own cache files.
        val otherType = if (level.dimension().identifier().toString() == job.dimension) null
        else JourneyMapOtherWorld.typeOf(job.dimension, level.registryAccess()) ?: return
        val kinds = JourneyMapCacheWrites.kindsIn(job.dimension, job.regionX, job.regionZ) ?: return
        val drawnFile = job.drawnFolder.resolve("${job.regionX},${job.regionZ}.drawn")
        val drawn = try {
            if (Files.isRegularFile(drawnFile)) Files.readAllBytes(drawnFile).takeIf { it.size == CHUNKS } ?: ByteArray(CHUNKS) else ByteArray(CHUNKS)
        } catch (e: Exception) {
            ByteArray(CHUNKS)
        }
        val before = drawn.copyOf()
        val key = levelKey(job.dimension)
        // No night map where there is no sky to have one, as JourneyMap draws none of the Nether.
        val types = buildList {
            if (if (otherType != null) otherType.hasSkyLight() && !otherType.hasCeiling() else key != Level.NETHER) add(NIGHT to MapType.night(key))
            add(TOPO to MapType.topo(key))
            add(BIOME to MapType.biome(key))
        }
        // Only the maps wanted just now; the others when they are shown.
        val all = types.fold(0) { bits, (bit, _) -> bits or bit } and job.wanted
        val toDraw = ArrayList<Int>()
        val toWipe = ArrayList<Int>()
        for (i in 0 until CHUNKS) when (kinds[i]) {
            Kind.ADDED -> if (all != 0 && drawn[i].toInt() and all != all) toDraw.add(i)
            Kind.WITHDRAWN -> if (drawn[i].toInt() != 0) toWipe.add(i)
            // Mapped by JourneyMap since: it drew all its maps of it, over whatever was there.
            else -> drawn[i] = 0
        }
        if (toDraw.isNotEmpty() || toWipe.isNotEmpty()) {
            val world = FileHandler.getJMWorldDir(minecraft) ?: return
            val day = MapType.day(key)
            val snapshots = arrayOfNulls<JMChunkSnapshot>(CHUNKS)
            val wanted = toDraw + toWipe
            fun posOf(i: Int) = ChunkPos(job.regionX * 32 + (i and 31), job.regionZ * 32 + (i shr 5))
            if (otherType == null) {
                // Read through JourneyMap's cache, which loads off its own threads: all asked for at once, then waited for.
                val loaded = CountDownLatch(wanted.size)
                for (i in wanted) {
                    JMChunkCache.INSTANCE.supplyAsync(posOf(i), level) {
                        snapshots[i] = it
                        loaded.countDown()
                    }
                }
                // One that fails is never answered (JourneyMap logs it): after a while, the rest go ahead.
                loaded.await(LOAD_WAIT_S, TimeUnit.SECONDS)
            } else {
                // Another dimension's files, which JourneyMap does not have open: read here, at that dimension's height.
                val folder = JourneyMapFiles.chunkCacheFolder(job.dimension) ?: return
                JMChunkStorage(folder).use { storage ->
                    JourneyMapOtherWorld.with(otherType, emptyMap()) { for (i in wanted) snapshots[i] = storage.read(posOf(i), level) }
                }
            }
            val mds = HashMap<Int, ChunkMD>()
            fun md(i: Int): ChunkMD? = mds[i] ?: snapshots[i]?.let { snapshot ->
                (if (otherType == null) SnapshotChunkMD(level, snapshot, true) else JourneyMapOtherWorld.OtherChunk(level, snapshot, key, otherType)).also { mds[i] = it }
            }
            // Its neighbours in reach of the renderers, as JourneyMap's own tasks hold theirs, for the slopes at
            // the edges: in its cache for your dimension, in their own list for another ([JourneyMapOtherWorld.neighbour]).
            val held = toDraw.mapNotNull { md(it) }
            if (otherType == null) held.forEach(DataCache.INSTANCE::addChunkMD)
            var anyDrawn = 0
            fun drawAll() {
                for (i in toDraw) {
                    val md = md(i) ?: continue
                    val coord = RegionCoord.fromChunkPos(world, day, md.coord.x(), md.coord.z())
                    val set = RegionImageCache.INSTANCE.getRegionImageSet(coord)
                    ChunkRenderContext.instance().begin(md)
                    try {
                        for ((bit, type) in types) {
                            if (bit and all == 0 || drawn[i].toInt() and bit != 0) continue
                            val image = set.getChunkImage(md, type) ?: continue
                            image.use {
                                if (!empty(it)) return@use
                                val ok = when (bit) {
                                    NIGHT -> set.getChunkImage(md, day)?.use { scratch -> renderers.get().surface.render(scratch, it, md) } ?: false
                                    TOPO -> renderers.get().topo.render(it, md, null)
                                    else -> renderers.get().biome.render(it, md, null)
                                }
                                if (ok) {
                                    set.setChunkImage(md, type, it)
                                    drawn[i] = (drawn[i].toInt() or bit).toByte()
                                    anyDrawn = anyDrawn or bit
                                }
                            }
                        }
                    } finally {
                        ChunkRenderContext.instance().end()
                    }
                }
                for (i in toWipe) {
                    val md = md(i) ?: continue
                    val coord = RegionCoord.fromChunkPos(world, day, md.coord.x(), md.coord.z())
                    val set = RegionImageCache.INSTANCE.getRegionImageSet(coord)
                    for ((bit, type) in types) {
                        if (drawn[i].toInt() and bit == 0) continue
                        set.getChunkImage(md, type)?.use {
                            for (z in 0 until 16) for (x in 0 until 16) it.setPixel(x, z, 0)
                            set.setChunkImage(md, type, it)
                        }
                    }
                    drawn[i] = 0
                }
            }
            try {
                if (otherType == null) drawAll()
                else JourneyMapOtherWorld.with(otherType, held.associateBy { it.coord.pack() }) { drawAll() }
            } finally {
                if (otherType == null) held.forEach(DataCache.INSTANCE::removeChunkMD)
            }
            if (anyDrawn != 0 || toWipe.isNotEmpty()) synchronized(saving) {
                // Shown at once.
                RegionImageCache.INSTANCE.updateTextures(false, true)
                // Saved straight away, and only then JourneyMap's zoomed-out copies made again: it makes
                // them from the files, so made from the old file they showed the region empty zoomed out
                // and the new one close up, flickering between the two until it saved on its own.
                RegionImageCache.INSTANCE.getRegionImageSet(RegionCoord.fromRegionPos(world, job.regionX, job.regionZ, key)).writeToDiskAsync(true)
                RegionImageCache.INSTANCE.awaitPendingWrites(WRITE_WAIT_MS)
                val dimensionFolder = JourneyMapFiles.dimensionFolder(job.dimension)
                for ((bit, type) in types) {
                    if (anyDrawn and bit == 0 && toWipe.isEmpty()) continue
                    val folder = dimensionFolder?.resolve(FOLDERS.getValue(bit)) ?: continue
                    JourneyMapFiles.written(type, folder, job.regionX, job.regionZ, exists = true)
                }
            }
        }
        if (!drawn.contentEquals(before)) {
            if (drawn.all { it.toInt() == 0 }) Files.deleteIfExists(drawnFile)
            else SafeFiles.write(drawnFile, drawn)
        }
    }

    private fun empty(image: com.mojang.blaze3d.platform.NativeImage): Boolean {
        for (z in 0 until image.height) for (x in 0 until image.width) if (image.getPixel(x, z) ushr 24 != 0) return false
        return true
    }

    private val UIS = arrayOf(Context.UI.Fullscreen, Context.UI.Minimap)

    private const val CHUNKS = 32 * 32
    private val WORKERS = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)
    /** Each map's folder in JourneyMap's dimension folder. */
    private val FOLDERS = mapOf(NIGHT to "night", TOPO to "topo", BIOME to "biome")
    private const val WRITE_WAIT_MS = 10_000L
    private const val LOAD_WAIT_S = 30L
    private const val NIGHT = 1
    private const val TOPO = 2
    private const val BIOME = 4

    /** How often the list of JourneyMap's cache files is read again. */
    private const val LIST_MS = 10_000L
}
