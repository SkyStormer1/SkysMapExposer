package com.skystormer.skysmapexposer

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.BitSet
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A tile ready to draw, kept on disk (`x_z.smet`): the shaded picture at every zoom factor the
 * maps ask for, and where BlueMap has anything, each compressed on its own so that loading one is
 * a read and an inflate, with no PNG decode, shading or shrinking.
 *
 * Layout: magic, version, the hash of the BlueMap picture it was made from, tile and image size,
 * then a table of sections (factor, offset, length), then the sections. Section 0 is the presence
 * mask, `imageSize` square, one bit a pixel; the others are pixels, three bytes each, 0 for
 * nothing.
 *
 * Measured on 60 of his server's tiles (2026-10-20, Deflater level 6): all sizes together take
 * 1.45× the PNG, the 1:1 picture alone 0.78×; loading takes 1.4 ms at 1:1 and 0.15 ms at 1:20,
 * against 11 ms for reading, decoding, shading and shrinking the PNG (8× to 74× faster), and holds
 * about a third of the memory while it does. PNG-style row filters made it bigger, not smaller:
 * shaded pixels are too noisy for them.
 */
object TileFile {

    const val MAGIC = 0x534D4554 // "SMET"
    const val VERSION = 1

    /** The presence mask's section. */
    private const val PRESENCE = 0

    class Header(val hash: String, val tileSize: Int, val imageSize: Int, val sections: Map<Int, LongArray>) {
        val factors: Set<Int> get() = sections.keys - PRESENCE
    }

    /** How pixels are laid out before compressing; only [PLAIN] is written, the rest are for measuring (`CacheFormatBench`). */
    enum class Layout { PLAIN, SUB, PLANES }

    /**
     * The file's bytes for [colours] (BlueMap's shaded picture, `imageSize` square), shrunk to
     * each of [factors].
     */
    fun encode(
        hash: String, colours: IntArray, imageSize: Int, tileSize: Int, factors: List<Int>,
        level: Int = Deflater.DEFAULT_COMPRESSION, layout: Layout = Layout.PLAIN,
    ): ByteArray {
        val deflater = Deflater(level)
        try {
            val sections = ArrayList<Pair<Int, ByteArray>>()
            sections.add(PRESENCE to compress(deflater, TilePixels.presence(colours).toByteArray()))
            for (factor in factors) {
                val side = tileSize / factor
                sections.add(factor to compress(deflater, pack(TilePixels.shrink(colours, imageSize, tileSize, factor), side, layout)))
            }
            val head = ByteArrayOutputStream()
            DataOutputStream(head).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(VERSION)
                out.writeUTF(hash)
                out.writeInt(tileSize)
                out.writeInt(imageSize)
                out.writeInt(sections.size)
            }
            val tableSize = sections.size * 12
            var offset = head.size() + tableSize
            val table = ByteArrayOutputStream()
            DataOutputStream(table).use { out ->
                for ((factor, bytes) in sections) {
                    out.writeInt(factor)
                    out.writeInt(offset)
                    out.writeInt(bytes.size)
                    offset += bytes.size
                }
            }
            val file = ByteArrayOutputStream(offset)
            head.writeTo(file)
            table.writeTo(file)
            for ((_, bytes) in sections) file.write(bytes)
            return file.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /** The header and section table, or an [IOException] if this is not a file this version reads. */
    @Throws(IOException::class)
    fun header(file: Path): Header = FileChannel.open(file, StandardOpenOption.READ).use(::header)

    /** Where BlueMap has anything, `imageSize` square. */
    @Throws(IOException::class)
    fun presence(file: Path): Pair<Header, BitSet> = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
        val header = header(channel)
        header to BitSet.valueOf(section(channel, header, PRESENCE, (header.imageSize.toLong() * header.imageSize + 7) / 8))
    }

    /** The picture at [factor], `tileSize / factor` square, ready to upload; null if the file has no such size. */
    @Throws(IOException::class)
    fun pixels(file: Path, factor: Int, layout: Layout = Layout.PLAIN): IntArray? = FileChannel.open(file, StandardOpenOption.READ).use { channel ->
        val header = header(channel)
        if (factor !in header.sections) return null
        val side = header.tileSize / factor
        unpack(section(channel, header, factor, side.toLong() * side * 3), side, layout)
    }

