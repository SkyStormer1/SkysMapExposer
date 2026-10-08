package com.skystormer.skysmapexposer

import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether what your map shows of a chunk is from before the season began: before its dimension's
 * [Config.Server.coverBefore] date ("Cover map before" in the settings).
 *
 * A map left over from an old season still has that season's land and biomes, so downloads and
 * maps shared with you write over it even when only filling what you have not explored
 * ([XaeroWriter.write]), sharing your map leaves it out ([ShareFormat.Collector]), and
 * [MapClear] can take it off your map altogether.
 *
 * A chunk's date is when you last had it loaded ([VisitLog], kept since this mod was installed),
 * or else the date of Xaero's region file, which is the newest thing anywhere in its 512 × 512
 * blocks. So a chunk this mod has never seen counts as this season's if anything near it was
 * mapped this season.
 *
 * Region dates are taken the first time a region is asked about and kept, so one made for a job
 * still knows a region's date after the job has written into it. Client thread only, as
 * [VisitLog] is.
 */
class MapAge private constructor(
    private val visits: VisitLog,
    private val dimensionId: String,
    private val folder: Path,
    /** The season start, in [Clock] minutes. */
    val seasonStart: Int,
) {
    private val regionMinutes = HashMap<Long, Int>()

    /** Whether chunk ([chunkX], [chunkZ]) was last mapped before [seasonStart]. */
    fun isOld(chunkX: Int, chunkZ: Int): Boolean {
        val seen = visits.lastSeen(dimensionId, chunkX, chunkZ)
        if (seen != 0) return seen < seasonStart
        val region = regionMinute(chunkX shr 5, chunkZ shr 5)
        return region != 0 && region < seasonStart
    }

    /**
     * Whether region ([regionX], [regionZ]) may hold chunks from before [seasonStart]: its file
     * is older, or a chunk in it was last seen before then.
     */
    fun mayHoldOld(regionX: Int, regionZ: Int): Boolean {
        val region = regionMinute(regionX, regionZ)
        if (region == 0) return false
        if (region < seasonStart) return true
        for (i in 0 until 32 * 32) {
            val seen = visits.lastSeen(dimensionId, regionX * 32 + i / 32, regionZ * 32 + i % 32)
            if (seen != 0 && seen < seasonStart) return true
        }
        return false
    }

    /** The minute Xaero's file for the region was last written, or 0 if there is none. */
    private fun regionMinute(regionX: Int, regionZ: Int): Int =
        regionMinutes.getOrPut((regionX.toLong() shl 32) or (regionZ.toLong() and 0xFFFFFFFFL)) {
            try {
                val file = folder.resolve(MapBackup.regionFile(regionX, regionZ))
                if (Files.exists(file)) Clock.minutesOf(Files.getLastModifiedTime(file).toMillis()) else 0
            } catch (e: Exception) {
                0
            }
        }

    companion object {
        /**
         * Season dates for [dimensionId], whose regions (of the layer being read or written) are in
         * [folder]; null when no "Cover map before" date is set for it, or this server is not set up.
         */
        fun of(dimensionId: String, folder: Path): MapAge? {
            val session = Session.current ?: return null
            val start = session.server.coverBefore[dimensionId]?.let(Clock::minutesOf) ?: return null
            return MapAge(session.visits, dimensionId, folder, start)
        }
    }
}
