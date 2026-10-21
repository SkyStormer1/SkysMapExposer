package com.skystormer.skysmapexposer

/** Which of Xaero's layers BlueMap's picture belongs in ([XaeroWriter.blueMapLayer]); no Minecraft or Xaero classes, for tests. */
object MapLayers {

    /** Xaero's layer number for the surface. */
    const val SURFACE = Int.MAX_VALUE

    /**
     * The surface while Xaero shows the surface; Xaero's cave layer [shownLayer] in a dimension
     * with a ceiling whose BlueMap terrain is on (its map is of the inside); otherwise null.
     */
    fun blueMapLayer(shownLayer: Int, terrainUsed: Boolean, hasCeiling: () -> Boolean): Int? = when {
        shownLayer == SURFACE -> SURFACE
        terrainUsed && hasCeiling() -> shownLayer
        else -> null
    }
}
