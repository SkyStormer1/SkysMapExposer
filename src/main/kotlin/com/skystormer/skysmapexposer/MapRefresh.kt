package com.skystormer.skysmapexposer

/**
 * The map bar's Refresh: BlueMap's tiles in view are downloaded again whole and compared, as the
 * hard refresh does, tiles already in memory too ([TileStore.refetchBefore]). At most once every
 * [COOLDOWN_MS], so a held-down click cannot flood the server.
 */
object MapRefresh {

    const val COOLDOWN_MS = 30_000L

    /** Once every [ms] at most. */
    class Cooldown(private val ms: Long) {
        private var pressedAt = Long.MIN_VALUE / 2

        /** Whole seconds until it may be used again, rounded up; 0 when it may. */
        fun secondsLeft(now: Long): Int = ((pressedAt + ms - now + 999) / 1000).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

        /** Uses it if it may be used now; false while cooling down. */
        fun use(now: Long): Boolean {
            if (secondsLeft(now) > 0) return false
            pressedAt = now
            return true
        }
    }

    private val cooldown = Cooldown(COOLDOWN_MS)

    /** Seconds until it may be pressed again; 0 when it may. */
    fun secondsLeft(now: Long = System.currentTimeMillis()): Int = cooldown.secondsLeft(now)

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
        if (!cooldown.use(now)) return false
        session.tiles.refetchBefore = now
        return true
    }
}
