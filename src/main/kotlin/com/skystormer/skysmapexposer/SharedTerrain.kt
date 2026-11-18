package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.ShareFormat.unzigzag
import com.skystormer.skysmapexposer.ShareFormat.varint
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ThreadLocalRandom

/**
 * Terrain shared by file (`.smemap`, in the game folder's `shared maps` folder), for either map:
 * opening one dropped onto the game ([dropped], [open]), and reading it back a region at a time
 * ([region]). Saving one is Xaero's (`xaero.TerrainFiles`), as its map is what keeps every block;
 * adding one goes to the map in use ([MapAdapter.addSharedTerrain]).
 *
 * The file: [MAGIC], [VERSION], who saved it, the dimension, Xaero's cave layer it was read from
 * (version 2 on; version 1 files are the surface), the area and when; then one entry per region —
 * its position, chunk count and byte length, then the batch itself ([ShareFormat], checksummed).
 */
object SharedTerrain {

    const val EXTENSION = ".smemap"
    val MAGIC = "SMEMAP".toByteArray(Charsets.US_ASCII)
    const val VERSION = 2
    private const val MAX_ENTRY = 64 shl 20
    private const val MAX_OVERLAYS = 16
    private const val KEEP_OPENED = 5

    val folder: Path get() = FabricLoader.getInstance().gameDir.resolve("shared maps")

    class Entry(val regionX: Int, val regionZ: Int, val chunks: Int, val offset: Long, val length: Int)

    class Opened(val path: Path, val from: String, val dimensionId: String, val layer: Int, val area: ShareFormat.Area, val entries: List<Entry>) {
        val chunks get() = entries.sumOf { it.chunks }
    }

    class SharedOverlay(val state: BlockState, val light: Byte, val glowing: Boolean, val opacity: Int)

    /** A chunk of shared terrain: each of its 256 columns as Xaero keeps it, index `x * 16 + z`. */
    class SharedChunk {
        val state = arrayOfNulls<BlockState>(256)
        val height = ShortArray(256)
        val top = ShortArray(256)
        val biome = arrayOfNulls<ResourceKey<Biome>>(256)
        val light = ByteArray(256)
        val glowing = BooleanArray(256)
        val overlays = arrayOfNulls<List<SharedOverlay>>(256)
    }

    /** A region of shared terrain: its chunks, index `z * 32 + x` within it, null where the file has none. */
    class Region(val regionX: Int, val regionZ: Int) {
        val chunks = arrayOfNulls<SharedChunk>(32 * 32)
    }

    private val opened = LinkedHashMap<Int, Opened>()

    /** File [id] opened lately, or null once it has been let go of. */
    fun opened(id: Int): Opened? = opened[id]

    /** Files dropped onto the game window: opens every `.smemap` one. True if all of [paths] were, so nothing else needs to see the drop. */
    fun dropped(paths: List<Path>): Boolean {
        val ours = paths.filter { it.fileName.toString().lowercase().endsWith(EXTENSION) }
        if (ours.isEmpty()) return false
        ours.forEach(::open)
        return ours.size == paths.size
    }

    /** Opens a terrain file, and offers to add it in chat. */
    fun open(path: Path) {
        val player = Minecraft.getInstance().player
        if (player == null) {
            Log.info("A map file was dropped outside a world: {}", path)
            return
        }
        val file = try {
            read(path)
        } catch (e: Exception) {
            Log.error("Could not open the map file $path", e)
            player.sendSystemMessage(Component.literal("§7${path.fileName} could not be opened: ${e.message ?: e.javaClass.simpleName}"))
            return
        }
        val id = ThreadLocalRandom.current().nextInt(1 shl 30)
        opened[id] = file
        while (opened.size > KEEP_OPENED) opened.remove(opened.keys.first())
        val name = Ui.dimensionName(file.dimensionId) ?: file.dimensionId
        Log.info("Opened {} as {}: {} chunks of the {} (cave layer {}) from {}", path, id, file.chunks, file.dimensionId, file.layer, file.from)
        val layer = if (file.layer == MapLayers.SURFACE) "" else " (${layerName(file.layer)})"
        player.sendSystemMessage(
            Component.literal("§b${file.from}§r's terrain: ${file.chunks} chunks of the $name$layer near ${file.area.x}, ${file.area.z}  ")
                .append(ChatButton.of("[Add to unexplored only]", "/mapexposer shared $id blank", "Only fills chunks you have never mapped."))
                .append(Component.literal(" "))
                .append(ChatButton.of("[Add all]", "/mapexposer shared $id all", "Also replaces your own map there. Each region is backed up first."))
        )
    }

