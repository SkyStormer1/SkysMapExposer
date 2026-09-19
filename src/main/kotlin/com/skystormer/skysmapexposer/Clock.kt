package com.skystormer.skysmapexposer

/**
 * Times are whole minutes since 1970, in an `Int`.
 *
 * A minute is finer than anything here needs, and an `Int` halves the size of the visit record,
 * which holds one entry per chunk you have ever loaded. It lasts until the year 6053.
 */
object Clock {
    fun nowMinutes(): Int = (System.currentTimeMillis() / 60_000L).toInt()

    fun minutesOf(millis: Long): Int = (millis / 60_000L).toInt()
}
