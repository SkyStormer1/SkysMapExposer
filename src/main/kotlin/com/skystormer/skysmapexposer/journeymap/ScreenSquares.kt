package com.skystormer.skysmapexposer.journeymap

import journeymap.api.v2.client.util.UIState
import journeymap.api.v2.common.Context

/**
 * The squares [span] blocks wide that cover what one of JourneyMap's maps shows ([areaOf]), by
 * number (blocks / [span]), and how far each is from the middle of the map. The terrain
 * ([JourneyMapTerrain]), the biome tint ([JourneyMapBiomes]) and the other maps
 * ([JourneyMapOtherMaps]) all fill in what is on screen this way, nearest the middle first.
 */
internal class ScreenSquares(area: DoubleArray, val span: Int) {
    val left = Math.floorDiv(area[0].toInt(), span)
    val top = Math.floorDiv(area[1].toInt(), span)
    val right = Math.floorDiv(area[2].toInt(), span)
    val bottom = Math.floorDiv(area[3].toInt(), span)
    private val centreX = (area[0] + area[2]) / 2
    private val centreZ = (area[1] + area[3]) / 2

    /** How many squares there are. */
    val count: Long get() = (right - left + 1).toLong() * (bottom - top + 1)

    fun contains(x: Int, z: Int) = x in left..right && z in top..bottom

    /** How far square ([x], [z])'s middle is from the map's, squared, in blocks. */
    fun distance(x: Int, z: Int): Double {
        val dx = x * span + span / 2.0 - centreX
        val dz = z * span + span / 2.0 - centreZ
        return dx * dx + dz * dz
    }

    /** Every square, nearest the middle first. */
    fun nearestFirst(): List<Pair<Int, Int>> =
        buildList { for (z in top..bottom) for (x in left..right) add(x to z) }.sortedBy { (x, z) -> distance(x, z) }

    companion object {
        /**
         * The blocks [ui]'s map shows now, as left, top, right, bottom: the full-screen map's live
         * view ([JourneyMapMenus.shownArea]), or the minimap's as it last drew ([JourneyMapMinimap.area]).
         * Null until it has been seen.
         */
        fun areaOf(ui: Context.UI, state: UIState): DoubleArray? =
            if (ui == Context.UI.Fullscreen) JourneyMapMenus.shownArea(state) else JourneyMapMinimap.area
    }
}
