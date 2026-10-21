package com.skystormer.skysmapexposer

/**
 * The map bar's Refresh: BlueMap's tiles in view are downloaded again whole and compared, as the
 * hard refresh does, tiles already in memory too ([TileStore.refetchBefore]). At most once every
 * [COOLDOWN_MS], so a held-down click cannot flood the server.
 */
object MapRefresh {

    const val COOLDOWN_MS = 30_000L

    private var pressedAt = Long.MIN_VALUE / 2

    /** Seconds until it may be pressed again; 0 when it may. */
    fun secondsLeft(now: Long = System.currentTimeMillis()): Int =
        ((pressedAt + COOLDOWN_MS - now + 999) / 1000).toInt().coerceAtLeast(0)

    /** What its tooltip says now. */
    fun tip(now: Long = System.currentTimeMillis()): String = when {
        Session.current == null -> "This server has no BlueMap set up"
        secondsLeft(now) > 0 -> "Refresh available in ${secondsLeft(now)}s"
        else -> "Download BlueMap's tiles in view again, for changes made since they were last checked. " +
            "Zoomed far out, they are downloaded once you zoom in."
    }

    /** Presses it; false if it is cooling down or there is no BlueMap. */
    fun press(now: Long = System.currentTimeMillis()): Boolean {
        val session = Session.current ?: return false
        if (secondsLeft(now) > 0) return false
        pressedAt = now
        session.tiles.refetchBefore = now
        return true
    }
}
