package com.skystormer.skysmapexposer.xaero

import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.SharedTerrain
import com.skystormer.skysmapexposer.ShareFormat.Area
import com.skystormer.skysmapexposer.ShareFormat.Header
import com.skystormer.skysmapexposer.ShareFormat.Source
import com.skystormer.skysmapexposer.ShareFormat.Step
import com.skystormer.skysmapexposer.ShareFormat.pack
import com.skystormer.skysmapexposer.mixin.MapPixelAccess
import java.nio.file.Path
import java.util.concurrent.ThreadLocalRandom
import net.minecraft.world.level.block.Blocks
import xaero.map.MapProcessor
import xaero.map.WorldMapSession
import xaero.map.region.MapBlock
import xaero.map.region.MapRegion
import xaero.map.region.Overlay

/**
 * Xaero's map terrain in [ShareFormat]'s files: read out of your map a region at a time
 * ([RegionReader]), and read back in to be written into it ([SharedTerrain.readBatch], [SharedRegion]). Every
 * block as Xaero keeps it, so this part works with Xaero's World Map only.
 */
object TerrainFormat {

    private const val MAX_OVERLAYS = 16

    // ---- reading your map ----

    /** What one batch is read into, region by region, and packed from. */
    abstract class Collector(val part: Area) {
        var chunks = 0
        /** Chunks left out for being from before the season began. */
        var oldChunks = 0
        /** Season dates, so a map left from an old season is not shared; null to share everything. */
        var age: MapAge? = null
        private val blocks = arrayOfNulls<MapBlock>(256)

        /**
         * Reads what [part] has of region ([rx], [rz]), from its [from]th chunk on, until
         * [deadline] ([System.nanoTime]) has passed; the caller holds the region's lock. Returns
         * where to carry on next time, or -1 once the region is done. A whole region is too much
         * for one frame, so it is read a slice per tick.
         */
        fun read(region: MapRegion, rx: Int, rz: Int, from: Int = 0, deadline: Long = Long.MAX_VALUE): Int {
            val left = maxOf(part.left, rx * 32)
            val top = maxOf(part.top, rz * 32)
            val high = minOf(part.bottom, rz * 32 + 31) - top + 1
            val total = (minOf(part.right, rx * 32 + 31) - left + 1) * high
            var i = from
            while (i < total) {
                val cx = left + i / high
                val cz = top + i % high
                if (age?.isOld(cx, cz) == true) oldChunks++
                else if (readChunk(region, cx, cz, blocks)) {
                    chunk(cx, cz, blocks)
                    chunks++
                }
                if (++i < total && System.nanoTime() > deadline) return i
            }
            return -1
        }

        abstract fun chunk(cx: Int, cz: Int, blocks: Array<MapBlock?>)

        abstract fun pack(header: Header): ByteArray
    }

    /** Terrain: per chunk its position and 256 blocks (x * 16 + z), each as Xaero keeps it, through the one writer. */
    class TerrainBody(part: Area) : Collector(part) {
        private val writer = SharedTerrain.Writer()

        override fun chunk(cx: Int, cz: Int, blocks: Array<MapBlock?>) {
            val chunk = SharedTerrain.SharedChunk()
            for ((i, block) in blocks.withIndex()) {
                block!!
                chunk.state[i] = block.state
                chunk.height[i] = block.height.toShort()
                chunk.top[i] = block.topHeight.toShort()
                chunk.biome[i] = block.biome
                val bits = lightAndGlow(block)
                chunk.light[i] = (bits and 15).toByte()
                chunk.glowing[i] = bits and 16 != 0
                chunk.overlays[i] = block.overlays.orEmpty().take(MAX_OVERLAYS).map { overlay ->
                    val overlayBits = lightAndGlow(overlay)
                    SharedTerrain.SharedOverlay(overlay.state ?: Blocks.AIR.defaultBlockState(), (overlayBits and 15).toByte(), overlayBits and 16 != 0, overlay.opacity)
                }.ifEmpty { null }
            }
            writer.chunk(cx, cz, chunk)
        }

