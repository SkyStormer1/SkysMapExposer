package com.skystormer.skysmapexposer

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    /**
     * Whether the players BlueMap reports are shown at all: on the world map, on the minimap, and
     * as a head over the ones you have locked on to.
     */
    var showPlayers: Boolean = true

    /**
     * Whether to switch on Xaero's own "only display current map waypoints" the first time a world
     * is joined, so that changing the world map's dimension changes which waypoints it shows.
     *
     * Xaero's option defaults to off and is only reachable from a small toggle in the world map's
     * waypoint menu. Set this to false to leave it alone; it is never switched back off.
     */
    var matchWaypointsToDimension: Boolean = true

    /**
     * Whether a download guesses the biome of blocks you have never mapped from BlueMap's colours
     * ([BiomeGuide]), rather than writing a placeholder. Overworld only for now.
     */
    var guessBiomes: Boolean = true

    /**
     * Whether the world map shows, under Xaero's readout of the block under the mouse, that
     * block's coordinates in the other dimension (Overworld from the Nether, Nether from the Overworld).
     */
    var otherDimensionCoords: Boolean = true

    /** Which of those players the minimap shows. */
    var minimapPlayers: MinimapPlayers = MinimapPlayers.FAR_ONLY

    /**
     * What the minimap does with BlueMap's players.
     *
     * The point of showing them there at all is that BlueMap knows where everyone is, so a zoomed
     * out minimap can keep showing someone after they leave your render distance — which is when
     * Xaero's own radar loses them. Near you the two would draw the same player twice, so
     * [FAR_ONLY] leaves the ones your game has loaded to the radar. Turn that off with [ALWAYS] if
     * you have Xaero's player radar switched off.
     */
    enum class MinimapPlayers(val label: String) {
        OFF("No players"),
        FAR_ONLY("Players out of range"),
        ALWAYS("All players"),
    }

    /**
     * How long after you last had a chunk loaded before the server's BlueMap may replace it on your
     * map. BlueMap is only used even then if it has changed since you were last there.
     */
    var staleDays: Double = 7.0

    /**
     * How far away (in chunks) BlueMap markers are shown on the minimap. Within it they shrink with
     * distance; beyond it they are hidden from the minimap (the world map still shows them).
     */
    var minimapShrinkChunks: Int = 32

    /** Size of BlueMap's markers on the world map, as a multiple of the normal size. */
    var worldMapMarkerScale: Float = 1f

    /** Size of other players' heads on the world map and over a locked player in the world. */
    var playerHeadScale: Float = 1f

    /** Size of other players' heads on the minimap. */
    var minimapHeadScale: Float = 1f

    /** Size of BlueMap's markers on the minimap, on top of their shrinking with distance. */
    var minimapMarkerScale: Float = 1f

    /** Whether the bar on the world map is open, or folded down to its header. */
    var barOpen: Boolean = true

    /**
     * Where the bar sits on the world map, from the screen's top-left corner, in GUI units. It
     * starts beside the column of buttons down the left edge (Xaero's settings, Sky's Map Shapes).
     */
    var barLeft: Int = 52
    var barTop: Int = 2

    /** How many biomes the bar lists at once. */
    var barRows: Int = 6

    /** The bar's size (1 = the game's own), the width added by dragging its right edge, and the panel it is docked under ("" for none). */
    var barScale: Float = 1f
    var barExtra: Int = 0
    var barUnder: String = ""

    /** The biggest the map panels may be made, before the letters look too blocky. */
    var panelMaxScale: Float = 2f

    /** Experimental: the mods ("Bobby", "Voxy", "Distant Horizons") the terrain fill is switched off for. */
    val fillOff: MutableSet<String> = HashSet()

    /**
     * Experimental: the world map's sea floor from BlueMap's 3D tiles close up ([SeaFloor]), rather
     * than from its flat pictures alone, which have none. Downloads far more.
     */
    var seaFloorHires: Boolean = false

    /** Whether picked biomes are tinted on Xaero's minimap too, when it is installed. */
    var minimapBiomes: Boolean = true

    /**
     * How often a tile on screen is asked about on the server ("has it changed?", which costs
     * nothing when the server can say "not modified"), and how often it is downloaded whole anyway.
     */
    var tileCheckMinutes: Int = 15
    var tileHardRefreshHours: Int = 4

    /** How often BlueMap's markers are read again. */
    var markerRefreshSeconds: Int = 30

    /** Video memory the BlueMap tiles may hold, in MB; tiles on screen are drawn coarser rather than dropped when over it. */
    var textureBudgetMb: Int = 256

    /** Whether every tile BlueMap has is downloaded and made ready in the background after joining ([MapPrep]), and how many a second at most. */
    var prepareMap: Boolean = true
    var prepareTilesPerSecond: Int = 4

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

    /**
     * What [load] could not read, said once in chat on the next join ([takeLoadProblem]); null when
     * everything was read.
     */
    private var loadProblem: String? = null

    /**
     * Set when part of the file could not be read and it could not be copied aside either: saving
     * would then lose what was not read, so nothing is saved this game.
     */
    private var saveBlocked = false

    /**
     * Reads the file one setting at a time, and each server on its own, so that a typo costs only
     * the setting or server it is in (logged) and never the rest. If anything could not be read,
     * the file is copied aside as `skysmapexposer.json.broken-<time>` before anything can save over
     * it, and the player is told on joining a world.
     */
    fun load() = load(file)

    internal fun load(path: Path) {
        if (Files.notExists(path)) {
            save()
            return
        }
        val json = try {
            JsonParser.parseString(Files.readString(path)).asJsonObject
        } catch (e: Exception) {
            Log.error("Could not read $path", e)
            null
        }
        val skipped = ArrayList<String>()
        if (json != null) {
            fun read(key: String, apply: (JsonElement) -> Unit) {
                val value = json.get(key) ?: return
                try {
                    apply(value)
                } catch (e: Exception) {
                    Log.warn("Could not read \"{}\" in {} ({}); it is left as it was", key, path, e.toString())
                    skipped.add(key)
                }
            }
            read("enabled") { enabled = it.asBoolean }
            read("showMarkers") { showMarkers = it.asBoolean }
            read("showOutlines") { showOutlines = it.asBoolean }
            read("showPlayers") { showPlayers = it.asBoolean }
            read("matchWaypointsToDimension") { matchWaypointsToDimension = it.asBoolean }
            read("guessBiomes") { guessBiomes = it.asBoolean }
            read("otherDimensionCoords") { otherDimensionCoords = it.asBoolean }
            read("barOpen") { barOpen = it.asBoolean }
            read("barLeft") { barLeft = it.asInt }
            read("barTop") { barTop = it.asInt }
            read("barRows") { barRows = it.asInt.coerceIn(1, 40) }
            read("minimapBiomes") { minimapBiomes = it.asBoolean }
            read("fillOff") { element ->
                val names = element.asJsonArray.map { it.asString }
                fillOff.clear()
                fillOff.addAll(names)
            }
            read("barScale") { barScale = it.asFloat.coerceIn(0.5f, 4f) }
            read("barExtra") { barExtra = it.asInt.coerceIn(0, 1000) }
            read("barUnder") { barUnder = it.asString }
            read("panelMaxScale") { panelMaxScale = it.asFloat.coerceIn(1f, 4f) }
            read("minimapPlayers") { element ->
                val name = element.asString
                minimapPlayers = MinimapPlayers.entries.first { it.name == name }
            }
            read("staleDays") { staleDays = it.asDouble }
            read("worldMapMarkerScale") { worldMapMarkerScale = it.asFloat.coerceIn(MIN_SCALE, MAX_SCALE) }
            read("playerHeadScale") { playerHeadScale = it.asFloat.coerceIn(MIN_SCALE, MAX_SCALE) }
            // Before it had its own setting, the minimap used the world map's.
            read(if (json.has("minimapHeadScale")) "minimapHeadScale" else "playerHeadScale") {
                minimapHeadScale = it.asFloat.coerceIn(MIN_SCALE, MAX_SCALE)
            }
            read("minimapMarkerScale") { minimapMarkerScale = it.asFloat.coerceIn(MIN_SCALE, MAX_SCALE) }
            read("minimapShrinkChunks") { minimapShrinkChunks = it.asInt.coerceIn(MIN_SHRINK_CHUNKS, MAX_SHRINK_CHUNKS) }
            // The old "tileRefreshSeconds" is ignored: its 60 seconds asked far too often.
            read("tileCheckMinutes") { tileCheckMinutes = it.asInt.coerceIn(1, 24 * 60) }
            read("tileHardRefreshHours") { tileHardRefreshHours = it.asInt.coerceIn(1, 24 * 7) }
            read("markerRefreshSeconds") { markerRefreshSeconds = it.asInt.coerceAtLeast(5) }
            read("textureBudgetMb") { textureBudgetMb = it.asInt.coerceIn(32, 8192) }
            read("prepareMap") { prepareMap = it.asBoolean }
            read("seaFloorHires") { seaFloorHires = it.asBoolean }
            read("prepareTilesPerSecond") { prepareTilesPerSecond = it.asInt.coerceIn(1, 50) }
            read("servers") { element ->
                val list = ArrayList<Server>()
                element.asJsonArray.forEachIndexed { index, entry ->
                    try {
                        list.add(server(entry.asJsonObject))
                    } catch (e: Exception) {
                        Log.warn("Could not read server {} in {} ({}); it is left out: {}", index + 1, path, e.toString(), entry)
                        skipped.add("server ${index + 1}")
                    }
                }
                servers = list
            }
        }
        if (json == null) keepAside(path, "its settings")
        else if (skipped.isNotEmpty()) keepAside(path, "part of its settings (${skipped.joinToString()})")
    }

    private fun server(entry: JsonObject) = Server(
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

    /** Copies a file that could not be fully read beside itself, before anything saves over it. */
    private fun keepAside(path: Path, what: String) {
        val copy = path.resolveSibling("${path.fileName}.broken-${LocalDateTime.now().format(STAMP)}")
        loadProblem = try {
            Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            Log.warn("Kept the settings file as it was in {}", copy)
            "Sky's Map Exposer couldn't read $what; the original was kept as ${copy.fileName}"
        } catch (e: Exception) {
            Log.error("Could not copy $path aside; settings will not be saved this game", e)
            saveBlocked = true
            "Sky's Map Exposer couldn't read $what, nor copy the file aside, so settings won't be saved this game. The log says why."
        }
    }

    /** The message about a settings file that could not be fully read, once; null after that or if there was none. */
    fun takeLoadProblem(): String? = loadProblem.also { loadProblem = null }

    fun save() {
        if (saveBlocked) return
        val json = JsonObject()
        json.addProperty("enabled", enabled)
        json.addProperty("showMarkers", showMarkers)
        json.addProperty("showOutlines", showOutlines)
        json.addProperty("showPlayers", showPlayers)
        json.addProperty("minimapPlayers", minimapPlayers.name)
        json.addProperty("matchWaypointsToDimension", matchWaypointsToDimension)
        json.addProperty("guessBiomes", guessBiomes)
        json.addProperty("otherDimensionCoords", otherDimensionCoords)
        json.addProperty("barOpen", barOpen)
        json.addProperty("barLeft", barLeft)
        json.addProperty("barTop", barTop)
        json.addProperty("barRows", barRows)
        json.addProperty("minimapBiomes", minimapBiomes)
        json.add("fillOff", JsonArray().also { list -> fillOff.sorted().forEach(list::add) })
        json.addProperty("barScale", barScale)
        json.addProperty("barExtra", barExtra)
        json.addProperty("barUnder", barUnder)
        json.addProperty("panelMaxScale", panelMaxScale)
        json.addProperty("staleDays", staleDays)
        json.addProperty("worldMapMarkerScale", worldMapMarkerScale)
        json.addProperty("playerHeadScale", playerHeadScale)
        json.addProperty("minimapHeadScale", minimapHeadScale)
        json.addProperty("minimapMarkerScale", minimapMarkerScale)
        json.addProperty("minimapShrinkChunks", minimapShrinkChunks)
        json.addProperty("tileCheckMinutes", tileCheckMinutes)
        json.addProperty("tileHardRefreshHours", tileHardRefreshHours)
        json.addProperty("markerRefreshSeconds", markerRefreshSeconds)
        json.addProperty("textureBudgetMb", textureBudgetMb)
        json.addProperty("prepareMap", prepareMap)
        json.addProperty("seaFloorHires", seaFloorHires)
        json.addProperty("prepareTilesPerSecond", prepareTilesPerSecond)
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
            SafeFiles.writeString(file, GSON.toJson(json))
        } catch (e: Exception) {
            Log.error("Could not write $file", e)
        }
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    const val MIN_SCALE = 0.25f
    const val MAX_SCALE = 3f

    const val MIN_SHRINK_CHUNKS = 4
    const val MAX_SHRINK_CHUNKS = 256

    const val OVERWORLD = "minecraft:overworld"
    const val NETHER = "minecraft:the_nether"
    const val END = "minecraft:the_end"
}
