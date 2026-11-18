package com.skystormer.skysmapexposer.xaero

import com.skystormer.skysmapexposer.ChatButton
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.SharedTerrain
import com.skystormer.skysmapexposer.Ui
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import com.skystormer.skysmapexposer.ShareFormat.Step
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Sharing terrain by file on Xaero's map, as chat ([com.skystormer.skysmapexposer.ChatShare]) is far
 * too small for it: the chunks picked on the world map are saved, every block exactly as Xaero keeps
 * it, to a [SharedTerrain] file for you to send however you like. Whoever gets it drops it onto
 * their game window, and picks whether it fills only the chunks they have never mapped or replaces
 * their own map there too; each region is backed up just before it is changed
 * ([BlueMapDownload.startShared]). Regions are read back one at a time as they are written into the
 * map, so a big file is never all in memory.
 */
object TerrainFiles {

    /** Regions packed but not yet in the file; reading waits while this many are. */
    private const val QUEUED = 2
    /** Regions of an opened file read ahead of the one being written into your map. */
    private const val AHEAD = 2

    // ---- saving ----

    private class Saving(val reader: TerrainFormat.RegionReader, val part: Path, val file: Path, val out: DataOutputStream) {
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
        val (reader, why) = TerrainFormat.RegionReader.plan(me, left, top, right, bottom)
        reader ?: return why!!
        val area = reader.area
        return try {
            val (file, part) = SharedTerrain.newFile(reader.dimensionId, area)
            val out = DataOutputStream(BufferedOutputStream(Files.newOutputStream(part)))
            SharedTerrain.startFile(out, me, reader.dimensionId, reader.layer, area)
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
        Ui.say("Saving stopped: $message")
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
            is Step.Waiting -> if (current.ticks % 40 == 0) Ui.say("Saving terrain: ${current.reader.progress}")
            is Step.Failed -> fail(current, step.message)
            is Step.Done -> finish(current)
            is Step.Ready -> {
                val (rx, rz) = current.reader.regionsOf(step.batch).single()
                current.queued.incrementAndGet()
                worker.execute {
                    try {
                        if (current.error == null && !current.cancelled) {
                            SharedTerrain.writeRegion(current.out, rx, rz, step.count, step.bytes)
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
                Minecraft.getInstance().execute { Ui.say("Saving stopped: could not write the file (${e.message ?: e.javaClass.simpleName})") }
                return@execute
            }
            Minecraft.getInstance().execute { saved(current, chunks, skipped, old, size) }
        }
    }

    private fun saved(current: Saving, chunks: Int, skipped: Int, old: Int, size: Long) {
        if (size < 0) {
            Ui.say(SharedTerrain.nothingSaved(old, "Nothing of your map there to save: you have not mapped those chunks"))
            return
        }
        Log.info("Saved the terrain of {} chunks ({} regions, {} bytes, {} old-season chunks left out) to {}", chunks, current.regions.get(), size, old, current.file)
        val skippedNote = if (skipped > 0) " ($skipped regions Xaero did not load were left out — see the log)" else ""
        Minecraft.getInstance().player?.sendSystemMessage(SharedTerrain.savedMessage(chunks, current.file, size, old, skippedNote))
    }

    // ---- adding ----

    /**
     * Adds opened file [id] to your map; with [unexploredOnly], only into chunks you have never
     * mapped. Returns what to tell the player, or null when it has asked in chat instead.
     *
     * A file writes into the cave layer it was saved from. If the world map shows another layer of
     * that dimension, the import would work but show nothing there, so unless [confirmed] this asks
     * first.
     */
    fun add(file: SharedTerrain.Opened, id: Int, unexploredOnly: Boolean, confirmed: Boolean = false): String? {
        val shown = XaeroWriter.shown().first
        if (!confirmed && shown != null && shown.dimensionId == file.dimensionId && shown.layer != file.layer) {
            val name = Ui.dimensionName(file.dimensionId) ?: file.dimensionId
            val command = "/mapexposer shared $id ${if (unexploredOnly) "blank" else "all"} confirm"
            Minecraft.getInstance().player?.sendSystemMessage(
                Component.literal("§eThis map is of ${SharedTerrain.layerName(file.layer)} of the $name, but the world map is showing ${SharedTerrain.layerName(shown.layer)}. " +
                    "It goes into ${SharedTerrain.layerName(file.layer)}, so you won't see it until the map shows that.§r  ")
                    .append(ChatButton.of("[Add anyway]", command, "Adds it to ${SharedTerrain.layerName(file.layer)}."))
            )
            return null
        }
        val unreadable = AtomicInteger()
        val ahead = ReadAhead(file, unreadable)
        val regions = file.entries.mapIndexed { index, entry -> Triple(entry.regionX, entry.regionZ) { ahead.get(index) } }
        return BlueMapDownload.startShared(regions, file.dimensionId, file.layer, file.from, unexploredOnly) {
            val count = unreadable.get()
            if (count == 0) "" else ", $count region${if (count == 1) "" else "s"} could not be read (the file may be damaged)"
        }
    }

    /**
     * Reads [file]'s regions on [worker], a few ahead of the one being written, so that the
     * client thread only ever picks up a region that is ready. Used on the client thread only.
     */
    private class ReadAhead(val file: SharedTerrain.Opened, val unreadable: AtomicInteger) {
        private val reads = HashMap<Int, Future<XaeroWriter.Pixels>>()

        /** Region [index], or null while it is still being read. */
        fun get(index: Int): XaeroWriter.Pixels? {
            for (i in index..minOf(index + AHEAD, file.entries.lastIndex)) {
                reads.getOrPut(i) { worker.submit(Callable { region(file, file.entries[i], unreadable) }) }
            }
            val read = reads.getValue(index)
            if (!read.isDone) return null
            reads.remove(index)
            return read.get()
        }
    }

    /** One region of [file], read when it is about to be written; nothing if it cannot be read, counted in [unreadable]. */
    private fun region(file: SharedTerrain.Opened, entry: SharedTerrain.Entry, unreadable: AtomicInteger): XaeroWriter.Pixels = try {
        TerrainFormat.SharedRegion(SharedTerrain.region(file, entry))
    } catch (e: Exception) {
        Log.error("Could not read region ${entry.regionX}_${entry.regionZ} of ${file.path}; left it out", e)
        unreadable.incrementAndGet()
        TerrainFormat.SharedRegion(SharedTerrain.Region(entry.regionX, entry.regionZ))
    }

    /** Leaving the server: a half-saved file is no use, and files opened there were for its map. */
    fun forget() {
        saving?.let(::abandon)
    }
}
