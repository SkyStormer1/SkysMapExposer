package com.skystormer.skysmapexposer

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import com.skystormer.skysmapexposer.ShareFormat.Step
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom

/**
 * Sharing terrain by file, which is far too big for chat ([ChatShare]): the chunks picked on the
 * world map are saved, every block exactly as Xaero keeps it, to a `.smemap` file in the game
 * folder's `shared maps` folder (next to `screenshots`), for you to send however you like. Whoever
 * gets it drops it onto their game window, and picks whether it fills only the chunks they have
 * never mapped or replaces their own map there too; each region is backed up just before it is
 * changed ([BlueMapDownload.startShared]).
 *
 * The file: [MAGIC], [VERSION], who saved it, the dimension, Xaero's cave layer it was read from
 * (version 2 on; version 1 files are the surface), the area and when; then one entry per
 * region — its position, chunk count and byte length, then the batch itself ([ShareFormat],
 * checksummed). Regions are read back one at a time as they are written into the map, so a big
 * file is never all in memory.
 */
object TerrainFiles {

    const val EXTENSION = ".smemap"
    private val MAGIC = "SMEMAP".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 2
    /** Regions packed but not yet in the file; reading waits while this many are. */
    private const val QUEUED = 2
    /** Regions of an opened file read ahead of the one being written into your map. */
    private const val AHEAD = 2
    private const val MAX_ENTRY = 64 shl 20
    private const val KEEP_OPENED = 5

    val folder: Path get() = FabricLoader.getInstance().gameDir.resolve("shared maps")

    // ---- saving ----

    private class Saving(val reader: ShareFormat.RegionReader, val part: Path, val file: Path, val out: DataOutputStream) {
        var ticks = 0
        /** Regions in the file so far, and still to go in; both counted on [worker]. */
        val regions = AtomicInteger()
        val queued = AtomicInteger()
        @Volatile var error: Exception? = null
        @Volatile var cancelled = false
    }

    /**
     * Packing a region and writing it to the file, and reading an opened file's regions back:
     * slow, and none of it needs Xaero, so it is done here rather than on the client thread.
     */
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Sky's Map Exposer map file").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private var saving: Saving? = null

    val busy: Boolean get() = saving != null

    /** Starts saving the terrain of chunks [left]..[right], [top]..[bottom] (inclusive) to a file. Returns what to tell the player. */
    fun save(left: Int, top: Int, right: Int, bottom: Int): String {
        if (saving != null) return "A map is already being saved to a file; /mapexposer terrain cancel stops it"
        val me = Minecraft.getInstance().player?.gameProfile?.name() ?: "?"
        val (reader, why) = ShareFormat.RegionReader.plan(true, me, left, top, right, bottom)
        reader ?: return why!!
        val area = reader.area
        val name = MapMenus.dimensionName(reader.dimensionId) ?: reader.dimensionId.substringAfter(':')
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"))
        val base = "$name near ${area.x} ${area.z} $stamp".replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return try {
            Files.createDirectories(folder)
            val file = folder.resolve(base + EXTENSION)
            val part = folder.resolve("$base$EXTENSION.part")
            val out = DataOutputStream(BufferedOutputStream(Files.newOutputStream(part)))
            out.write(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(me)
            out.writeUTF(reader.dimensionId)
            out.writeInt(reader.layer)
            ShareFormat.writeArea(out, area)
            out.writeLong(System.currentTimeMillis())
            saving = Saving(reader, part, file, out)
            Log.info("Saving the terrain of {} regions (cave layer {}) to {}", reader.batches, reader.layer, file)
            "Saving the terrain of ${area.width} × ${area.height} chunks to a file…"
        } catch (e: Exception) {
            Log.error("Could not start saving terrain to a file", e)
            "Could not save to the shared maps folder: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun cancel(): Boolean {
        val current = saving ?: return false
        abandon(current)
        Log.info("Saving terrain to {} cancelled", current.file)
        return true
    }

    private fun abandon(current: Saving) {
        saving = null
        current.cancelled = true
        worker.execute {
            runCatching { current.out.close() }
            runCatching { Files.deleteIfExists(current.part) }
        }
    }

    private fun fail(current: Saving, message: String) {
        abandon(current)
        MapMenus.say("Saving stopped: $message")
        Log.info("Saving terrain stopped: {}", message)
    }

    fun tick() {
        val current = saving ?: return
        current.ticks++
        current.error?.let { e ->
            Log.error("Could not save terrain to ${current.part}", e)
            return fail(current, "could not write the file (${e.message ?: e.javaClass.simpleName})")
        }
        // The file is behind: let it catch up rather than hold more packed regions in memory.
        if (current.queued.get() >= QUEUED) return
        when (val step = current.reader.next()) {
            is Step.Waiting -> if (current.ticks % 40 == 0) MapMenus.say("Saving terrain: ${current.reader.progress}")
            is Step.Failed -> fail(current, step.message)
            is Step.Done -> finish(current)
            is Step.Ready -> {
                val (rx, rz) = current.reader.regionsOf(step.batch).single()
                current.queued.incrementAndGet()
                worker.execute {
                    try {
                        if (current.error == null && !current.cancelled) {
                            val bytes = step.bytes
                            current.out.writeInt(rx)
                            current.out.writeInt(rz)
                            current.out.writeInt(step.count)
                            current.out.writeInt(bytes.size)
                            current.out.write(bytes)
                            current.regions.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        current.error = e
                    } finally {
                        current.queued.decrementAndGet()
                    }
                }
            }
        }
    }

    private fun finish(current: Saving) {
        saving = null
        val chunks = current.reader.count
        val skipped = current.reader.skippedRegions
        val old = current.reader.oldChunks
        worker.execute {
            val size = try {
                current.out.close()
                current.error?.let { throw it }
                if (chunks == 0) {
                    Files.deleteIfExists(current.part)
                    -1L
                } else {
                    Files.move(current.part, current.file, StandardCopyOption.REPLACE_EXISTING)
                    Files.size(current.file)
                }
            } catch (e: Exception) {
                Log.error("Could not save terrain to ${current.part}", e)
                runCatching { Files.deleteIfExists(current.part) }
                Minecraft.getInstance().execute { MapMenus.say("Saving stopped: could not write the file (${e.message ?: e.javaClass.simpleName})") }
                return@execute
            }
            Minecraft.getInstance().execute { saved(current, chunks, skipped, old, size) }
        }
    }

    private fun saved(current: Saving, chunks: Int, skipped: Int, old: Int, size: Long) {
        if (size < 0) {
            MapMenus.say(
                if (old > 0) "Nothing of your map there to save: all $old chunks you have there are from before the season began"
                else "Nothing of your map there to save: you have not mapped those chunks"
            )
            return
        }
        Log.info("Saved the terrain of {} chunks ({} regions, {} bytes, {} old-season chunks left out) to {}", chunks, current.regions.get(), size, old, current.file)
        val note = (if (skipped > 0) " ($skipped regions Xaero did not load were left out — see the log)" else "") +
            (if (old > 0) " ($old chunks from before the season began were left out)" else "")
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal("Saved the terrain of $chunks chunks to §b${current.file.fileName}§r (${megabytes(size)})$note  ")
                .append(Component.literal("[Open folder]").withStyle { style ->
                    style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(ClickEvent.OpenFile(folder))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal(
                            "Opens the shared maps folder. Send the file to whoever you like; they drop it onto their game window to add it."
                        )))
                })
        )
    }

