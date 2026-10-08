package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.mixin.MapPixelAccess
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet
import java.util.concurrent.ThreadLocalRandom
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * What a shared map is made of, however it travels — private messages ([ChatShare]) or a file
 * ([TerrainFiles]): your Xaero map read in batches ([RegionReader]), each batch packed as one
 * compressed block behind a CRC-32 and its length, so one changed or cut short on the way is turned
 * away rather than misread.
 *
 * A batch holds its [format], a [Header] (who, which dimension, which share, which batch of how
 * many, the whole area), the chunk count, then what the format carries:
 * - [BIOMES]: the biomes as one grid of 4×4-block cells, Minecraft's own resolution ([BiomeGrid]).
 * - [TERRAIN]: every block exactly as Xaero keeps it ([TerrainBody]).
 * - [WAYPOINTS]: waypoints, with their names, letters, colours and coordinates.
 */
object ShareFormat {

    const val TERRAIN = 4
    const val BIOMES = 5
    const val WAYPOINTS = 6

    /** Chunks per side, at most, of an area shared. */
    const val MAX_SIDE = 512
    const val MAX_WAYPOINTS = 1000
    private const val MAX_OVERLAYS = 16

    /** A chunk rectangle, inclusive. */
    class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1
        /** The block in the middle. */
        val x get() = (left + right + 1) * 8
        val z get() = (top + bottom + 1) * 8
    }

    /** What every batch starts with. */
    class Header(val from: String, val dimensionId: String, val shareId: Int, val batch: Int, val batches: Int, val area: Area)

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

    /**
     * Biomes as one grid of 4×4-block cells over the batch's area, row by row (0 = none, else
     * name + 1), after a bit per chunk saying which are there. Each cell takes the biome most of
     * its 16 blocks have. Neighbouring cells are nearly always the same biome, so the rows compress
     * to very little.
     */
    class BiomeGrid(part: Area) : Collector(part) {
        private val names = LinkedHashMap<ResourceKey<Biome>, Int>()
        private val cells = ShortArray(part.width * 4 * part.height * 4)
        private val present = BitSet(part.width * part.height)
        private val counts = HashMap<Int, Int>()

        override fun chunk(cx: Int, cz: Int, blocks: Array<MapBlock?>) {
            val gx = cx - part.left
            val gz = cz - part.top
            present.set(gz * part.width + gx)
            for (cell in 0 until 16) {
                counts.clear()
                for (dx in 0 until 4) {
                    for (dz in 0 until 4) {
                        val biome = blocks[((cell shr 2) * 4 + dx) * 16 + (cell and 3) * 4 + dz]!!.biome ?: continue
                        counts.merge(names.getOrPut(biome) { names.size } + 1, 1, Int::plus)
                    }
                }
                val value = counts.maxByOrNull { it.value }?.key ?: 0
                cells[(gz * 4 + (cell and 3)) * part.width * 4 + gx * 4 + (cell shr 2)] = value.toShort()
            }
        }

        override fun pack(header: Header): ByteArray = pack(BIOMES, header, chunks) { out ->
            writeArea(out, part)
            varint(out, names.size)
            for (name in names.keys) out.writeUTF(name.identifier().toString())
            val bits = present.toByteArray()
            varint(out, bits.size)
            out.write(bits)
            for (value in cells) varint(out, value.toInt())
        }
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

    /** One step of reading or sending a share. */
    sealed interface Step {
        /** Nothing yet; ask again next tick. */
        object Waiting : Step
        /**
         * Batch [batch], holding [count] chunks (or waypoints). Its [bytes] are packed when first
         * asked for, so a big batch can be packed away from the client thread.
         */
        class Ready(pack: () -> ByteArray, val batch: Int, val count: Int) : Step {
            val bytes: ByteArray by lazy(pack)
        }
        /** Nothing more. */
        object Done : Step
        class Failed(val message: String) : Step
    }

    /** Where batches to share come from, a tick at a time. */
    interface Source {
        /** "biomes", "terrain", "waypoints". */
        val label: String
        val batches: Int
        /** Chunks (or waypoints) in the batches handed out so far. */
        val count: Int
        /** What is going on, for the action bar while nothing is ready. */
        val progress: String
        fun next(): Step
    }

    /** Waypoints, all in one batch, packed when made. */
    class WaypointSource(private val bytes: ByteArray, private val waypoints: Int) : Source {
        override val label = "waypoints"
        override val batches = 1
        override var count = 0
        override val progress = ""
        private var given = false

        override fun next(): Step {
            if (given) return Step.Done
            given = true
            count = waypoints
            return Step.Ready({ bytes }, 0, waypoints)
        }
    }

    /**
     * Reads chunks [area] of the dimension the world map shows, a batch at a time: the regions of
     * each batch as Xaero loads them, a few per tick, then packed. A batch is [BIOME_BATCH] ×
     * [BIOME_BATCH] regions of biomes, or one region of terrain. Only regions there is anything of
     * are read: on disk, or in Xaero's memory and not yet saved.
     */
    class RegionReader private constructor(
        val terrain: Boolean,
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
        override val label = if (terrain) "terrain" else "biomes"
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
                (if (terrain) TerrainBody(part) else BiomeGrid(part)).also { it.age = age; collector = it }
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

        /** The regions of batch [index]; one, for terrain. */
        fun regionsOf(index: Int): List<Pair<Int, Int>> = parts[index].second

        companion object {
            /** Regions per side of a batch of biomes. */
            private const val BIOME_BATCH = 4
            private const val REGIONS_AT_ONCE = 3
            private const val LOAD_WAIT = 30_000L
            /** Time spent reading your map per tick, so the game keeps its frame rate. */
            private const val READ_BUDGET = 4_000_000L

            /** A reader of chunks [left]..[right], [top]..[bottom] (inclusive), or why there cannot be one. */
            fun plan(terrain: Boolean, from: String, left: Int, top: Int, right: Int, bottom: Int): Pair<RegionReader?, String?> {
                val area = Area(left, top, right, bottom)
                if (area.width > MAX_SIDE || area.height > MAX_SIDE) {
                    return null to "That is ${area.width} × ${area.height} chunks; pick at most $MAX_SIDE × $MAX_SIDE"
                }
                val (shown, why) = XaeroWriter.shown()
                shown ?: return null to why
                val processor = shown.processor
                val layer = shown.layer
                val folder = shown.folder
                val step = if (terrain) 1 else BIOME_BATCH
                val parts = ArrayList<Pair<Area, List<Pair<Int, Int>>>>()
                for (bz in (top shr 5)..(bottom shr 5) step step) {
                    for (bx in (left shr 5)..(right shr 5) step step) {
                        val regions = ArrayList<Pair<Int, Int>>()
                        for (rz in bz until minOf(bz + step, (bottom shr 5) + 1)) {
                            for (rx in bx until minOf(bx + step, (right shr 5) + 1)) {
                                if (Files.exists(folder.resolve(MapBackup.regionFile(rx, rz))) ||
                                    processor.getLeafMapRegion(layer, rx, rz, false) != null
                                ) regions.add(rx to rz)
                            }
                        }
                        if (regions.isEmpty()) continue
                        val part = Area(
                            maxOf(left, bx * 32), maxOf(top, bz * 32),
                            minOf(right, (bx + step) * 32 - 1), minOf(bottom, (bz + step) * 32 - 1),
                        )
                        parts.add(part to regions)
                    }
                }
                if (parts.isEmpty()) return null to "Nothing of your map there to share: you have not mapped those chunks"
                return RegionReader(terrain, from, shown.dimension, shown.dimensionId, folder, layer, area, parts) to null
            }
        }
    }

    // ---- waypoints ----

    class SharedWaypoint(val name: String, val initials: String, val x: Int, val y: Int, val z: Int, val yIncluded: Boolean, val color: Int)

    fun packWaypoints(header: Header, waypoints: List<SharedWaypoint>): ByteArray = pack(WAYPOINTS, header, waypoints.size) { out ->
        for (w in waypoints) {
            out.writeUTF(w.name)
            out.writeUTF(w.initials)
            varint(out, zigzag(w.x))
            varint(out, zigzag(w.y))
            varint(out, zigzag(w.z))
            out.writeBoolean(w.yIncluded)
            varint(out, w.color)
        }
    }

    fun readWaypoints(input: DataInputStream, count: Int): List<SharedWaypoint> {
        if (count !in 1..MAX_WAYPOINTS) error("$count waypoints")
        return List(count) {
            SharedWaypoint(
                input.readUTF().take(64), input.readUTF().take(3),
                unzigzag(varint(input)), unzigzag(varint(input)), unzigzag(varint(input)),
                input.readBoolean(), varint(input),
            )
        }
    }

    // ---- packing ----

    /**
     * [format], [header], [count] and what [rest] writes, compressed, behind a CRC-32 and the
     * length of the compressed bytes: a batch changed on the way is turned away rather than
     * misread, and anything after the length (chat's padding) is cut off.
     */
    private fun pack(format: Int, header: Header, count: Int, rest: (DataOutputStream) -> Unit): ByteArray {
        val compressed = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(compressed, Deflater(Deflater.BEST_COMPRESSION))).use { out ->
            out.writeByte(format)
            out.writeUTF(header.from)
            out.writeUTF(header.dimensionId)
            out.writeInt(header.shareId)
            varint(out, header.batch)
            varint(out, header.batches)
            writeArea(out, header.area)
            out.writeInt(count)
            rest(out)
        }
        val bytes = compressed.toByteArray()
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        return ByteBuffer.allocate(8 + bytes.size).putInt(crc).putInt(bytes.size).put(bytes).array()
    }

    /** A batch opened: its format, header and count, and the rest still to read from [input]. */
    class Opened(val format: Int, val header: Header, val count: Int, val input: DataInputStream)

    /** Checks and opens a batch made by [pack], hands it to [action] and closes it. */
    fun <T> open(bytes: ByteArray, action: (Opened) -> T): T {
        if (bytes.size < 9) error("too short")
        val head = ByteBuffer.wrap(bytes, 0, 8)
        val crc = head.int
        val length = head.int
        if (length !in 1..bytes.size - 8) error("bad length")
        if (CRC32().apply { update(bytes, 8, length) }.value.toInt() != crc) error("it was changed on the way (checksum)")
        DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes, 8, length))).use { input ->
            val format = input.readUnsignedByte()
            if (format != TERRAIN && format != BIOMES && format != WAYPOINTS) error("made by a different version of the mod (format $format)")
            val from = input.readUTF()
            val dimensionId = input.readUTF()
            val shareId = input.readInt()
            val batch = varint(input)
            val batches = varint(input)
            if (batches !in 1..65536 || batch !in 0 until batches) error("batch $batch of $batches")
            val area = readArea(input)
            val count = input.readInt()
            return action(Opened(format, Header(from, dimensionId, shareId, batch, batches, area), count, input))
        }
    }

    /** The biomes of an opened [BIOMES] batch, by [Session.chunkKey]. */
    fun readBiomes(input: DataInputStream, count: Int): Map<Long, Array<ResourceKey<Biome>?>> {
        val part = readArea(input)
        val names = varint(input)
        if (names > 65536) error("$names biomes")
        val biomes = List(names) { ResourceKey.create(Registries.BIOME, Identifier.parse(input.readUTF())) }
        val bitBytes = varint(input)
        if (bitBytes > part.width * part.height / 8 + 1) error("bad chunk list")
        val present = BitSet.valueOf(ByteArray(bitBytes).also { input.readFully(it) })
        if (present.cardinality() != count) error("chunk count does not match")
        val rowCells = part.width * 4
        val cells = ShortArray(rowCells * part.height * 4) { varint(input).also { if (it > names) error("bad biome") }.toShort() }
        val out = HashMap<Long, Array<ResourceKey<Biome>?>>()
        var i = present.nextSetBit(0)
        while (i >= 0) {
            val gx = i % part.width
            val gz = i / part.width
            out[Session.chunkKey(part.left + gx, part.top + gz)] = Array(16) { cell ->
                val value = cells[(gz * 4 + (cell and 3)) * rowCells + gx * 4 + (cell shr 2)].toInt()
                if (value == 0) null else biomes[value - 1]
            }
            i = present.nextSetBit(i + 1)
        }
        return out
    }

    /** Reads an opened [TERRAIN] batch into [into], by region. */
    fun readTerrain(input: DataInputStream, count: Int, into: MutableMap<Pair<Int, Int>, SharedRegion>) {
        if (count !in 1..32 * 32) error("$count chunks")
        val stateCount = varint(input)
        if (stateCount > 65536) error("$stateCount blocks")
        val states = List(stateCount) { parseState(input.readUTF()) }
        val biomeCount = varint(input)
        if (biomeCount > 65536) error("$biomeCount biomes")
        val biomes = List(biomeCount) { ResourceKey.create(Registries.BIOME, Identifier.parse(input.readUTF())) }
        repeat(count) {
            val cx = input.readInt()
            val cz = input.readInt()
            val chunk = SharedChunk()
            for (i in 0 until 256) {
                chunk.state[i] = states[varint(input)]
                val height = input.readShort()
                chunk.height[i] = height
                chunk.top[i] = (height + unzigzag(varint(input))).toShort()
                chunk.biome[i] = varint(input).let { if (it == 0) null else biomes[it - 1] }
                val lightAndGlow = input.readUnsignedByte()
                chunk.light[i] = (lightAndGlow and 15).toByte()
                chunk.glowing[i] = lightAndGlow and 16 != 0
                val overlays = varint(input)
                if (overlays > MAX_OVERLAYS) error("$overlays layers on one block")
                if (overlays > 0) {
                    chunk.overlays[i] = List(overlays) {
                        val state = states[varint(input)]
                        val bits = input.readUnsignedByte()
                        SharedOverlay(state, (bits and 15).toByte(), bits and 16 != 0, input.readUnsignedByte())
                    }
                }
            }
            val region = into.getOrPut((cx shr 5) to (cz shr 5)) { SharedRegion(cx shr 5, cz shr 5) }
            region.chunks[(cz and 31) * 32 + (cx and 31)] = chunk
        }
    }

    // ---- terrain, as it is written into your map ----

    class SharedOverlay(val state: BlockState, val light: Byte, val glowing: Boolean, val opacity: Int)

    class SharedChunk {
        val state = arrayOfNulls<BlockState>(256)
        val height = ShortArray(256)
        val top = ShortArray(256)
        val biome = arrayOfNulls<ResourceKey<Biome>>(256)
        val light = ByteArray(256)
        val glowing = BooleanArray(256)
        val overlays = arrayOfNulls<List<SharedOverlay>>(256)
    }

    class SharedRegion(override val regionX: Int, override val regionZ: Int) : XaeroWriter.Pixels {
        val chunks = arrayOfNulls<SharedChunk>(32 * 32)

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

    // ---- small parts ----

    fun writeArea(out: DataOutputStream, area: Area) {
        out.writeInt(area.left)
        out.writeInt(area.top)
        out.writeInt(area.right)
        out.writeInt(area.bottom)
    }

    fun readArea(input: DataInputStream): Area {
        val area = Area(input.readInt(), input.readInt(), input.readInt(), input.readInt())
        if (area.width <= 0 || area.height <= 0 || area.width > MAX_SIDE || area.height > MAX_SIDE) error("bad area")
        return area
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

    /** A block by its name and properties; one this game does not know becomes air. */
    private fun parseState(text: String): BlockState = try {
        BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState()
    } catch (e: Exception) {
        Blocks.AIR.defaultBlockState()
    }

    fun varint(out: DataOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.writeByte(v and 0x7F or 0x80)
            v = v ushr 7
        }
        out.writeByte(v)
    }

    fun varint(input: DataInputStream): Int {
        var value = 0
        var shift = 0
        while (true) {
            val b = input.readUnsignedByte()
            value = value or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return value
            shift += 7
            if (shift > 28) error("bad number")
        }
    }

    private fun zigzag(v: Int) = (v shl 1) xor (v shr 31)
    private fun unzigzag(v: Int) = (v ushr 1) xor -(v and 1)
}
