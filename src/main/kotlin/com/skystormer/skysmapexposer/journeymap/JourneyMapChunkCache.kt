package com.skystormer.skysmapexposer.journeymap

import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongArrayTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.StringTag
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.PalettedContainerFactory
import net.minecraft.world.level.chunk.PalettedContainerRO
import net.minecraft.world.level.levelgen.Heightmap
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.InflaterInputStream

/**
 * Reads the biomes out of JourneyMap's chunk cache files, for any dimension, whether or not
 * JourneyMap has that dimension's cache open: JourneyMap keeps only the one you are in open, and
 * its own reader opens files for writing. This only ever reads, never writes.
 *
 * A file (`c.<regionX>.<regionZ>.jmc`) holds a region's 32 by 32 chunks: a 16-byte header ("JMCA",
 * format 1, deflate), then where each chunk is and how long, then each chunk deflated. A chunk
 * ("JMCS", format 1) holds its sections (blocks, which are skipped, and biomes, a palette of names
 * and the packed indices the game itself uses, decoded with the game's own codec), then its
 * height maps. Any other format is refused as a whole ([knownFormat] goes false), not guessed at.
 *
 * Sizes are checked before anything is allocated, so a damaged file costs that chunk, not memory.
 */
internal object JourneyMapChunkCache {

    /** False once a file in a format this does not know has been met, for `/mapexposer`. */
    @Volatile
    var knownFormat = true
        private set

    /** A chunk's biomes: each section's, by section from [minSectionY], and the top block's height per column. */
    class Chunk(
        private val minSectionY: Int,
        private val minBuild: Int,
        private val maxBuild: Int,
        private val sections: Array<PalettedContainerRO<Holder<Biome>>?>,
        private val surface: IntArray?,
    ) {
        /** The biome at column ([x], [z]) of the chunk: at its top block, or at height [y] when given. */
        fun biomeAt(x: Int, z: Int, y: Int?): ResourceKey<Biome>? {
            val at = y ?: ((surface ?: return null)[x + z * 16] - 1)
            if (at < minBuild || at >= maxBuild) return null
            val section = sections.getOrNull((at shr 4) - minSectionY) ?: return null
            return section.get(x shr 2, (at and 15) shr 2, z shr 2).unwrapKey().orElse(null)
        }
    }

    /** Every chunk of the region file [file] (index `z * 32 + x` within it), null where it has none. Off the client thread. */
    fun readRegion(file: Path, registries: RegistryAccess): Array<Chunk?> {
        val chunks = arrayOfNulls<Chunk>(CHUNKS)
        if (!Files.isRegularFile(file)) return chunks
        val bytes = Files.readAllBytes(file)
        val (offsets, lengths) = index(ByteBuffer.wrap(bytes), bytes.size.toLong()) ?: return chunks
        val decoder = Decoder(registries)
        for (i in 0 until CHUNKS) {
            if (lengths[i] == 0) continue
            chunks[i] = try {
                decoder.chunk(inflate(bytes, offsets[i], lengths[i]))
            } catch (e: Exception) {
                null
            }
        }
        return chunks
    }

    /** Chunk [index] (`z * 32 + x` within its region) of [file] alone, reading only that much. */
    fun readChunk(file: Path, index: Int, registries: RegistryAccess): Chunk? {
        if (!Files.isRegularFile(file)) return null
        Files.newByteChannel(file).use { channel ->
            val head = ByteBuffer.allocate(DATA_START)
            while (head.hasRemaining() && channel.read(head) > 0) { /* fill */ }
            head.flip()
            val (offsets, lengths) = index(head, channel.size()) ?: return null
            if (lengths[index] == 0) return null
            val data = ByteBuffer.allocate(lengths[index])
            channel.position(offsets[index].toLong())
            while (data.hasRemaining() && channel.read(data) > 0) { /* fill */ }
            if (data.hasRemaining()) return null
            return Decoder(registries).chunk(inflate(data.array(), 0, data.capacity()))
        }
    }

