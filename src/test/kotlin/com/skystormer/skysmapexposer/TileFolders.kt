package com.skystormer.skysmapexposer

import java.nio.file.Files
import java.nio.file.Path

/** BlueMap's finest lowres tiles from folders on disk, for the biome guide tools. */
internal object TileFolders {
    const val TILE = 500

    /** The ground over tiles named `x_z.png` or `t_x_z.png` in any of [dirs]. */
    fun ground(dirs: List<Path>) = LowresSource(TILE, hasSea = true) { tx, tz -> load(dirs, tx, tz) }

    private fun load(dirs: List<Path>, tx: Int, tz: Int): BlueMapPicture.Tile? {
        for (dir in dirs) {
            for (name in listOf("${tx}_$tz.png", "t_${tx}_$tz.png")) {
                val file = dir.resolve(name)
                if (Files.exists(file)) return BlueMapPicture.decode(tx, tz, TILE, Files.readAllBytes(file))
            }
        }
        return null
    }
}
