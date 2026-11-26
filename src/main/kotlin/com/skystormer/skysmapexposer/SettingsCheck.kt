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
            blueMapAddress(url).problem?.let { return Problem("${entry.addresses.first()}: $it", i, Field.URL) }
            if (entry.coverOverworld.isNotBlank() && parseTime(entry.coverOverworld) == null) {
                return Problem("${entry.addresses.first()}: the season date must look like 2026-10-20 14:00", i, Field.COVER)
            }
        }
        return null
    }

    /**
     * A BlueMap address as typed, cleaned into the address of the map's own page: [url], with what
     * was changed ([fixed], or null), or why it can't be used ([problem]). An address copied from a
     * browser often carries the view after a `#` (`#world:0:64:0:…`) or a page name (`index.html`),
     * and one typed by hand often has no `http://`; those are fixed rather than refused.
     */
    class Address(val url: String, val fixed: String?, val problem: String?)

    fun blueMapAddress(typed: String): Address {
        var text = typed.trim()
        if (text.isEmpty()) return Address("", null, null)
        val changes = ArrayList<String>()
        if (!text.contains("://")) {
            text = "http://$text"
            changes += "added http://"
        }
        val uri = try {
            java.net.URI(text)
        } catch (e: java.net.URISyntaxException) {
            return Address(typed.trim(), null, "\"${typed.trim()}\" is not a web address")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Address(typed.trim(), null, "a BlueMap address starts with http:// or https://")
        val host = uri.host
        if (host.isNullOrBlank()) return Address(typed.trim(), null, "\"${typed.trim()}\" has no server name in it")
        if (uri.rawFragment != null) changes += "took off #${uri.rawFragment.take(16)}${if (uri.rawFragment.length > 16) "…" else ""}"
        if (uri.rawQuery != null) changes += "took off ?${uri.rawQuery.take(16)}"
        var path = uri.rawPath.orEmpty()
        val last = path.substringAfterLast('/')
        if ('.' in last) {
            path = path.dropLast(last.length)
            changes += "took off $last"
        }
        if (!path.endsWith('/')) path += "/"
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        val url = "$scheme://$host$port$path"
        return Address(url, changes.takeIf { it.isNotEmpty() }?.joinToString(", "), null)
    }

    /** The root of cleaned BlueMap address [url] (`http://host:port/`), or null when it already is one. */
    fun rootOf(url: String): String? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        if (uri.host == null || uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") return null
        return "${uri.scheme}://${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}/"
    }

    /** The settings of one server that a session is built from, to compare before and after. */
    fun serverKey(server: Config.Server): String =
        listOf(server.addresses.map(Config::normalise).sorted(), server.url.trim(), server.maps.toSortedMap(), server.off.sorted(), server.coverBefore.toSortedMap()).toString()
}
