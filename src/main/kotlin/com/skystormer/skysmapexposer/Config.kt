package com.skystormer.skysmapexposer

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * `config/skysmapexposer.json`: which servers have a BlueMap, where, and when your own map counts as
 * out of date. Edited in game through the settings screen.
 *
 * Read and written by hand with Gson, which Minecraft already ships, rather than through a config
 * library the mod would then depend on.
 */
object Config {

    private val GSON = GsonBuilder().setPrettyPrinting().create()

    private val file: Path
        get() = FabricLoader.getInstance().configDir.resolve("skysmapexposer.json")

    /** Whether BlueMap's terrain is drawn at all. Chunk visits are recorded either way. */
    var enabled: Boolean = true

    /** Whether BlueMap's markers (shops, banners, spawn…) are shown on the world map and minimap. */
    var showMarkers: Boolean = true

    /** Whether BlueMap's outlines (world border, zones) are drawn on the world map and minimap. */
    var showOutlines: Boolean = true

    /** Whether the players BlueMap reports are shown on the world map, in their own dimension. */
    var showPlayers: Boolean = true

    /**
     * How long after you last had a chunk loaded before the server's BlueMap may replace it on your
     * map. BlueMap is only used even then if it has changed since you were last there.
     */
    var staleDays: Double = 7.0

    /** How often a tile on screen is checked for changes on the server. */
    var tileRefreshSeconds: Int = 60

    /** How often BlueMap's markers are read again. */
    var markerRefreshSeconds: Int = 30

    /** How many BlueMap tiles to keep in memory. */
    var maxLoadedTiles: Int = 64

    var servers: List<Server> = defaultServers()

    /**
     * One server with a BlueMap.
     *
     * @property addresses every name the server is reached by, as typed into the server list; the
     *   default port may be left off.
     * @property url the BlueMap web address, the page you would open in a browser.
     * @property maps Minecraft dimension id to BlueMap map id.
     * @property off dimensions whose BlueMap map is known but not used.
     * @property coverBefore per dimension, a time (epoch milliseconds): anything you mapped before it
     *   is covered by BlueMap regardless of age — for a map left over from a previous season.
     */
    class Server(
        val addresses: List<String>,
        val url: String,
        val maps: Map<String, String>,
        val off: Set<String> = emptySet(),
        val coverBefore: Map<String, Long> = emptyMap(),
    ) {
        /** The BlueMap map whose terrain fills in [dimension], or null if none or switched off. */
        fun mapFor(dimension: String): String? =
            maps[dimension]?.takeIf { it.isNotBlank() && dimension !in off }

        /**
         * The BlueMap map for [dimension]'s markers, outlines and players, which are shown even
         * where its terrain is switched off: they sit on top of your own map and replace nothing.
         */
        fun markersFor(dimension: String): String? = maps[dimension]?.takeIf { it.isNotBlank() }
    }

    val staleMinutes: Int
        get() = (staleDays * 24 * 60).toInt().coerceAtLeast(0)

    fun serverFor(address: String?): Server? {
        if (address == null) return null
        val wanted = normalise(address)
        return servers.firstOrNull { server -> server.addresses.any { normalise(it) == wanted } }
    }

    /** `Play.Example.com:25565` and `play.example.com` are the same server. */
    fun normalise(address: String): String =
        address.trim().lowercase().removeSuffix(":25565").removeSuffix(".")

    // No server is set up until the player adds one in the settings screen.
    private fun defaultServers() = emptyList<Server>()

    fun load() {
        val path = file
        try {
            if (Files.notExists(path)) {
                save()
                return
            }
            val json = JsonParser.parseString(Files.readString(path)).asJsonObject
            json.get("enabled")?.let { enabled = it.asBoolean }
            json.get("showMarkers")?.let { showMarkers = it.asBoolean }
            json.get("showOutlines")?.let { showOutlines = it.asBoolean }
            json.get("showPlayers")?.let { showPlayers = it.asBoolean }
            json.get("staleDays")?.let { staleDays = it.asDouble }
            json.get("tileRefreshSeconds")?.let { tileRefreshSeconds = it.asInt.coerceAtLeast(10) }
            json.get("markerRefreshSeconds")?.let { markerRefreshSeconds = it.asInt.coerceAtLeast(5) }
            json.get("maxLoadedTiles")?.let { maxLoadedTiles = it.asInt.coerceIn(8, 1024) }
            json.getAsJsonArray("servers")?.let { array ->
                servers = array.map { element ->
                    val entry = element.asJsonObject
                    Server(
                        addresses = entry.getAsJsonArray("addresses")?.map { it.asString }
                            ?: listOfNotNull(entry.get("address")?.asString),
                        url = entry.get("url").asString,
                        maps = entry.getAsJsonObject("maps")?.entrySet()
                            ?.associate { (dimension, map) -> dimension to map.asString }
                            ?: emptyMap(),
                        off = entry.getAsJsonArray("off")?.map { it.asString }?.toSet() ?: emptySet(),
                        coverBefore = entry.getAsJsonObject("coverBefore")?.entrySet()
                            ?.associate { (dimension, time) -> dimension to time.asLong }
                            ?: emptyMap(),
                    )
                }
            }
        } catch (e: Exception) {
            Log.error("Could not read $path; keeping the settings already in memory", e)
        }
    }

    fun save() {
        val json = JsonObject()
        json.addProperty("enabled", enabled)
        json.addProperty("showMarkers", showMarkers)
        json.addProperty("showOutlines", showOutlines)
        json.addProperty("showPlayers", showPlayers)
        json.addProperty("staleDays", staleDays)
        json.addProperty("tileRefreshSeconds", tileRefreshSeconds)
        json.addProperty("markerRefreshSeconds", markerRefreshSeconds)
        json.addProperty("maxLoadedTiles", maxLoadedTiles)
        val array = JsonArray()
        for (server in servers) {
            val entry = JsonObject()
            val addresses = JsonArray()
            server.addresses.forEach(addresses::add)
            entry.add("addresses", addresses)
            entry.addProperty("url", server.url)
            val maps = JsonObject()
            server.maps.forEach { (dimension, map) -> maps.addProperty(dimension, map) }
            entry.add("maps", maps)
            val off = JsonArray()
            server.off.forEach(off::add)
            entry.add("off", off)
            val cover = JsonObject()
            server.coverBefore.forEach { (dimension, time) -> cover.addProperty(dimension, time) }
            entry.add("coverBefore", cover)
            array.add(entry)
        }
        json.add("servers", array)
        try {
            Files.createDirectories(file.parent)
            Files.writeString(file, GSON.toJson(json))
        } catch (e: Exception) {
            Log.error("Could not write $file", e)
        }
    }

    const val OVERWORLD = "minecraft:overworld"
    const val NETHER = "minecraft:the_nether"
    const val END = "minecraft:the_end"
}
