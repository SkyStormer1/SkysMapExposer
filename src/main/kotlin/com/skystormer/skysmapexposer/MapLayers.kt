package com.skystormer.skysmapexposer

/** Which of Xaero's layers BlueMap's picture belongs in ([XaeroWriter.blueMapLayer]); no Minecraft or Xaero classes, for tests. */
object MapLayers {

    /** Xaero's layer number for the surface. */
    const val SURFACE = Int.MAX_VALUE

    /**
     * What a BlueMap map of a dimension with a ceiling (the Nether) shows. Most servers leave
     * BlueMap drawing the roof; some cut it off at a height to show the inside. The two belong in
     * different layers of Xaero's map: the roof in its surface, the inside in its cave layers.
     */
    enum class View { ROOF, INSIDE }

    /**
     * Heights from which ground counts as the roof: the Nether's roof is bedrock up to y 127, and
     * nothing inside reaches this high but the odd pillar. A map cut off to show the inside stops
     * below it.
     */
    const val ROOF_FROM = 120

    /** What a map shows, from the heights of the ground in its tiles; null with none to go by. */
    fun viewOf(heights: IntArray): View? = when {
        heights.isEmpty() -> null
        heights.count { it >= ROOF_FROM } * 2 >= heights.size -> View.ROOF
        else -> View.INSIDE
    }

    /**
     * The layer BlueMap's picture is drawn over while Xaero shows [shownLayer], or null if none.
     * Without a ceiling, the surface only: BlueMap has nothing for caves. With one, the roof goes
     * over the surface and the inside over the cave layer on screen, and nothing while it is not
     * yet known which the map shows ([view] null), so neither is ever covered with the other.
     */
    fun blueMapLayer(shownLayer: Int, hasCeiling: () -> Boolean, view: () -> View?): Int? {
        if (!hasCeiling()) return SURFACE.takeIf { shownLayer == SURFACE }
        return when (view()) {
            View.ROOF -> SURFACE.takeIf { shownLayer == SURFACE }
            View.INSIDE -> shownLayer.takeIf { shownLayer != SURFACE }
            null -> null
        }
    }

    /**
     * Where a download goes while Xaero shows [shownLayer]: the layer, and a note for the player.
     * A null layer means not at all, and the note says why.
     */
    fun downloadLayer(shownLayer: Int, hasCeiling: Boolean, view: View?): Pair<Int?, String?> = when {
        // Caves elsewhere: BlueMap has only the surface, so that is where it goes.
        !hasCeiling -> SURFACE to null
        view == null -> null to "Still checking whether BlueMap's map here shows the roof or the inside; try again in a moment"
        view == View.ROOF -> SURFACE to if (shownLayer == SURFACE) null else
            "BlueMap's map here shows the roof, so it goes into Xaero's roof layer; the inside, which Xaero is showing, is left as it is"
        shownLayer == SURFACE -> null to
            "BlueMap's map here shows the inside, not the roof Xaero's world map is showing; switch it to the cave view and try again"
        else -> shownLayer to null
    }
}