    private fun header(channel: FileChannel): Header {
        val fixed = read(channel, 0, minOf(channel.size(), 4096L).toInt())
        val input = DataInputStream(fixed.inputStream())
        if (input.readInt() != MAGIC) throw IOException("not a tile file")
        val version = input.readInt()
        if (version != VERSION) throw IOException("tile file version $version")
        val hash = input.readUTF()
        val tileSize = input.readInt()
        val imageSize = input.readInt()
        val count = input.readInt()
        if (tileSize !in 1..4096 || imageSize != tileSize + 1 || count !in 1..64) throw IOException("damaged tile file")
        val sections = HashMap<Int, LongArray>()
        repeat(count) {
            val factor = input.readInt()
            val offset = input.readInt().toLong()
            val length = input.readInt().toLong()
            if (offset < 0 || length < 0 || offset + length > channel.size()) throw IOException("damaged tile file")
            sections[factor] = longArrayOf(offset, length)
        }
        if (PRESENCE !in sections) throw IOException("damaged tile file")
        return Header(hash, tileSize, imageSize, sections)
    }

    private fun section(channel: FileChannel, header: Header, id: Int, expected: Long): ByteArray {
        val (offset, length) = header.sections.getValue(id)
        val packed = read(channel, offset, length.toInt())
        val inflater = Inflater()
        try {
            inflater.setInput(packed)
            val out = ByteArray(expected.toInt())
            var at = 0
            while (at < out.size && !inflater.finished()) {
                val n = inflater.inflate(out, at, out.size - at)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                at += n
            }
            // A presence mask may end early: BitSet drops trailing empty bytes.
            if (id != PRESENCE && at != out.size) throw IOException("damaged tile file")
            if (!inflater.finished()) throw IOException("damaged tile file")
            return if (at == out.size) out else out.copyOf(at)
        } catch (e: java.util.zip.DataFormatException) {
            throw IOException("damaged tile file", e)
        } finally {
            inflater.end()
        }
    }

    private fun read(channel: FileChannel, offset: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        var at = offset
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, at)
            if (n < 0) break
            at += n
        }
        return buffer.array().copyOf(buffer.position())
    }

    private fun compress(deflater: Deflater, bytes: ByteArray): ByteArray {
        deflater.reset()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream(bytes.size / 4 + 64)
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /** 0 stays "nothing", so a pixel that really is black is stored a shade off it. */
    private fun rgb(pixel: Int): Int = if (pixel == 0) 0 else (pixel and 0xFFFFFF).let { if (it == 0) 1 else it }

    private fun pack(pixels: IntArray, side: Int, layout: Layout): ByteArray {
        val out = ByteArray(pixels.size * 3)
        when (layout) {
            Layout.PLAIN -> for (i in pixels.indices) {
                val c = rgb(pixels[i])
                out[i * 3] = (c shr 16).toByte(); out[i * 3 + 1] = (c shr 8).toByte(); out[i * 3 + 2] = c.toByte()
            }
            Layout.SUB -> for (z in 0 until side) {
                var previous = 0
                for (x in 0 until side) {
                    val i = z * side + x
                    val c = rgb(pixels[i])
                    out[i * 3] = ((c shr 16) - (previous shr 16)).toByte()
                    out[i * 3 + 1] = ((c shr 8) - (previous shr 8)).toByte()
                    out[i * 3 + 2] = (c - previous).toByte()
                    previous = c
                }
            }
            Layout.PLANES -> {
                val plane = pixels.size
                for (z in 0 until side) {
                    var previous = 0
                    for (x in 0 until side) {
                        val i = z * side + x
                        val c = rgb(pixels[i])
                        out[i] = ((c shr 16) - (previous shr 16)).toByte()
                        out[plane + i] = ((c shr 8) - (previous shr 8)).toByte()
                        out[2 * plane + i] = (c - previous).toByte()
                        previous = c
                    }
                }
            }
        }
        return out
    }

    private fun unpack(bytes: ByteArray, side: Int, layout: Layout): IntArray {
        val pixels = IntArray(side * side)
        val plane = pixels.size
        for (z in 0 until side) {
            var r = 0
            var g = 0
            var b = 0
            for (x in 0 until side) {
                val i = z * side + x
                when (layout) {
                    Layout.PLAIN -> { r = bytes[i * 3].toInt(); g = bytes[i * 3 + 1].toInt(); b = bytes[i * 3 + 2].toInt() }
                    Layout.SUB -> { r += bytes[i * 3]; g += bytes[i * 3 + 1]; b += bytes[i * 3 + 2] }
                    Layout.PLANES -> { r += bytes[i]; g += bytes[plane + i]; b += bytes[2 * plane + i] }
                }
                val c = ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
                pixels[i] = if (c == 0) 0 else TilePixels.OPAQUE or c
            }
        }
        return pixels
    }
}
