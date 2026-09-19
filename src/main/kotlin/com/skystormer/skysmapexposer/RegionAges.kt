package com.skystormer.skysmapexposer

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * The last-modified date of each of Xaero's region files in one map folder.
 *
 * This dates map data that Xaero recorded before this mod was installed, which [VisitLog] cannot
 * know about. A region file covers 512×512 blocks and is rewritten whenever any chunk in it
 * changes, so its date is the newest thing in it: good enough to say "this whole area is old", and
 * never wrongly says an area is older than it is.
 *
 * The folder is listed off the client thread, at most once a minute, and only while the map is
 * being looked at. Until the first listing arrives nothing is known, and [minuteOf] says so.
 */
class RegionAges(private val io: Executor) {

    private class Listing(val folder: Path, val minutes: Long2IntOpenHashMap, val takenAt: Long)

    @Volatile
    private var listing: Listing? = null

    @Volatile
    private var listingInProgress = false

    /** Whether [folder] has been listed yet. */
    fun isKnown(folder: Path): Boolean = listing?.folder == folder

    /**
     * The minute region ([regionX], [regionZ]) was last written, or 0 if there is no such file.
     * Only meaningful once [isKnown] is true for [folder].
     */
    fun minuteOf(folder: Path, regionX: Int, regionZ: Int): Int {
        refreshIfDue(folder)
        val current = listing ?: return 0
        if (current.folder != folder) return 0
        return current.minutes.get(key(regionX, regionZ))
    }

    fun count(): Int = listing?.minutes?.size ?: 0

    private fun refreshIfDue(folder: Path) {
        val current = listing
        val due = current == null || current.folder != folder ||
            System.currentTimeMillis() - current.takenAt > 60_000L
        if (!due || listingInProgress) return
        listingInProgress = true
        io.execute {
            try {
                listing = Listing(folder, list(folder), System.currentTimeMillis())
            } finally {
                listingInProgress = false
            }
        }
    }

    private fun list(folder: Path): Long2IntOpenHashMap {
        val minutes = Long2IntOpenHashMap()
        minutes.defaultReturnValue(0)
        if (!Files.isDirectory(folder)) {
            return minutes
        }
        try {
            Files.newDirectoryStream(folder, "*.zip").use { files ->
                for (file in files) {
                    val coordinates = REGION_NAME.matchEntire(file.fileName.toString()) ?: continue
                    val regionX = coordinates.groupValues[1].toInt()
                    val regionZ = coordinates.groupValues[2].toInt()
                    minutes.put(key(regionX, regionZ), Clock.minutesOf(Files.getLastModifiedTime(file).toMillis()))
                }
            }
        } catch (e: Exception) {
            Log.error("Could not list Xaero's region files in $folder", e)
        }
        return minutes
    }

    private fun key(regionX: Int, regionZ: Int): Long =
        (regionX.toLong() and 0xFFFFFFFFL) or (regionZ.toLong() shl 32)

    private companion object {
        val REGION_NAME = Regex("(-?\\d+)_(-?\\d+)\\.zip")
    }
}
