package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.ShareFormat.unzigzag
import com.skystormer.skysmapexposer.ShareFormat.varint
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom

/**
 * Terrain shared by file (`.smemap`, in the game folder's `shared maps` folder), for either map:
 * opening one dropped onto the game ([dropped], [open]), and reading it back a region at a time
 * ([region]), and the format they are written in ([startFile], [Writer]), for either map's saving;
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

    /**
     * The most a region may unpack to: far more than a full region of the busiest terrain needs, so
     * a file made to unpack into gigabytes is refused rather than freezing the game.
     */
    private const val MAX_UNPACKED = 64 shl 20
    /** The widest a terrain file may be, in chunks: the whole world, border to border. */
    private const val MAX_FILE_SIDE = 3_750_000
    /** Saving more chunks a side than this asks first ([bigWarning]); the same as a share in chat. */
    const val WARN_SIDE = ShareFormat.MAX_SIDE
    /** Room kept free on the drive: a save does not start, or stops, rather than leave less. */
    private const val ROOM_SPARE = 256L shl 20
    private const val MAX_OVERLAYS = 16
    private const val KEEP_OPENED = 5
    private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")

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

    // ---- writing ----

    /**
     * A new file in the shared maps folder for terrain of [dimensionId] near [area], named for where
     * and when, and the `.part` file it is written to first and moved over it once whole.
     */
    fun newFile(dimensionId: String, area: ShareFormat.Area): Pair<Path, Path> {
        val name = Ui.dimensionName(dimensionId) ?: dimensionId.substringAfter(':')
        val base = "$name near ${area.x} ${area.z} ${LocalDateTime.now().format(FILE_STAMP)}".replace(Regex("[\\\\/:*?\"<>|]"), "_")
        Files.createDirectories(folder)
        return folder.resolve(base + EXTENSION) to folder.resolve("$base$EXTENSION.part")
    }

    /**
     * The chat line for a saved [file] of [chunks] chunks and [size] bytes, with how many old-season
     * chunks were left out, anything else to say ([extra]), and a button that opens the folder.
     */
    fun savedMessage(chunks: Int, file: Path, size: Long, oldLeftOut: Int, extra: String = ""): Component {
        val note = extra + if (oldLeftOut > 0) " ($oldLeftOut chunks from before the season began were left out)" else ""
        return Component.literal("Saved the terrain of $chunks chunks to §b${file.fileName}§r (${SharedTerrain.size(size)})$note  ")
            .append(Component.literal("[Open folder]").withStyle { style ->
                style.withColor(ChatFormatting.GREEN)
                    .withClickEvent(ClickEvent.OpenFile(folder))
                    .withHoverEvent(HoverEvent.ShowText(Component.literal(
                        "Opens the shared maps folder. Send the file to whoever you like; they drop it onto their game window to add it."
                    )))
            })
    }

    /** What asking for a save came to, on either map: what to tell the player, or a warning to [confirm] first. */
    class Answer(val text: String, val confirm: Boolean = false)

    /**
     * Answers a `terrain save` command with [answer]; a warning comes with a button that runs the
     * same command with `confirm` on the end.
     */
    fun reply(context: CommandContext<FabricClientCommandSource>, answer: Answer): Int {
        if (!answer.confirm) return Ui.reply(context, answer.text)
        val command = context.input.removePrefix("/").trim() + " confirm"
        context.source.sendFeedback(Component.literal(answer.text + "  ").append(ChatButton.of("[Save anyway]", "/$command", "Starts saving it")))
        return 1
    }

    /**
     * The regions of [wanted] (all of [files] when null) that [has] a file of, from [files], the
     * regions there are files of: by going through whichever is fewer, the area or the files, so a
     * huge area typed in costs no more than the map there is.
     */
    fun regionsIn(wanted: ShareFormat.Area?, files: Set<Pair<Int, Int>>, has: (Int, Int) -> Boolean = { x, z -> (x to z) in files }): List<Pair<Int, Int>> {
        if (wanted == null) return files.sortedWith(compareBy({ it.second }, { it.first }))
        val xs = Math.floorDiv(wanted.left, 32)..Math.floorDiv(wanted.right, 32)
        val zs = Math.floorDiv(wanted.top, 32)..Math.floorDiv(wanted.bottom, 32)
        val inArea = (xs.last - xs.first + 1).toLong() * (zs.last - zs.first + 1)
        if (inArea > files.size + 4096L) return files.filter { (x, z) -> x in xs && z in zs }.sortedWith(compareBy({ it.second }, { it.first }))
        return buildList { for (z in zs) for (x in xs) if (has(x, z)) add(x to z) }
    }

    /** The chunks spanning [regions]; null if there are none. */
    fun spanning(regions: Collection<Pair<Int, Int>>): ShareFormat.Area? {
        if (regions.isEmpty()) return null
        return ShareFormat.Area(
            regions.minOf { it.first } * 32, regions.minOf { it.second } * 32,
            regions.maxOf { it.first } * 32 + 31, regions.maxOf { it.second } * 32 + 31,
        )
    }

    /** The regions [folder] has a file of, by [pattern] (region x and z as its two groups); none if it cannot be read. */
    fun regionFiles(folder: Path, pattern: Regex): Set<Pair<Int, Int>> = try {
        Files.list(folder).use { files ->
            files.toList().mapNotNull { pattern.matchEntire(it.fileName.toString())?.destructured }
                .mapTo(HashSet()) { (x, z) -> x.toInt() to z.toInt() }
        }
    } catch (e: Exception) {
        emptySet()
    }

    /**
     * Before a save of [regions] regions: why it cannot go ahead, if the
     * drive the shared maps folder is on has too little room for it ([ROOM_SPARE] kept free besides).
     */
    fun noRoom(regions: Int): String? {
        val free = freeSpace() ?: return null
        val needed = estimate(regions)
        if (free >= needed + ROOM_SPARE) return null
        return "Not enough room on the drive for that: it may need about ${size(needed)}, and ${size(free)} is free"
    }

    /**
     * The warning before saving more than [WARN_SIDE] chunks a side: how much, how long, and the
     * room it may take. Null for a smaller area, which saves without asking.
     */
    fun bigWarning(width: Int, height: Int, regions: Int): String? {
        if (width <= WARN_SIDE && height <= WARN_SIDE) return null
        val free = freeSpace()?.let { ", and ${size(it)} is free" } ?: ""
        return "That is $width × $height chunks: $regions regions of your map. The file may be about " +
            "${size(estimate(regions))}$free. Saving takes a while (you can keep playing and stop it from " +
            "the map's right-click menu), and whoever gets the file needs a version of the mod from 1.10 on."
    }

    /** True once the drive is so full that a save going on should stop rather than fill it. */
    fun nearlyFull(): Boolean = (freeSpace() ?: Long.MAX_VALUE) < ROOM_SPARE

    private fun freeSpace(): Long? = try {
        Files.createDirectories(folder)
        Files.getFileStore(folder).usableSpace
    } catch (e: Exception) {
        null
    }

    /** About how big a file of [regions] full regions comes out (saves so far: 200 to 330 bytes a chunk). */
    private fun estimate(regions: Int): Long = regions * 1024L * 330

    /** [bytes] as KB, MB or GB. */
    fun size(bytes: Long): String = when {
        bytes < 1 shl 20 -> "${(bytes + 1023) / 1024} KB"
        bytes < 1L shl 30 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /** What to say when a save found nothing: all [oldLeftOut] chunks there were old-season, else [none]. */
    fun nothingSaved(oldLeftOut: Int, none: String): String =
        if (oldLeftOut > 0) "Nothing of your map there to save: all $oldLeftOut chunks you have there are from before the season began" else none

    /** Starts a terrain file: everything before the regions. */
    fun startFile(out: DataOutputStream, from: String, dimensionId: String, layer: Int, area: ShareFormat.Area) {
        out.write(MAGIC)
        out.writeInt(VERSION)
        out.writeUTF(from)
        out.writeUTF(dimensionId)
        out.writeInt(layer)
        ShareFormat.writeArea(out, area)
        out.writeLong(System.currentTimeMillis())
    }

    /** One region of a terrain file: where it is, its chunk count, then its batch. */
    fun writeRegion(out: DataOutputStream, regionX: Int, regionZ: Int, chunks: Int, batch: ByteArray) {
        out.writeInt(regionX)
        out.writeInt(regionZ)
        out.writeInt(chunks)
        out.writeInt(batch.size)
        out.write(batch)
    }

    /**
     * Packs chunks into one [ShareFormat.TERRAIN] batch: the blocks and biomes used, then per chunk
     * its position and its 256 columns (`x * 16 + z`). The one writer of the format, for either map.
     */
    class Writer {
        var chunks = 0
            private set
        private val states = LinkedHashMap<BlockState, Int>()
        private val biomes = LinkedHashMap<ResourceKey<Biome>, Int>()
        private val body = ByteArrayOutputStream()
        private val data = DataOutputStream(body)

        fun chunk(cx: Int, cz: Int, chunk: SharedChunk) {
            data.writeInt(cx)
            data.writeInt(cz)
            for (i in 0 until 256) {
                varint(data, stateIndex(chunk.state[i]))
                data.writeShort(chunk.height[i].toInt())
                varint(data, ShareFormat.zigzag(chunk.top[i] - chunk.height[i]))
                varint(data, chunk.biome[i]?.let { biomes.getOrPut(it) { biomes.size } + 1 } ?: 0)
                data.writeByte(lightAndGlow(chunk.light[i], chunk.glowing[i]))
                val overlays = chunk.overlays[i].orEmpty().take(MAX_OVERLAYS)
                varint(data, overlays.size)
                for (overlay in overlays) {
                    varint(data, stateIndex(overlay.state))
                    data.writeByte(lightAndGlow(overlay.light, overlay.glowing))
                    data.writeByte(overlay.opacity)
                }
            }
            chunks++
        }

        fun pack(header: ShareFormat.Header): ByteArray = ShareFormat.pack(ShareFormat.TERRAIN, header, chunks) { out ->
            varint(out, states.size)
            for (state in states.keys) out.writeUTF(BlockStateParser.serialize(state))
            varint(out, biomes.size)
            for (biome in biomes.keys) out.writeUTF(biome.identifier().toString())
            body.writeTo(out)
        }

        private fun stateIndex(state: BlockState?): Int = states.getOrPut(state ?: Blocks.AIR.defaultBlockState()) { states.size }

        private fun lightAndGlow(light: Byte, glowing: Boolean): Int = (light.toInt() and 15) or (if (glowing) 16 else 0)
    }

    // ---- opening ----

    private val opened = LinkedHashMap<Int, Opened>()

    /** File [id] opened lately, or null once it has been let go of. */
    fun opened(id: Int): Opened? = opened[id]

    /** The id of the map file opened last, if any is still open. */
    fun lastOpened(): Int? = opened.keys.lastOrNull()

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
            val area = ShareFormat.readArea(input, MAX_FILE_SIDE)
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
        ShareFormat.open(bytes, MAX_UNPACKED, MAX_FILE_SIDE) { batch ->
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
