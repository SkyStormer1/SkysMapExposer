package com.skystormer.skysmapexposer.journeymap

import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.LongArrayTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.StringTag
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.DataLayer
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
 * Reads JourneyMap's chunk cache files, for any dimension, whether or not JourneyMap has that
 * dimension's cache open: JourneyMap keeps only the one you are in open, and its own reader opens
 * files for writing. This only ever reads, never writes.
 *
 * JourneyMap's cache is what Xaero's map keeps for itself and JourneyMap's map does not: every
 * chunk it has mapped, blocks, biomes, heights and light. The biome tint reads it
 * ([JourneyMapBiomes]), and so does saving terrain files ([JourneyMapTerrainSave]).
 *
 * A file (`c.<regionX>.<regionZ>.jmc`) holds a region's 32 by 32 chunks: a 16-byte header ("JMCA",
 * format 1, deflate), then where each chunk is and how long, then each chunk deflated. A chunk
 * ("JMCS", format 1) holds its sections (blocks: a palette of block names and properties, then an
 * index per block, y by z by x; and biomes: a palette of names and the packed indices the game
 * itself uses, decoded with the game's own codec), then its height maps, then its block light.
 * Blocks and light are only decoded when asked for, as the biome tint needs neither. Any other
 * format is refused as a whole ([knownFormat] goes false), not guessed at.
 *
 * Sizes are checked before anything is allocated, so a damaged file costs that chunk, not memory.
 */
internal object JourneyMapChunkCache {

    /** False once a file in a format this does not know has been met, for `/mapexposer`. */
    @Volatile
    var knownFormat = true
        private set

    /** A section's blocks: its palette, and an index into it per block (`y * 256 + z * 16 + x`). */
    class SectionBlocks(val palette: Array<BlockState>, val indices: ShortArray)

    /**
     * A chunk: each section's biomes (and blocks and block light, when read), by section from
     * [minSectionY], and the height just above the top block of each column (`x + z * 16`).
     */
    class Chunk(
        private val minSectionY: Int,
        val minBuild: Int,
        val maxBuild: Int,
        private val biomes: Array<PalettedContainerRO<Holder<Biome>>?>,
        val surface: IntArray?,
        private val blocks: Array<SectionBlocks?>?,
        private val light: Array<DataLayer?>?,
    ) {
        /** The biome at column ([x], [z]) of the chunk: at its top block, or at height [y] when given. */
        fun biomeAt(x: Int, z: Int, y: Int?): ResourceKey<Biome>? {
            val at = y ?: ((surface ?: return null)[x + z * 16] - 1)
            if (at < minBuild || at >= maxBuild) return null
            val section = biomes.getOrNull((at shr 4) - minSectionY) ?: return null
            return section.get(x shr 2, (at and 15) shr 2, z shr 2).unwrapKey().orElse(null)
        }

        /** The block at ([x], [y], [z]) of the chunk; air where it has none. Only when read with blocks. */
        fun blockAt(x: Int, y: Int, z: Int): BlockState {
            val section = blocks?.getOrNull((y shr 4) - minSectionY) ?: return AIR
            val index = section.indices[((y and 15) shl 8) or (z shl 4) or x].toInt() and 0xFFFF
            return section.palette.getOrElse(index) { AIR }
        }

        /** The block light at ([x], [y], [z]), 0 to 15. */
        fun lightAt(x: Int, y: Int, z: Int): Int = light?.getOrNull((y shr 4) - minSectionY)?.get(x, y and 15, z) ?: 0
    }

    /** Region ([regionX], [regionZ])'s file in the chunk cache folder [folder]. */
    fun file(folder: Path, regionX: Int, regionZ: Int): Path = folder.resolve("c.$regionX.$regionZ.jmc")

    /**
     * Every chunk of the region file [file] (index `z * 32 + x` within it), null where it has none;
     * [withBlocks] to read blocks and light too. Off the client thread.
     */
    fun readRegion(file: Path, registries: RegistryAccess, withBlocks: Boolean = false): Array<Chunk?> {
        val chunks = arrayOfNulls<Chunk>(CHUNKS)
        if (!Files.isRegularFile(file)) return chunks
        val bytes = Files.readAllBytes(file)
        val (offsets, lengths) = index(ByteBuffer.wrap(bytes), bytes.size.toLong()) ?: return chunks
        val decoder = Decoder(registries, withBlocks)
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

    /** Chunk [index] (`z * 32 + x` within its region) of [file] alone, reading only that much; biomes only. */
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
            return Decoder(registries, false).chunk(inflate(data.array(), 0, data.capacity()))
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
            val bad = lengths[i] < 0 || lengths[i] > MAX_CHUNK ||
                (lengths[i] > 0 && (offsets[i] < DATA_START || offsets[i].toLong() + lengths[i] > fileSize))
            if (bad) lengths[i] = 0
        }
        return offsets to lengths
    }

    private fun inflate(bytes: ByteArray, offset: Int, length: Int): DataInputStream {
        val inflated = InflaterInputStream(ByteArrayInputStream(bytes, offset, length)).use { it.readNBytes(MAX_INFLATED + 1) }
        if (inflated.size > MAX_INFLATED) throw IOException("chunk too big")
        return DataInputStream(ByteArrayInputStream(inflated))
    }

    /** Decodes chunks with the game's own readers (biome codec, block states), made once per batch. */
    private class Decoder(registries: RegistryAccess, private val withBlocks: Boolean) {
        private val codec = PalettedContainerFactory.create(registries).biomeContainerCodec()
        private val blockLookup = registries.lookupOrThrow(Registries.BLOCK)

        fun chunk(input: DataInputStream): Chunk? {
            if (input.readInt() != CHUNK_MAGIC || input.readByte() != CHUNK_VERSION) {
                knownFormat = false
                return null
            }
            val minSectionY = input.readInt()
            val minBuild = input.readInt()
            val maxBuild = input.readInt()
            val sectionCount = ((maxBuild - minBuild) shr 4).coerceIn(0, MAX_SECTIONS)
            val biomes = arrayOfNulls<PalettedContainerRO<Holder<Biome>>>(sectionCount)
            val blocks = if (withBlocks) arrayOfNulls<SectionBlocks>(sectionCount) else null
            repeat(input.readUnsignedByte()) {
                val index = input.readUnsignedByte()
                val sectionBlocks = blocks(input)
                val sectionBiomes = biomes(input)
                if (index < sectionCount) {
                    biomes[index] = sectionBiomes
                    blocks?.set(index, sectionBlocks)
                }
            }
            var surface: IntArray? = null
            repeat(input.readUnsignedByte()) {
                val type = input.readUnsignedByte()
                val heights = IntArray(256) { input.readShort().toInt() }
                if (Heightmap.Types.entries.getOrNull(type) == Heightmap.Types.WORLD_SURFACE) surface = heights
            }
            val light = if (withBlocks) arrayOfNulls<DataLayer>(sectionCount) else null
            if (light != null) {
                repeat(input.readUnsignedByte()) {
                    val index = input.readUnsignedByte()
                    val bytes = ByteArray(2048).also(input::readFully)
                    if (index < sectionCount) light[index] = DataLayer(bytes)
                }
            }
            return Chunk(minSectionY, minBuild, maxBuild, biomes, surface, blocks, light)
        }

        /**
         * A section's blocks: a palette of block names with their properties, then 4096 indices.
         * Decoded only [withBlocks], else skipped. A block this game does not know is air.
         */
        private fun blocks(input: DataInputStream): SectionBlocks? {
            val palette = ArrayList<BlockState>()
            repeat(count(input, MAX_PALETTE)) {
                val name = input.readUTF()
                val properties = CompoundTag()
                repeat(input.readUnsignedByte()) { properties.putString(input.readUTF(), input.readUTF()) }
                if (withBlocks) palette.add(blockState(name, properties))
            }
            if (!withBlocks) {
                input.skipNBytes(4096L * 2)
                return null
            }
            return SectionBlocks(palette.toTypedArray(), ShortArray(4096) { input.readShort() })
        }

        private fun blockState(name: String, properties: CompoundTag): BlockState = try {
            val tag = CompoundTag()
            tag.putString("Name", name)
            if (!properties.isEmpty) tag.put("Properties", properties)
            NbtUtils.readBlockState(blockLookup, tag)
        } catch (e: Exception) {
            AIR
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

    private val AIR: BlockState = Blocks.AIR.defaultBlockState()

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
