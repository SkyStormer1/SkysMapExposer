package com.skystormer.skysmapexposer

/**
 * What the settings screen checks before it saves, and how it tells whether the server you are on
 * changed. No Minecraft classes, so it can be tested.
 */
object SettingsCheck {

    /** One server entry as typed: its addresses (already split), BlueMap address and season date. */
    class Entry(val addresses: List<String>, val url: String, val coverOverworld: String)

    enum class Field { STALE, ADDRESS, URL, COVER }

    /** What is wrong, which server entry it is in (for [Field.STALE], none: -1) and which box. */
    class Problem(val message: String, val server: Int, val field: Field)

    /**
     * The first thing that stops saving, or null. An entry with neither an address nor a BlueMap
     * address is a new one left empty: it is not saved, and is no problem.
     */
    fun check(staleDays: String, entries: List<Entry>, parseTime: (String) -> Long?): Problem? {
        if (staleDays.trim().toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() } == null) {
            return Problem("\"Replace after\" must be a number of days, 0 or more", -1, Field.STALE)
        }
        entries.forEachIndexed { i, entry ->
            val url = entry.url.trim()
            if (entry.addresses.isEmpty() && url.isEmpty()) return@forEachIndexed
            if (entry.addresses.isEmpty()) return Problem("The server with BlueMap $url has no server address", i, Field.ADDRESS)
            if (url.isEmpty()) return Problem("${entry.addresses.first()} has no BlueMap address", i, Field.URL)
            if (entry.coverOverworld.isNotBlank() && parseTime(entry.coverOverworld) == null) {
                return Problem("${entry.addresses.first()}: the season date must look like 2026-10-20 14:00", i, Field.COVER)
            }
        }
        return null
    }

    /** The settings of one server that a session is built from, to compare before and after. */
    fun serverKey(server: Config.Server): String =
        listOf(server.addresses.map(Config::normalise).sorted(), server.url.trim(), server.maps.toSortedMap(), server.off.sorted(), server.coverBefore.toSortedMap()).toString()
}