        override fun pack(header: Header): ByteArray = writer.pack(header)
    }

    /**
     * Reads the terrain of chunks [area] of the dimension the world map shows, a batch (one region)
     * at a time, as Xaero loads it, then packed. Only regions there is anything of are read: on
     * disk, or in Xaero's memory and not yet saved.
     */
    class RegionReader private constructor(
        private val from: String,
        val dimensionId: String,
        private val folder: Path,
        /** Xaero's cave layer the map was read from: the one it was showing. */
        val layer: Int,
        val area: Area,
        /** The part of [area] each batch covers, and its regions. */
        val parts: List<Pair<Area, List<Pair<Int, Int>>>>,
    ) : Source {
        val shareId = ThreadLocalRandom.current().nextInt(1 shl 30)
        override val label = "terrain"
        override val batches get() = parts.size
        override var count = 0
        var skippedRegions = 0
            private set
        /** Chunks left out for being from before the season began. */
        var oldChunks = 0
            private set
        private val age = MapAge.of(dimensionId, folder)
        private var batch = 0
        private val pending = ArrayDeque<Pair<Int, Int>>()
        private var collector: Collector? = null
        private var regionSince = 0L
        /** How far into a region it has read, for one it has not finished. */
        private val reading = HashMap<Pair<Int, Int>, Int>()
        /** Regions it has asked Xaero to load and not yet let go of ([XaeroWriter.release]). */
        private val asked = HashMap<Pair<Int, Int>, MapRegion>()
        /** How long one [next] may spend reading your map ([System.nanoTime]), so the game keeps its frame rate. */
        var budget = 2_000_000L
        override val progress get() = "region ${minOf(batch + 1, batches)} of $batches"

        override fun next(): Step {
            if (batch >= parts.size) return Step.Done
            val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return Step.Waiting
            if (processor.mapWorld?.currentDimension?.dimId?.identifier()?.toString() != dimensionId) {
                return Step.Failed("the world map switched dimension while your map was being read")
            }
            val (part, regions) = parts[batch]
            val collector = collector ?: run {
                pending.clear()
                pending.addAll(regions)
                regionSince = System.currentTimeMillis()
                TerrainBody(part).also { it.age = age; collector = it }
            }
            val now = System.currentTimeMillis()
            val deadline = System.nanoTime() + budget
            for ((rx, rz) in pending.take(REGIONS_AT_ONCE)) {
                val region = XaeroWriter.loaded(processor, folder, rx, rz, layer)
                if (region == null) {
                    processor.getLeafMapRegion(layer, rx, rz, false)?.let { asked[rx to rz] = it }
                    continue
                }
                asked[rx to rz] = region
                val next = synchronized(region) { collector.read(region, rx, rz, reading[rx to rz] ?: 0, deadline) }
                regionSince = now
                if (next >= 0) {
                    reading[rx to rz] = next
                    return Step.Waiting
                }
                reading.remove(rx to rz)
                pending.remove(rx to rz)
                asked.remove(rx to rz)
                XaeroWriter.release(region)
                if (System.nanoTime() > deadline) break
            }
            if (pending.isNotEmpty()) {
                if (now - regionSince > LOAD_WAIT) {
                    val (rx, rz) = pending.removeFirst()
                    asked.remove(rx to rz)?.let(XaeroWriter::release)
                    skippedRegions++
                    regionSince = now
                    Log.warn("Sharing: Xaero did not load region {}_{} within {} s; left it out", rx, rz, LOAD_WAIT / 1000)
                }
                return Step.Waiting
            }
            this.collector = null
            oldChunks += collector.oldChunks
            val index = batch++
            if (collector.chunks == 0) return if (batch >= parts.size) Step.Done else Step.Waiting
            count += collector.chunks
            val header = Header(from, dimensionId, shareId, index, parts.size, area)
            return Step.Ready({ collector.pack(header) }, index, collector.chunks)
        }

        /**
         * Lets go of every region it asked Xaero to load and has not read yet, when it stops early
         * (cancelled, failed, the map switched dimension): otherwise Xaero keeps their full data in
         * memory and saves them again for nothing. Client thread.
         */
        fun close() {
            asked.values.forEach(XaeroWriter::release)
            asked.clear()
        }

        /** The regions of batch [index]: one. */
        fun regionsOf(index: Int): List<Pair<Int, Int>> = parts[index].second

        companion object {
            private val REGION_FILE = Regex("""(-?\d+)_(-?\d+)\.zip""")

            private const val REGIONS_AT_ONCE = 3
            private const val LOAD_WAIT = 30_000L

            /**
             * A reader of the chunks of [wanted] (inclusive), or of every region Xaero has a file
             * for in the dimension and layer shown when null, or why there cannot be one.
             */
            fun plan(from: String, wanted: Area?): Pair<RegionReader?, String?> {
                val (shown, why) = XaeroWriter.shown()
                shown ?: return null to why
                val processor = shown.processor
                val layer = shown.layer
                val folder = shown.folder
                val files = SharedTerrain.regionFiles(folder, REGION_FILE)
                val regions = SharedTerrain.regionsIn(wanted, files) { x, z -> (x to z) in files || processor.getLeafMapRegion(layer, x, z, false) != null }
                val area = wanted ?: SharedTerrain.spanning(regions) ?: return null to "Nothing of your map to save yet: Xaero has not saved any of this dimension's map to disk; try again in a minute"
                val parts = regions.map { (rx, rz) ->
                    Area(
                        maxOf(area.left, rx * 32), maxOf(area.top, rz * 32),
                        minOf(area.right, rx * 32 + 31), minOf(area.bottom, rz * 32 + 31),
                    ) to listOf(rx to rz)
                }
                if (parts.isEmpty()) return null to "Nothing of your map there to save: you have not mapped those chunks"
                return RegionReader(from, shown.dimensionId, folder, layer, area, parts) to null
            }
        }
    }

