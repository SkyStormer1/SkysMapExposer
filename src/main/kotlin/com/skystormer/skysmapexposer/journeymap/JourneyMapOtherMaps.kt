package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.SafeFiles
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
import journeymap.client.model.region.LodChainRegistry
import journeymap.client.model.region.RegionCoord
import journeymap.client.model.region.RegionImageCache
import journeymap.common.chunk.JMChunkCache
import journeymap.common.chunk.JMChunkSnapshot
import net.minecraft.client.Minecraft
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JourneyMap's night, topo and biome maps of the downloaded and shared areas. A download writes
 * BlueMap's ground into JourneyMap's day map, and every column of it into JourneyMap's chunk cache
 * ([JourneyMapCacheWrites]); JourneyMap draws its other maps only of chunks it sees itself, so they
 * stayed empty there. Here JourneyMap's own renderers draw them from those cached chunks, as it
 * draws them from the chunks it sees: the same colours, contour lines and shading.
 *
 * Only into a chunk's picture where it is empty, never over anything JourneyMap drew; and which
 * were drawn is kept (one byte per chunk in a file per region), so that once a download is undone
 * ([JourneyMapCacheWrites.Kind.WITHDRAWN]) exactly those are wiped again. Regions are done as they
 * come on screen on either map, one at a time off the client thread, in the dimension you are in
 * (JourneyMap's renderers draw only of the world it has open).
 *
 * Hooks into JourneyMap's inner classes: if they change, this stops with a line in the log, and
 * those maps stay as JourneyMap leaves them.
 */
internal object JourneyMapOtherMaps {

    /** False once JourneyMap's classes turned out not to be as expected. */
    @Volatile
    private var working = true

    private class Job(val dimension: String, val regionX: Int, val regionZ: Int, val drawnFolder: Path)

    /** Regions done since they last changed, by dimension. */
    private val done = ConcurrentHashMap<String, MutableSet<Long>>()
    private val busy = AtomicBoolean()

    private val worker by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "SkysMapExposer JourneyMap other maps").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    private val renderers by lazy { Renderers() }

    private class Renderers {
        val surface = SurfaceRenderer()
        val topo: BaseRenderer = TopoRenderer()
        val biome: BaseRenderer = BiomeRenderer()
    }

    /** Client thread, each tick: the next region on screen to do, if none is being done. */
    fun tick(api: IClientAPI) {
        if (!working || busy.get()) return
        val level = Minecraft.getInstance().level ?: return
        val here = level.dimension().identifier().toString()
        val drawnFolder = JourneyMapEdits.serverFolder()?.resolve("journeymap-drawn")?.resolve(SafeFiles.name(here)) ?: return
        val doneHere = done.getOrPut(here) { ConcurrentHashMap.newKeySet() }
        for (ui in UIS) {
            val state = api.getUIState(ui) ?: continue
            if (!state.active || state.mapType == Context.MapType.Underground) continue
            if (state.dimension?.identifier()?.toString() != here) continue
            val bounds = (if (ui == Context.UI.Fullscreen) JourneyMapMenus.shownArea(state) else JourneyMapMinimap.area) ?: continue
            val left = Math.floorDiv(bounds[0].toInt(), JourneyMapFiles.REGION)
            val top = Math.floorDiv(bounds[1].toInt(), JourneyMapFiles.REGION)
            val right = Math.floorDiv(bounds[2].toInt(), JourneyMapFiles.REGION)
            val bottom = Math.floorDiv(bounds[3].toInt(), JourneyMapFiles.REGION)
            if ((right - left + 1).toLong() * (bottom - top + 1) > MAX_ON_SCREEN) continue
            val centreX = (left + right) / 2.0
            val centreZ = (top + bottom) / 2.0
            var best: Pair<Int, Int>? = null
            var bestDistance = Double.MAX_VALUE
            for (z in top..bottom) for (x in left..right) {
                if (packXZ(x, z) in doneHere) continue
                val d = (x - centreX) * (x - centreX) + (z - centreZ) * (z - centreZ)
                if (d < bestDistance) { bestDistance = d; best = x to z }
            }
            val (x, z) = best ?: continue
            busy.set(true)
            val job = Job(here, x, z, drawnFolder)
            worker.execute {
                try {
                    run(job)
                } catch (e: Throwable) {
                    stop("drawing region ${job.regionX}, ${job.regionZ}", e)
                } finally {
                    doneHere.add(packXZ(x, z))
                    busy.set(false)
                }
            }
            return
        }
    }

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
        // Through a portal since it was handed over: JourneyMap's renderers draw only of the world it has open.
        if (level.dimension().identifier().toString() != job.dimension) return
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
            if (key != Level.NETHER) add(NIGHT to MapType.night(key))
            add(TOPO to MapType.topo(key))
            add(BIOME to MapType.biome(key))
        }
        val all = types.fold(0) { bits, (bit, _) -> bits or bit }
        val toDraw = ArrayList<Int>()
        val toWipe = ArrayList<Int>()
        for (i in 0 until CHUNKS) when (kinds[i]) {
            Kind.ADDED -> if (drawn[i].toInt() and all != all) toDraw.add(i)
            Kind.WITHDRAWN -> if (drawn[i].toInt() != 0) toWipe.add(i)
            // Mapped by JourneyMap since: it drew all its maps of it, over whatever was there.
            else -> drawn[i] = 0
        }
        if (toDraw.isNotEmpty() || toWipe.isNotEmpty()) {
            val world = FileHandler.getJMWorldDir(minecraft) ?: return
            val day = MapType.day(key)
            // Read through JourneyMap's cache, which loads off its own threads: all asked for at once, then waited for.
            val snapshots = arrayOfNulls<JMChunkSnapshot>(CHUNKS)
            val wanted = toDraw + toWipe
            val loaded = CountDownLatch(wanted.size)
            for (i in wanted) {
                JMChunkCache.INSTANCE.supplyAsync(ChunkPos(job.regionX * 32 + (i and 31), job.regionZ * 32 + (i shr 5)), level) {
                    snapshots[i] = it
                    loaded.countDown()
                }
            }
            // One that fails is never answered (JourneyMap logs it): after a while, the rest go ahead.
            loaded.await(LOAD_WAIT_S, TimeUnit.SECONDS)
            val mds = HashMap<Int, ChunkMD>()
            fun md(i: Int): ChunkMD? = mds[i] ?: snapshots[i]?.let { snapshot -> SnapshotChunkMD(level, snapshot, true).also { mds[i] = it } }
            // Its neighbours in reach of the renderers, as JourneyMap's own tasks hold theirs, for the slopes at the edges.
            val held = toDraw.mapNotNull { md(it) }
            held.forEach(DataCache.INSTANCE::addChunkMD)
            var anyDrawn = 0
            try {
                for (i in toDraw) {
                    val md = md(i) ?: continue
                    val coord = RegionCoord.fromChunkPos(world, day, md.coord.x(), md.coord.z())
                    val set = RegionImageCache.INSTANCE.getRegionImageSet(coord)
                    ChunkRenderContext.instance().begin(md)
                    try {
                        for ((bit, type) in types) {
                            if (drawn[i].toInt() and bit != 0) continue
                            val image = set.getChunkImage(md, type) ?: continue
                            image.use {
                                if (!empty(it)) return@use
                                val ok = when (bit) {
                                    NIGHT -> set.getChunkImage(md, day)?.use { scratch -> renderers.surface.render(scratch, it, md) } ?: false
                                    TOPO -> renderers.topo.render(it, md, null)
                                    else -> renderers.biome.render(it, md, null)
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
            } finally {
                held.forEach(DataCache.INSTANCE::removeChunkMD)
            }
            if (anyDrawn != 0 || toWipe.isNotEmpty()) {
                // Shown at once, and saved by JourneyMap with its own regions.
                RegionImageCache.INSTANCE.updateTextures(false, true)
                // A region new to one of these maps goes on its list of files to draw zoomed out.
                for ((bit, type) in types) if (anyDrawn and bit != 0) LodChainRegistry.INSTANCE.registerRegion(type, job.regionX, job.regionZ)
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
    private const val LOAD_WAIT_S = 30L
    private const val NIGHT = 1
    private const val TOPO = 2
    private const val BIOME = 4

    /** Further out than this many regions on screen, nothing is drawn until you zoom in. */
    private const val MAX_ON_SCREEN = 256
}
