package com.skystormer.skysmapexposer.xaero

import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.SharedTerrain
import com.skystormer.skysmapexposer.ShareFormat.Area
import com.skystormer.skysmapexposer.ShareFormat.Header
import com.skystormer.skysmapexposer.ShareFormat.MAX_SIDE
import com.skystormer.skysmapexposer.ShareFormat.Source
import com.skystormer.skysmapexposer.ShareFormat.Step
import com.skystormer.skysmapexposer.ShareFormat.TERRAIN
import com.skystormer.skysmapexposer.ShareFormat.pack
import com.skystormer.skysmapexposer.ShareFormat.varint
import com.skystormer.skysmapexposer.ShareFormat.zigzag
import com.skystormer.skysmapexposer.mixin.MapPixelAccess
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ThreadLocalRandom
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import xaero.map.MapProcessor
import xaero.map.WorldMapSession
import xaero.map.region.MapBlock
import xaero.map.region.MapRegion
import xaero.map.region.Overlay
import xaero.map.world.MapDimension

/**
 * Xaero's map terrain in [ShareFormat]'s files: read out of your map a region at a time
 * ([RegionReader]), and read back in to be written into it ([readTerrain], [SharedRegion]). Every
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

    /** Terrain: per chunk its position and 256 blocks (x * 16 + z), each as Xaero keeps it. */
    class TerrainBody(part: Area) : Collector(part) {
        private val states = LinkedHashMap<BlockState, Int>()
        private val biomes = LinkedHashMap<ResourceKey<Biome>, Int>()
        private val body = ByteArrayOutputStream()
        private val data = DataOutputStream(body)

        override fun chunk(cx: Int, cz: Int, blocks: Array<MapBlock?>) {
            data.writeInt(cx)
            data.writeInt(cz)
            for (block in blocks) {
                block!!
                val state = block.state ?: Blocks.AIR.defaultBlockState()
                varint(data, states.getOrPut(state) { states.size })
                data.writeShort(block.height)
                varint(data, zigzag(block.topHeight - block.height))
                varint(data, block.biome?.let { biomes.getOrPut(it) { biomes.size } + 1 } ?: 0)
                data.writeByte(lightAndGlow(block))
                val overlays = block.overlays.orEmpty().take(MAX_OVERLAYS)
                varint(data, overlays.size)
                for (overlay in overlays) {
                    varint(data, states.getOrPut(overlay.state ?: Blocks.AIR.defaultBlockState()) { states.size })
                    data.writeByte(lightAndGlow(overlay))
                    data.writeByte(overlay.opacity)
                }
            }
        }

        override fun pack(header: Header): ByteArray = pack(TERRAIN, header, chunks) { out ->
            varint(out, states.size)
            for (state in states.keys) out.writeUTF(BlockStateParser.serialize(state))
            varint(out, biomes.size)
            for (biome in biomes.keys) out.writeUTF(biome.identifier().toString())
            body.writeTo(out)
        }
    }

    /**
     * Reads the terrain of chunks [area] of the dimension the world map shows, a batch (one region)
     * at a time, as Xaero loads it, then packed. Only regions there is anything of are read: on
     * disk, or in Xaero's memory and not yet saved.
     */
    class RegionReader private constructor(
        private val from: String,
        private val dimension: MapDimension,
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
        override val progress get() = "batch ${minOf(batch + 1, batches)} of $batches"

        override fun next(): Step {
            if (batch >= parts.size) return Step.Done
            val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return Step.Waiting
            if (processor.mapWorld?.currentDimension !== dimension) {
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
            val deadline = System.nanoTime() + READ_BUDGET
            for ((rx, rz) in pending.take(REGIONS_AT_ONCE)) {
                val region = XaeroWriter.loaded(processor, folder, rx, rz, layer) ?: continue
                val next = synchronized(region) { collector.read(region, rx, rz, reading[rx to rz] ?: 0, deadline) }
                regionSince = now
                if (next >= 0) {
                    reading[rx to rz] = next
                    return Step.Waiting
                }
                reading.remove(rx to rz)
                pending.remove(rx to rz)
                XaeroWriter.release(region)
                if (System.nanoTime() > deadline) break
            }
            if (pending.isNotEmpty()) {
                if (now - regionSince > LOAD_WAIT) {
                    val (rx, rz) = pending.removeFirst()
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

        /** The regions of batch [index]: one. */
        fun regionsOf(index: Int): List<Pair<Int, Int>> = parts[index].second

        companion object {
            private const val REGIONS_AT_ONCE = 3
            private const val LOAD_WAIT = 30_000L
            /** Time spent reading your map per tick, so the game keeps its frame rate. */
            private const val READ_BUDGET = 4_000_000L

            /** A reader of chunks [left]..[right], [top]..[bottom] (inclusive), or why there cannot be one. */
            fun plan(from: String, left: Int, top: Int, right: Int, bottom: Int): Pair<RegionReader?, String?> {
                val area = Area(left, top, right, bottom)
                if (area.width > MAX_SIDE || area.height > MAX_SIDE) {
                    return null to "That is ${area.width} × ${area.height} chunks; pick at most $MAX_SIDE × $MAX_SIDE"
                }
                val (shown, why) = XaeroWriter.shown()
                shown ?: return null to why
                val processor = shown.processor
                val layer = shown.layer
                val folder = shown.folder
                val parts = ArrayList<Pair<Area, List<Pair<Int, Int>>>>()
                for (rz in (top shr 5)..(bottom shr 5)) {
                    for (rx in (left shr 5)..(right shr 5)) {
                        if (!Files.exists(folder.resolve(MapBackup.regionFile(rx, rz))) &&
                            processor.getLeafMapRegion(layer, rx, rz, false) == null
                        ) continue
                        val part = Area(
                            maxOf(left, rx * 32), maxOf(top, rz * 32),
                            minOf(right, rx * 32 + 31), minOf(bottom, rz * 32 + 31),
                        )
                        parts.add(part to listOf(rx to rz))
                    }
                }
                if (parts.isEmpty()) return null to "Nothing of your map there to share: you have not mapped those chunks"
                return RegionReader(from, shown.dimension, shown.dimensionId, folder, layer, area, parts) to null
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
