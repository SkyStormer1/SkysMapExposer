package com.skystormer.skysmapexposer

/**
 * Under the world map's readout of the block under the mouse, the same block's coordinates in the
 * other dimension: viewing the Nether, the Overworld's (eight times as far); viewing the
 * Overworld, the Nether's (an eighth). Nothing for the End. Y is left out, as it does not carry over.
 * Drawn by `GuiMapMixin`; switched off with [Config.otherDimensionCoords].
 */
object OtherDimensionCoords {

    /**
     * The line for block ([x], [z]) in the dimension the map is showing (which need not be yours);
     * null when that is not the Overworld or the Nether.
     */
    @JvmStatic
    fun forWorldMap(x: Int, z: Int): String? = if (!Config.otherDimensionCoords) null else when (MarkerElements.worldMapDimension()) {
        "minecraft:the_nether" -> "Overworld X: ${x * 8} Z: ${z * 8}"
        "minecraft:overworld" -> "Nether X: ${Math.floorDiv(x, 8)} Z: ${Math.floorDiv(z, 8)}"
        else -> null
    }
}