    // ---- terrain, as it is written into your map ----

    /** A region of a shared map ([SharedTerrain]), as Xaero's blocks. */
    class SharedRegion(val terrain: SharedTerrain.Region) : XaeroWriter.Pixels {
        override val regionX get() = terrain.regionX
        override val regionZ get() = terrain.regionZ
        private val chunks get() = terrain.chunks

        override fun countInChunk(chunkInRegionX: Int, chunkInRegionZ: Int) =
            if (chunks[chunkInRegionZ * 32 + chunkInRegionX] != null) 256 else 0

        override fun block(processor: MapProcessor, px: Int, pz: Int, old: MapBlock?, tally: XaeroWriter.Tally): MapBlock? {
            val chunk = chunks[(pz shr 4) * 32 + (px shr 4)] ?: return null
            val i = (px and 15) * 16 + (pz and 15)
            val block = MapBlock()
            block.write(chunk.state[i], chunk.height[i].toInt(), chunk.top[i].toInt(), chunk.biome[i], chunk.light[i], chunk.glowing[i], false)
            chunk.overlays[i]?.forEach { o ->
                val overlay = Overlay(o.state, o.light, o.glowing)
                overlay.increaseOpacity(o.opacity)
                block.addOverlay(processor.overlayManager.getOriginal(overlay))
            }
            return block
        }
    }

    /** Fills [into] with chunk ([cx], [cz]) of [region] if Xaero has all of it; the caller holds the region's lock. */
    private fun readChunk(region: MapRegion, cx: Int, cz: Int, into: Array<MapBlock?>): Boolean {
        val tileChunk = region.getChunk((cx and 31) shr 2, (cz and 31) shr 2) ?: return false
        if (tileChunk.loadState != 2) return false
        val tile = tileChunk.getTile(cx and 3, cz and 3) ?: return false
        if (!tile.isLoaded) return false
        for (i in 0 until 256) into[i] = tile.getBlock(i shr 4, i and 15) ?: return false
        return true
    }

    private fun lightAndGlow(pixel: Any): Int {
        val access = pixel as MapPixelAccess
        return (access.`skysmapexposer$light`().toInt() and 15) or (if (access.`skysmapexposer$glowing`()) 16 else 0)
    }
}
