package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Each dimension's season start ("cover map before"), for either map: your map from before it counts as old. */
object Seasons {

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun format(minute: Int): String =
        TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(minute * 60_000L), ZoneId.systemDefault()))

    /** Each dimension's season start on this server, as words. */
    fun list(): String {
        val server = Config.serverFor(MapExposerClient.addressOf(Minecraft.getInstance()))
            ?: return "This server is not set up in Sky's Map Exposer's settings, so it has no season start"
        if (server.coverBefore.isEmpty()) return "No season start is set; /mapexposer season set <yyyy-MM-dd HH:mm> sets one for the dimension the map shows"
        return "Season start: " + server.coverBefore.entries.joinToString(", ") { (dimension, millis) ->
            "${Ui.dimensionName(dimension) ?: dimension} ${format(Clock.minutesOf(millis))}"
        }
    }

    /**
     * Sets the season start of [dimension] (the one the map shows) to [text] — `yyyy-MM-dd HH:mm`, a
     * date alone, or `now` — or takes it away when [text] is null; refused while the map is [busy]
     * being written. Returns what to tell the player.
     */
    fun set(text: String?, dimension: String?, busy: Boolean): String {
        if (busy) return "Wait for what is being written into your map to finish first"
        val address = MapExposerClient.addressOf(Minecraft.getInstance())
        val server = Config.serverFor(address) ?: return "This server is not set up in Sky's Map Exposer's settings; add it there first"
        dimension ?: return "Not in a world"
        val name = Ui.dimensionName(dimension) ?: dimension
        val millis = text?.let { parse(it) ?: return "Write the time as yyyy-MM-dd HH:mm (or just the date, or now)" }
        val cover = LinkedHashMap(server.coverBefore)
        if (millis == null) cover.remove(dimension) else cover[dimension] = millis
        Config.servers = Config.servers.map { if (it === server) Config.Server(it.addresses, it.url, it.maps, it.off, cover) else it }
        Config.save()
        Session.start(address)
        return if (millis == null) "The $name has no season start now"
        else "The $name's season started ${format(Clock.minutesOf(millis))}: your map from before then is replaced by downloads and shared maps, and left out when you share"
    }

    /** [text] as a time, `yyyy-MM-dd HH:mm`, a date alone, or `now`; null if it is none of those (or blank). */
    fun parse(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.equals("now", ignoreCase = true)) return System.currentTimeMillis()
        val time = runCatching { LocalDateTime.parse(trimmed, TIME) }.getOrNull()
            ?: runCatching { LocalDate.parse(trimmed).atStartOfDay() }.getOrNull()
            ?: return null
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
