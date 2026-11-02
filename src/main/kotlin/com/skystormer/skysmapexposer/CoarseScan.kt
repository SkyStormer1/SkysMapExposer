package com.skystormer.skysmapexposer

/**
 * Where BlueMap has anything in a whole world, read off its coarsest tiles: a few tiles for a
 * whole world, each saying which finest tiles under it have something. Scanned ring by ring from
 * the middle outwards until a whole ring has nothing, so a world of any size is covered and the
 * empty beyond its border costs one ring. Used by "download everything" and by the background
 * preparation of the map ([MapPrep]).
 */
object CoarseScan {

    /**
     * @property finest the finest tiles that have anything, as (x, z).
     * @property rings how many rings of coarsest tiles were read, the middle one counted.
     * @property reach blocks from the middle tile's edge to the edge of the last ring read.
     * @property limited whether it stopped at [MAX_TILES] rather than at an empty ring.
     */
    class Result(val finest: Set<Pair<Int, Int>>, val rings: Int, val reach: Long, val limited: Boolean) {
        override fun toString() =
            "${finest.size} finest tiles with something, ${rings} ring${if (rings == 1) "" else "s"} of coarsest tiles read " +
                "(±$reach blocks)${if (limited) ", stopped at the limit of $MAX_TILES coarsest tiles" else ""}"
    }

    /** Coarsest tiles read at most; far more than any world border needs. */
    const val MAX_TILES = 4096

    /** Rings read even when nothing has been found yet, as the old fixed ±4 did. */
    const val MIN_RINGS = 5

    /**
     * Scans from the coarsest tile holding block ([centreX], [centreZ]). [fetch] gives a coarsest
     * tile's PNG, or null where BlueMap has nothing. Returns null if [cancelled] says so part way.
     */
    fun scan(
        layout: BlueMap.MapLayout, centreX: Int, centreZ: Int, cancelled: () -> Boolean,
        fetch: (tileX: Int, tileZ: Int) -> ByteArray?,
    ): Result? {
        val lod = layout.lodCount
        val span = layout.tileBlocks(lod)
        val blocksPerPixel = layout.blocksPerPixel(lod)
        val middleX = Math.floorDiv(centreX, span)
        val middleZ = Math.floorDiv(centreZ, span)
        val finest = HashSet<Pair<Int, Int>>()
        var read = 0
        var ring = 0
        while (true) {
            var found = false
            for ((dx, dz) in ring(ring)) {
                if (cancelled()) return null
                if (read >= MAX_TILES) return Result(finest, ring, ring.toLong() * span, limited = true)
                read++
                val tx = middleX + dx
                val tz = middleZ + dz
                val bytes = fetch(tx, tz) ?: continue
                val before = finest.size
                BlueMapPicture.finestTilesIn(BlueMapPicture.decode(tx, tz, layout.tileSize, bytes), blocksPerPixel, layout.tileSize, finest)
                if (finest.size > before) found = true
            }
            ring++
            if (!found && ring >= MIN_RINGS) {
                return Result(finest, ring, (ring - 1).toLong() * span, limited = false)
            }
        }
    }

    /** The tiles [r] steps out from the middle one, all the way round; the middle one alone for 0. */
    fun ring(r: Int): List<Pair<Int, Int>> {
        if (r == 0) return listOf(0 to 0)
        val out = ArrayList<Pair<Int, Int>>(8 * r)
        for (x in -r..r) {
            out.add(x to -r)
            out.add(x to r)
        }
        for (z in -r + 1 until r) {
            out.add(-r to z)
            out.add(r to z)
        }
        return out
    }
}