    private fun megabytes(bytes: Long): String =
        if (bytes < 1 shl 20) "${(bytes + 1023) / 1024} KB" else "%.1f MB".format(bytes / (1024.0 * 1024.0))

    // ---- opening ----

    private class Entry(val regionX: Int, val regionZ: Int, val chunks: Int, val offset: Long, val length: Int)

    private class Opened(val path: Path, val from: String, val dimensionId: String, val layer: Int, val area: ShareFormat.Area, val entries: List<Entry>) {
        val chunks get() = entries.sumOf { it.chunks }
    }

    private val opened = LinkedHashMap<Int, Opened>()

    /**
     * Files dropped onto the game window: opens every `.smemap` one. True if all of [paths] were,
     * so nothing else needs to see the drop.
     */
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
        val name = MapMenus.dimensionName(file.dimensionId) ?: file.dimensionId
        Log.info("Opened {} as {}: {} chunks of the {} (cave layer {}) from {}", path, id, file.chunks, file.dimensionId, file.layer, file.from)
        player.sendSystemMessage(
            Component.literal("§b${file.from}§r's terrain: ${file.chunks} chunks of the $name near ${file.area.x}, ${file.area.z}  ")
                .append(ChatShare.button("[Add to unexplored only]", "/mapexposer shared $id blank", "Only fills chunks you have never mapped."))
                .append(Component.literal(" "))
                .append(ChatShare.button("[Add all]", "/mapexposer shared $id all", "Also replaces your own map there. Each region is backed up first."))
        )
    }

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
            val layer = if (version >= 2) input.readInt() else XaeroWriter.SURFACE
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

    /** Adds opened file [id] to your map; with [unexploredOnly], only into chunks you have never mapped. Returns what to tell the player. */
    fun add(id: Int, unexploredOnly: Boolean): String {
        val file = opened[id] ?: return "That map file is no longer open; drop it onto the game again"
        val ahead = ReadAhead(file)
        val regions = file.entries.mapIndexed { index, entry -> Triple(entry.regionX, entry.regionZ) { ahead.get(index) } }
        return BlueMapDownload.startShared(regions, file.dimensionId, file.layer, file.from, unexploredOnly)
    }

    /**
     * Reads [file]'s regions on [worker], a few ahead of the one being written, so that the
     * client thread only ever picks up a region that is ready. Used on the client thread only.
     */
    private class ReadAhead(val file: Opened) {
        private val reads = HashMap<Int, Future<XaeroWriter.Pixels>>()

        /** Region [index], or null while it is still being read. */
        fun get(index: Int): XaeroWriter.Pixels? {
            for (i in index..minOf(index + AHEAD, file.entries.lastIndex)) {
                reads.getOrPut(i) { worker.submit(Callable { region(file, file.entries[i]) }) }
            }
            val read = reads.getValue(index)
            if (!read.isDone) return null
            reads.remove(index)
            return read.get()
        }
    }

    /** One region of [file], read when it is about to be written; nothing if it cannot be read. */
    private fun region(file: Opened, entry: Entry): XaeroWriter.Pixels {
        val empty = ShareFormat.SharedRegion(entry.regionX, entry.regionZ)
        return try {
            val bytes = ByteArray(entry.length)
            RandomAccessFile(file.path.toFile(), "r").use { input ->
                input.seek(entry.offset)
                input.readFully(bytes)
            }
            val regions = HashMap<Pair<Int, Int>, ShareFormat.SharedRegion>()
            ShareFormat.open(bytes) { batch ->
                if (batch.format != ShareFormat.TERRAIN) error("not terrain")
                ShareFormat.readTerrain(batch.input, batch.count, regions)
            }
            regions[entry.regionX to entry.regionZ] ?: empty
        } catch (e: Exception) {
            Log.error("Could not read region ${entry.regionX}_${entry.regionZ} of ${file.path}; left it out", e)
            empty
        }
    }

    /** Leaving the server: a half-saved file is no use, and files opened there were for its map. */
    fun forget() {
        saving?.let(::abandon)
        opened.clear()
    }
}