    fun layerName(layer: Int) = if (layer == MapLayers.SURFACE) "the surface" else "cave layer $layer"

    /** The files in the shared maps folder, newest first, by name. */
    fun list(): List<String> = try {
        Files.list(folder).use { files ->
            files.filter { it.fileName.toString().lowercase().endsWith(EXTENSION) }
                .sorted(compareByDescending<Path> { Files.getLastModifiedTime(it) })
                .map { it.fileName.toString() }
                .toList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun read(path: Path): Opened {
        DataInputStream(Files.newInputStream(path).buffered()).use { input ->
            val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
            if (!magic.contentEquals(MAGIC)) error("it is not a Sky's Map Exposer map")
            val version = input.readInt()
            if (version !in 1..VERSION) error("it was made by a different version of the mod")
            val from = input.readUTF()
            val dimensionId = input.readUTF()
            val layer = if (version >= 2) input.readInt() else MapLayers.SURFACE
            val area = ShareFormat.readArea(input)
            input.readLong()
            var offset = MAGIC.size + 4L + utfSize(from) + utfSize(dimensionId) + (if (version >= 2) 4 else 0) + 16 + 8
            val entries = ArrayList<Entry>()
            while (true) {
                val rx = try {
                    input.readInt()
                } catch (e: EOFException) {
                    break
                }
                val rz = input.readInt()
                val chunks = input.readInt()
                val length = input.readInt()
                if (chunks !in 1..32 * 32 || length !in 1..MAX_ENTRY) error("it is damaged")
                offset += 16
                entries.add(Entry(rx, rz, chunks, offset, length))
                input.skipNBytes(length.toLong())
                offset += length
            }
            if (entries.isEmpty()) error("it holds no terrain")
            return Opened(path, from, dimensionId, layer, area, entries)
        }
    }

    private fun utfSize(text: String): Int {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).writeUTF(text)
        return out.size()
    }

    /** One region of [file], read from disk when it is about to be written; throws if it cannot be read. Any thread. */
    fun region(file: Opened, entry: Entry): Region {
        val bytes = ByteArray(entry.length)
        RandomAccessFile(file.path.toFile(), "r").use { input ->
            input.seek(entry.offset)
            input.readFully(bytes)
        }
        val regions = HashMap<Pair<Int, Int>, Region>()
        ShareFormat.open(bytes) { batch ->
            if (batch.format != ShareFormat.TERRAIN) error("not terrain")
            readBatch(batch.input, batch.count, regions)
        }
        return regions[entry.regionX to entry.regionZ] ?: Region(entry.regionX, entry.regionZ)
    }

    /** Reads an opened [ShareFormat.TERRAIN] batch into [into], by region. */
    fun readBatch(input: DataInputStream, count: Int, into: MutableMap<Pair<Int, Int>, Region>) {
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
                chunk.state[i] = states.entry(varint(input), "block")
                val height = input.readShort()
                chunk.height[i] = height
                chunk.top[i] = (height + unzigzag(varint(input))).toShort()
                chunk.biome[i] = varint(input).let { if (it == 0) null else biomes.entry(it - 1, "biome") }
                val lightAndGlow = input.readUnsignedByte()
                chunk.light[i] = (lightAndGlow and 15).toByte()
                chunk.glowing[i] = lightAndGlow and 16 != 0
                val overlays = varint(input)
                if (overlays > MAX_OVERLAYS) error("$overlays layers on one block")
                if (overlays > 0) {
                    chunk.overlays[i] = List(overlays) {
                        val state = states.entry(varint(input), "block")
                        val bits = input.readUnsignedByte()
                        SharedOverlay(state, (bits and 15).toByte(), bits and 16 != 0, input.readUnsignedByte())
                    }
                }
            }
            val region = into.getOrPut((cx shr 5) to (cz shr 5)) { Region(cx shr 5, cz shr 5) }
            region.chunks[(cz and 31) * 32 + (cx and 31)] = chunk
        }
    }

    /** Entry [index] of a list read from the file, or "the file is damaged" if there is no such entry. */
    private fun <T> List<T>.entry(index: Int, what: String): T =
        getOrNull(index) ?: error("the file is damaged ($what $index of $size)")

    /** A block by its name and properties; one this game does not know becomes air. */
    private fun parseState(text: String): BlockState = try {
        BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState()
    } catch (e: Exception) {
        Blocks.AIR.defaultBlockState()
    }

    /** Leaving the server: files opened there were for its map. */
    fun forget() {
        opened.clear()
    }
}
