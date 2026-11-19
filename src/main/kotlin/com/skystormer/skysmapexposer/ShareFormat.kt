package com.skystormer.skysmapexposer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * What is shared, however it travels: terrain by file ([TerrainFiles]), your Xaero map read in
 * batches ([RegionReader]); waypoints by private message ([ChatShare]). Each batch is packed as one
 * compressed block behind a CRC-32 and its length, so one changed or cut short on the way is turned
 * away rather than misread.
 *
 * A batch holds its [format], a [Header] (who, which dimension, which share, which batch of how
 * many, the whole area), the chunk count, then what the format carries:
 * - [TERRAIN]: every block exactly as Xaero keeps it ([TerrainBody]).
 * - [WAYPOINTS]: waypoints, with their names, letters, colours and coordinates.
 */
object ShareFormat {

    const val TERRAIN = 4
    // 5 was biomes, shared in chat until 1.9.2; not reused, so an old client's biomes are turned away.
    const val WAYPOINTS = 6

    /** Chunks per side, at most, of an area shared. */
    const val MAX_SIDE = 512
    const val MAX_WAYPOINTS = 1000
    /** The most a batch shared in chat may unpack to: [MAX_WAYPOINTS] of the longest waypoints fit. */
    const val MAX_BATCH = 256 * 1024
    private const val MAX_NAME = 64
    private const val MAX_INITIALS = 3
    /** Xaero's waypoint colours (`WaypointColor`), 0 to 20. */
    const val WAYPOINT_COLOURS = 21

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
        /** "terrain", "waypoints". */
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

    // ---- waypoints ----

    class SharedWaypoint(val name: String, val initials: String, val x: Int, val y: Int, val z: Int, val yIncluded: Boolean, val color: Int)

    fun packWaypoints(header: Header, waypoints: List<SharedWaypoint>): ByteArray = pack(WAYPOINTS, header, waypoints.size) { out ->
        for (w in waypoints) {
            out.writeUTF(clean(w.name).take(MAX_NAME))
            out.writeUTF(clean(w.initials).take(MAX_INITIALS))
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
                clean(input.readUTF()).take(MAX_NAME), clean(input.readUTF()).take(MAX_INITIALS),
                unzigzag(varint(input)), unzigzag(varint(input)), unzigzag(varint(input)),
                input.readBoolean(), varint(input).coerceIn(0, WAYPOINT_COLOURS - 1),
            )
        }
    }

    private val CODE = Regex("§.?")

    /** [text] without formatting codes. */
    fun stripCodes(text: String): String = text.replace(CODE, "")

    /** [text] without formatting codes, line breaks or other control and invisible format characters. */
    fun clean(text: String): String = stripCodes(text).filter {
        when (Character.getType(it).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false
            else -> true
        }
    }.trim()

    // ---- packing ----

    /**
     * [format], [header], [count] and what [rest] writes, compressed, behind a CRC-32 and the
     * length of the compressed bytes: a batch changed on the way is turned away rather than
     * misread, and anything after the length (chat's padding) is cut off.
     */
    internal fun pack(format: Int, header: Header, count: Int, rest: (DataOutputStream) -> Unit): ByteArray {
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

    /**
     * Checks and opens a batch made by [pack], hands it to [action] and closes it. Reading more
     * than [limit] bytes out of it fails, so a small batch cannot unpack into a huge one. Its area
     * may be at most [maxSide] chunks a side: [MAX_SIDE] in chat, the whole file's in a terrain file.
     */
    fun <T> open(bytes: ByteArray, limit: Int = Int.MAX_VALUE, maxSide: Int = MAX_SIDE, action: (Opened) -> T): T {
        if (bytes.size < 9) error("too short")
        val head = ByteBuffer.wrap(bytes, 0, 8)
        val crc = head.int
        val length = head.int
        if (length !in 1..bytes.size - 8) error("bad length")
        if (CRC32().apply { update(bytes, 8, length) }.value.toInt() != crc) error("it was changed on the way (checksum)")
        DataInputStream(Limited(InflaterInputStream(ByteArrayInputStream(bytes, 8, length)), limit)).use { input ->
            val format = input.readUnsignedByte()
            if (format != TERRAIN && format != WAYPOINTS) error("made by a different version of the mod (format $format)")
            val from = input.readUTF()
            val dimensionId = input.readUTF()
            val shareId = input.readInt()
            val batch = varint(input)
            val batches = varint(input)
            if (batches !in 1..65536 || batch !in 0 until batches) error("batch $batch of $batches")
            val area = readArea(input, maxSide)
            val count = input.readInt()
            return action(Opened(format, Header(from, dimensionId, shareId, batch, batches, area), count, input))
        }
    }

    /** [input], failing once more than [limit] bytes have been read from it. */
    private class Limited(input: InputStream, private val limit: Int) : FilterInputStream(input) {
        private var count = 0L

        private fun counted(n: Int): Int {
            if (n > 0) count += n
            if (count > limit) throw IOException("it unpacks to more than $limit bytes")
            return n
        }

        override fun read(): Int = super.read().also { if (it >= 0) counted(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = counted(super.read(b, off, len))
        override fun skip(n: Long): Long = super.skip(n).also { counted(it.toInt()) }
        override fun markSupported() = false
    }

    // ---- small parts ----

    fun writeArea(out: DataOutputStream, area: Area) {
        out.writeInt(area.left)
        out.writeInt(area.top)
        out.writeInt(area.right)
        out.writeInt(area.bottom)
    }

    /** An area written by [writeArea], at most [maxSide] chunks a side: [MAX_SIDE] in chat, more in a terrain file. */
    fun readArea(input: DataInputStream, maxSide: Int = MAX_SIDE): Area {
        val area = Area(input.readInt(), input.readInt(), input.readInt(), input.readInt())
        if (area.width <= 0 || area.height <= 0 || area.width > maxSide || area.height > maxSide) error("bad area")
        return area
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

    internal fun zigzag(v: Int) = (v shl 1) xor (v shr 31)
    internal fun unzigzag(v: Int) = (v ushr 1) xor -(v and 1)
}
