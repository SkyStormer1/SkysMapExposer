package com.skystormer.skysmapexposer

/**
 * BlueMap's coarsest lowres tiles as an index of where its finest tiles have anything, so a
 * zoomed-out map does not ask the server for thousands of tiles beyond what BlueMap has rendered.
 * For Xaero's map and JourneyMap's alike. Client thread.
 */
object TileIndex {

    /** Ask the index first once more finest tiles than this are on screen. */
    const val ABOVE = 16L

    /** The coarsest tile over the finest tile ([tileX], [tileZ]). */
    fun over(session: Session, layout: BlueMap.MapLayout, mapId: String, tileX: Int, tileZ: Int): TileStore.Tile {
        val lod = layout.lodCount
        val ratio = layout.tileBlocks(lod) / layout.tileSize
        return session.tiles.get(
            TileStore.Key(mapId, lod, Math.floorDiv(tileX, ratio), Math.floorDiv(tileZ, ratio)), layout.blocksPerPixel(lod), layout.tileSize,
        )
    }

    /**
     * Whether BlueMap's coarsest tile says the finest tile ([tileX], [tileZ]) has anything in it:
     * null while that is not known yet.
     */
    fun says(session: Session, layout: BlueMap.MapLayout, mapId: String, tileX: Int, tileZ: Int): Boolean? {
        val ratio = layout.tileBlocks(layout.lodCount) / layout.tileSize
        val index = over(session, layout, mapId, tileX, tileZ)
        return when (index.state) {
            TileStore.State.LOADING -> null
            TileStore.State.EMPTY -> false
            TileStore.State.FAILED -> true // no index to go by: ask for the fine tile itself
            TileStore.State.READY -> {
                val localX = tileX - index.key.x * ratio
                val localZ = tileZ - index.key.z * ratio
                val known = index.finerPresence ?: ByteArray(ratio * ratio).also { index.finerPresence = it }
                val slot = localZ * ratio + localX
                if (known[slot] == UNKNOWN) {
                    val span = layout.tileSize / ratio
                    var any = false
                    search@ for (z in localZ * span until (localZ + 1) * span) {
                        for (x in localX * span until (localX + 1) * span) {
                            if (index.hasDataAt(x, z)) {
                                any = true
                                break@search
                            }
                        }
                    }
                    known[slot] = if (any) SOMETHING else NOTHING
                }
                known[slot] == SOMETHING
            }
        }
    }

    private const val UNKNOWN: Byte = 0
    private const val NOTHING: Byte = 1
    private const val SOMETHING: Byte = 2
}
