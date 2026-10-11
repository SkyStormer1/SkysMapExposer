package com.skystormer.skysmapexposer.terrain

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One BlueMap hires tile: the triangles BlueMap draws for [tileSize] blocks a side, each with the
 * texture, tint and light of the face it belongs to.
 *
 * The file ("PRBM") is a list of triangles, three corners each, in little-endian order: a version
 * byte, a header byte whose low five bits count the attributes, the corner count (3 bytes) and an
 * index count (3 bytes, 0: not indexed). Each attribute is a NUL-ended name, a type byte, padding
 * to 4 bytes, then one value per corner. Then, after padding, the material groups: texture number,
 * first corner, corner count, until a texture number of -1. Positions are blocks from the tile's
 * corner. These are facts about the files a BlueMap server publishes; no BlueMap code is used.
 *
 * Everything is checked against the file's length before it is read, since it came from a server.
 */
class HiresTile private constructor(private val data: ByteBuffer, val triangles: Int) {

    private var position = -1
    private var normal = -1
    private var color = -1
    private var sunlight = -1
    private var blocklight = -1
    private val textureOf = IntArray(triangles) { -1 }

    fun x(triangle: Int, corner: Int): Float = data.getFloat(position + (triangle * 3 + corner) * 12)
    fun y(triangle: Int, corner: Int): Float = data.getFloat(position + (triangle * 3 + corner) * 12 + 4)
    fun z(triangle: Int, corner: Int): Float = data.getFloat(position + (triangle * 3 + corner) * 12 + 8)

    /** The face's normal, each part from -1 to 1. */
    fun normal(triangle: Int, axis: Int): Float = data.get(normal + triangle * 9 + axis) / 127f

    /** The tint as 0xRRGGBB (white for untinted faces). */
    fun color(triangle: Int): Int {
        val at = color + triangle * 9
        return (data.get(at).toInt() and 0xFF shl 16) or (data.get(at + 1).toInt() and 0xFF shl 8) or (data.get(at + 2).toInt() and 0xFF)
    }

    fun sunlight(triangle: Int): Int = if (sunlight < 0) 15 else data.get(sunlight + triangle * 3).toInt().coerceIn(0, 15)
    fun blocklight(triangle: Int): Int = if (blocklight < 0) 0 else data.get(blocklight + triangle * 3).toInt().coerceIn(0, 15)

    /** The texture number (into the map's texture list), or -1. */
    fun texture(triangle: Int): Int = textureOf[triangle]

    companion object {
        private const val MAX_CORNERS = 3 * 500_000

        /** Reads an unpacked tile; an empty file (BlueMap's empty tile) has no triangles. */
        @Throws(IOException::class)
        fun read(bytes: ByteArray): HiresTile {
            val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (bytes.size < 8) return HiresTile(data, 0)
            val attributes = bytes[1].toInt() and 0x1F
            val corners = u24(bytes, 2)
            if (u24(bytes, 5) != 0) throw IOException("indexed hires tiles are not supported")
            if (corners % 3 != 0 || corners > MAX_CORNERS) throw IOException("bad corner count $corners")
            val tile = HiresTile(data, corners / 3)
            var at = 8
            repeat(attributes) {
                val end = (at until bytes.size).firstOrNull { bytes[it] == 0.toByte() } ?: throw IOException("truncated")
                val name = String(bytes, at, end - at, Charsets.US_ASCII)
                at = end + 1
                if (at >= bytes.size) throw IOException("truncated")
                val type = bytes[at].toInt() and 0xFF
                at += 1
                at += (-at) and 3
                val cardinality = ((type shr 4) and 3) + 1
                val size = when (type and 0xF) {
                    1, 6, 10 -> 4
                    3, 7 -> 1
                    4, 8 -> 2
                    else -> throw IOException("unknown encoding in $name")
                }
                val length = corners.toLong() * cardinality * size
                if (at + length > bytes.size) throw IOException("truncated $name")
                when (name) {
                    "position" -> if (cardinality == 3 && size == 4) tile.position = at
                    "normal" -> if (cardinality == 3 && size == 1) tile.normal = at
                    "color" -> if (cardinality == 3 && size == 1) tile.color = at
                    "sunlight" -> if (cardinality == 1 && size == 1) tile.sunlight = at
                    "blocklight" -> if (cardinality == 1 && size == 1) tile.blocklight = at
                }
                at += length.toInt()
            }
            if (tile.position < 0 || tile.normal < 0 || tile.color < 0) throw IOException("missing attributes")
            at += (-at) and 3
            while (at + 4 <= bytes.size) {
                val texture = data.getInt(at)
                at += 4
                if (texture == -1) break
                if (at + 8 > bytes.size) throw IOException("truncated groups")
                val first = data.getInt(at) / 3
                val count = data.getInt(at + 4) / 3
                at += 8
                if (first < 0 || count < 0 || first + count > tile.triangles) throw IOException("bad group")
                tile.textureOf.fill(texture, first, first + count)
            }
            return tile
        }

        private fun u24(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 0xFF) or (bytes[at + 1].toInt() and 0xFF shl 8) or (bytes[at + 2].toInt() and 0xFF shl 16)
    }
}