    /** Where each chunk is and how long, after checking the header; null for a file this cannot read. */
    private fun index(buffer: ByteBuffer, fileSize: Long): Pair<IntArray, IntArray>? {
        if (buffer.remaining() < DATA_START) return null
        val magic = ByteArray(4).also(buffer::get)
        val version = buffer.get()
        val compression = buffer.get()
        if (!magic.contentEquals(FILE_MAGIC) || version != FILE_VERSION || compression != DEFLATE) {
            knownFormat = false
            return null
        }
        buffer.position(HEADER)
        val offsets = IntArray(CHUNKS) { buffer.int }
        val lengths = IntArray(CHUNKS) { buffer.int }
        for (i in 0 until CHUNKS) {
            val bad = lengths[i] < 0 || lengths[i] > MAX_CHUNK || (lengths[i] > 0 && (offsets[i] < DATA_START || offsets[i].toLong() + lengths[i] > fileSize))
            if (bad) lengths[i] = 0
        }
        return offsets to lengths
    }

    private fun inflate(bytes: ByteArray, offset: Int, length: Int): DataInputStream {
        val inflated = InflaterInputStream(ByteArrayInputStream(bytes, offset, length)).use { it.readNBytes(MAX_INFLATED + 1) }
        if (inflated.size > MAX_INFLATED) throw IOException("chunk too big")
        return DataInputStream(ByteArrayInputStream(inflated))
    }

    /** Decodes chunks with the game's biome codec, made once per batch. */
    private class Decoder(registries: RegistryAccess) {
        private val codec = PalettedContainerFactory.create(registries).biomeContainerCodec()

        fun chunk(input: DataInputStream): Chunk? {
            if (input.readInt() != CHUNK_MAGIC || input.readByte() != CHUNK_VERSION) {
                knownFormat = false
                return null
            }
            val minSectionY = input.readInt()
            val minBuild = input.readInt()
            val maxBuild = input.readInt()
            val sectionCount = ((maxBuild - minBuild) shr 4).coerceIn(0, MAX_SECTIONS)
            val sections = arrayOfNulls<PalettedContainerRO<Holder<Biome>>>(sectionCount)
            repeat(input.readUnsignedByte()) {
                val index = input.readUnsignedByte()
                skipBlocks(input)
                val biomes = biomes(input)
                if (index < sectionCount) sections[index] = biomes
            }
            var surface: IntArray? = null
            repeat(input.readUnsignedByte()) {
                val type = input.readUnsignedByte()
                val heights = IntArray(256) { input.readShort().toInt() }
                if (Heightmap.Types.entries.getOrNull(type) == Heightmap.Types.WORLD_SURFACE) surface = heights
            }
            return Chunk(minSectionY, minBuild, maxBuild, sections, surface)
        }

        /** A section's blocks: a palette of block names with their properties, then 4096 indices. */
        private fun skipBlocks(input: DataInputStream) {
            repeat(count(input, MAX_PALETTE)) {
                input.readUTF()
                repeat(input.readUnsignedByte()) { input.readUTF(); input.readUTF() }
            }
            input.skipNBytes(4096L * 2)
        }

        /** A section's biomes, as the game keeps them: a palette of names and the packed indices into it. */
        private fun biomes(input: DataInputStream): PalettedContainerRO<Holder<Biome>>? {
            val palette = ListTag()
            repeat(count(input, MAX_PALETTE)) { palette.add(StringTag.valueOf(input.readUTF())) }
            val data = LongArray(count(input, MAX_LONGS)) { input.readLong() }
            val tag = CompoundTag()
            tag.put("palette", palette)
            if (data.isNotEmpty()) tag.put("data", LongArrayTag(data))
            return codec.parse(NbtOps.INSTANCE, tag).result().orElse(null)
        }

        private fun count(input: DataInputStream, max: Int): Int {
            val count = input.readInt()
            if (count !in 0..max) throw IOException("bad count $count")
            return count
        }
    }

    private val FILE_MAGIC = "JMCA".toByteArray(Charsets.US_ASCII)
    private const val FILE_VERSION: Byte = 1
    private const val DEFLATE: Byte = 1
    private const val HEADER = 16
    private const val CHUNKS = 32 * 32
    private const val DATA_START = HEADER + CHUNKS * 8

    /** "JMCS". */
    private const val CHUNK_MAGIC = 0x4A4D4353
    private const val CHUNK_VERSION: Byte = 1

    private const val MAX_CHUNK = 4 shl 20
    private const val MAX_INFLATED = 16 shl 20
    private const val MAX_SECTIONS = 256
    private const val MAX_PALETTE = 4096
    private const val MAX_LONGS = 4096
}
