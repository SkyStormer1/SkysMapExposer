package com.skystormer.skysmapexposer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * Regions Xaero has a file for but cannot show: damaged, from another Xaero version, or given up
 * on while loading. Where Xaero has no region open the overlay dates a chunk by its region file, so
 * such a region would stay black rather than being covered by BlueMap. A region on screen close up
 * (where Xaero always loads what it shows) that Xaero has tried to open and not loaded for
 * [GIVE_UP_MS] counts as a gap, like a region Xaero never mapped, until its file changes. A region
 * Xaero has not tried yet is left alone, so nothing flashes over regions that are only loading.
 *
 * Only draws: nothing here writes to Xaero's files. Each region is logged once, so a damaged file
 * can be found and put back from a backup. Kept on disk, as Xaero only answers while zoomed in.
 * Client thread only.
 */
class UnshowableRegions(private val file: Path?, private val io: Executor) {

    /** What Xaero has of a region on screen. */
    enum class Seen {
        /** No region object: not tried yet. */
        NOTHING,
        LOADED,
        /** Tried but not loaded: loading, failed, or let go. */
        NOT_LOADED,
    }

    private class Watch(val fileMinute: Int, val since: Long, var lastSeen: Long)

    private val watching = HashMap<String, Watch>()

    /** Region to the minute of the file Xaero could not show. */
    private val regions = HashMap<String, Int>()

    private var changed = false

    init {
        read()
    }

    val count: Int get() = regions.size

    private fun key(folder: Path, x: Int, z: Int) = "$folder|${x}_$z"

    /**
     * What Xaero has of region ([x], [z]) of [folder], whose file was last written at
     * [fileMinute] (0: no file), seen on screen close up at [now].
     */
    fun observe(folder: Path, x: Int, z: Int, fileMinute: Int, seen: Seen, now: Long) {
        val key = key(folder, x, z)
        if (fileMinute == 0) {
            watching.remove(key)
            forget(key)
            return
        }
        regions[key]?.let { if (it != fileMinute) forget(key) } // the file changed: ask again
        when (seen) {
            Seen.NOTHING -> watching.remove(key)
            Seen.LOADED -> {
                watching.remove(key)
                forget(key)
            }
            Seen.NOT_LOADED -> {
                if (key in regions) return
                val watch = watching[key]
                if (watch == null || watch.fileMinute != fileMinute || now - watch.lastSeen > CONTINUOUS_MS) {
                    watching[key] = Watch(fileMinute, now, now)
                    return
                }
                watch.lastSeen = now
                if (now - watch.since < GIVE_UP_MS) return
                watching.remove(key)
                regions[key] = fileMinute
                changed = true
                Log.warn("Xaero can't show region {}_{} of {} (its file is there); BlueMap covers it. Putting it back from a backup may fix it", x, z, folder)
            }
        }
    }

    private fun forget(key: String) {
        if (regions.remove(key) != null) changed = true
    }

    /** Whether BlueMap covers region ([x], [z]) of [folder], whose file was last written at [fileMinute]. */
    fun covers(folder: Path, x: Int, z: Int, fileMinute: Int): Boolean {
        if (regions.isEmpty()) return false
        return regions[key(folder, x, z)] == fileMinute
    }

    fun save() {
        val path = file ?: return
        if (!changed) return
        changed = false
        val json = JsonObject()
        for ((key, minute) in regions) json.addProperty(key, minute)
        val text = json.toString()
        io.execute {
            try {
                SafeFiles.writeString(path, text)
            } catch (e: Exception) {
                Log.error("Could not save $path", e)
            }
        }
    }

    private fun read() {
        val path = file ?: return
        try {
            if (Files.notExists(path)) return
            for ((key, value) in JsonParser.parseString(Files.readString(path)).asJsonObject.entrySet()) regions[key] = value.asInt
        } catch (e: Exception) {
            Log.error("Could not read $path; starting afresh", e)
        }
    }

    companion object {
        const val GIVE_UP_MS = 30_000L
        /** Away from the screen longer than this, and the wait starts again. */
        const val CONTINUOUS_MS = 5_000L
    }
}
