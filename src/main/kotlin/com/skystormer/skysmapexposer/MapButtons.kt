package com.skystormer.skysmapexposer

/**
 * Where this mod's button goes in the top-left corner of Xaero's world map.
 *
 * Xaero has its own settings button up there, and other mods add theirs — Sky's Map Shapes puts a
 * Shapes button in the same column. Rather than guess a spot and hope, [firstFreeSlot] is given
 * everything already on the screen and picks the first gap below them.
 *
 * Kept apart from the button itself so the arithmetic can be tested without a game.
 */
object MapButtons {

    const val WIDTH = 46
    const val HEIGHT = 20

    /** Clear of Xaero's own settings button, which sits at the very top of that edge. */
    const val FIRST = 32

    const val GAP = 2

    /** How far down the edge to look before giving up. */
    const val MAX_SLOTS = 8

    /**
     * The top of the first gap down the left edge with room for a [WIDTH] by [HEIGHT] button.
     *
     * @param occupied everything already on the screen, each as `(x, y, width, height)`. Anything
     *   that does not reach into the left edge's column is ignored.
     * @return that gap's y, or [FIRST] if the column is packed solid for [MAX_SLOTS] — overlapping
     *   something beats sliding off the bottom of the screen.
     */
    fun firstFreeSlot(occupied: List<IntArray>): Int {
        var y = FIRST
        repeat(MAX_SLOTS) {
            if (occupied.none { overlaps(it, y) }) return y
            y += HEIGHT + GAP
        }
        return FIRST
    }

    /** Whether [rect] `(x, y, width, height)` is in the way of a button placed at [y]. */
    private fun overlaps(rect: IntArray, y: Int): Boolean {
        val (rectX, rectY, rectWidth, rectHeight) = rect
        if (rectWidth <= 0 || rectHeight <= 0) return false
        return rectX < WIDTH && rectX + rectWidth > 0 && rectY < y + HEIGHT && rectY + rectHeight > y
    }

    private operator fun IntArray.component1() = this[0]
    private operator fun IntArray.component2() = this[1]
    private operator fun IntArray.component3() = this[2]
    private operator fun IntArray.component4() = this[3]
}
