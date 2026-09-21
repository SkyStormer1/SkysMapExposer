package com.skystormer.skysmapexposer

/**
 * Added to Xaero's world map screen by `GuiMapMixin`, so the player list can move the map's camera
 * to someone, the way Xaero's own waypoint list does.
 */
interface MapCamera {
    /** Glides the map's camera to block ([x], [z]). */
    fun skysmapexposerCentreOn(x: Int, z: Int)

    /**
     * Puts the camera on block ([x], [z]) at once, with no glide. For jumps of tens of thousands of
     * blocks, which is what changing dimension is, gliding would be a long slow crawl over nothing.
     */
    fun skysmapexposerJumpTo(x: Int, z: Int)

    /**
     * Puts the camera back on you, the way Xaero does when it first opens the map, keeping whether
     * the camera was following you rather than detaching it.
     */
    fun skysmapexposerFollowPlayer()
}
