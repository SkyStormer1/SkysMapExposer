package com.skystormer.skysmapexposer

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import net.fabricmc.loader.api.FabricLoader
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

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

    /** The settings declared with [bool], [int] and the rest, which [load] and [save] go through; first, so it is there when they are made. */
    private val settings = ArrayList<Setting<*>>()

    /**
     * One setting in the file, under its property's name: its default, how it is read (a value
     * outside its range brought in) and how it is written.
     */
    class Setting<T> internal constructor(
        val key: String, val default: T, private val read: (JsonElement) -> T, private val write: (T) -> JsonElement,
    ) : ReadWriteProperty<Any?, T> {
        var value: T = default
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) { this.value = value }
        internal fun load(element: JsonElement) { value = read(element) }
        internal fun json(): JsonElement = write(value)
        @Suppress("UNCHECKED_CAST")
        internal fun take(any: Any?) { value = any as T }
    }

    private fun <T> setting(default: T, read: (JsonElement) -> T, write: (T) -> JsonElement) =
        PropertyDelegateProvider<Any?, Setting<T>> { _, property -> Setting(property.name, default, read, write).also { settings += it } }
    private fun bool(default: Boolean) = setting(default, { it.asBoolean }, ::JsonPrimitive)
    private fun int(default: Int, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE) = setting(default, { it.asInt.coerceIn(min, max) }, ::JsonPrimitive)
    private fun float(default: Float, min: Float, max: Float) = setting(default, { it.asFloat.coerceIn(min, max) }, ::JsonPrimitive)
    private fun double(default: Double, max: Double = Double.MAX_VALUE) = setting(default, { it.asDouble.coerceAtMost(max) }, ::JsonPrimitive)
    private fun string(default: String) = setting(default, { it.asString }, ::JsonPrimitive)

    private fun settingOf(property: KProperty<*>): Setting<*> = settings.first { it.key == property.name }

    /**
     * A copy of the settings to change at leisure, as the settings screen does, by property
     * (`edits[Config::pings] = false`). [apply] puts into effect only what was changed here, so a
     * setting changed elsewhere meanwhile is not put back.
     */
    class Edits internal constructor() {
        private val before: Map<Setting<*>, Any?> = settings.associateWith { it.value }
        private val values = HashMap(before)

        @Suppress("UNCHECKED_CAST")
        operator fun <T> get(property: KProperty0<T>): T = values[settingOf(property)] as T
        operator fun <T> set(property: KProperty0<T>, value: T) { values[settingOf(property)] = value }
        @Suppress("UNCHECKED_CAST")
        fun <T> default(property: KProperty0<T>): T = settingOf(property).default as T
        fun reset(vararg properties: KProperty0<*>) = properties.forEach { values[settingOf(it)] = settingOf(it).default }
        fun apply() = values.forEach { (setting, value) -> if (value != before[setting]) setting.take(value) }
        /** Equal for two copies holding the same values. */
        override fun toString() = values.entries.associate { it.key.key to it.value }.toSortedMap().toString()
    }

    fun edits() = Edits()

    /** Whether BlueMap's terrain is drawn at all. Chunk visits are recorded either way. */
    var enabled by bool(true)

    /** Whether BlueMap's markers (shops, banners, spawn…) are shown on the world map and minimap. */
    var showMarkers by bool(true)

    /** Whether BlueMap's outlines (world border, zones) are drawn on the world map and minimap. */
    var showOutlines by bool(true)

    /**
     * Whether the players BlueMap reports are shown at all: on the world map, on the minimap, and
     * as a head over the ones you have locked on to.
     */
    var showPlayers by bool(true)

    /**
     * Whether to switch on Xaero's own "only display current map waypoints" the first time a world
     * is joined, so that changing the world map's dimension changes which waypoints it shows.
     *
     * Xaero's option defaults to off and is only reachable from a small toggle in the world map's
     * waypoint menu. Set this to false to leave it alone; it is never switched back off.
     */
    var matchWaypointsToDimension by bool(true)

    /**
     * Whether a download guesses the biome of blocks you have never mapped from BlueMap's colours
     * ([BiomeGuide]), rather than writing a placeholder. Overworld only for now.
     */
    var guessBiomes by bool(true)

    /**
     * Whether the world map shows, under Xaero's readout of the block under the mouse, that
     * block's coordinates in the other dimension (Overworld from the Nether, Nether from the Overworld).
     */
    var otherDimensionCoords by bool(true)

    /**
     * Whether your arrow shows on the full-screen map of the Nether while you are in the Overworld,
     * and the other way round, where you are in that dimension's terms (an eighth, or eight times, as
     * far out). Never on the End's map, or from it, where nothing lines up.
     */
    var otherDimensionArrow by bool(true)

    /** Which of those players the minimap shows. */
    var minimapPlayers by setting(MinimapPlayers.FAR_ONLY, { element -> MinimapPlayers.entries.first { it.name == element.asString } }, { JsonPrimitive(it.name) })

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
    var staleDays by double(7.0)

    /**
     * How far away (in chunks) BlueMap markers are shown on the minimap. Within it they shrink with
     * distance; beyond it they are hidden from the minimap (the world map still shows them).
     */
    var minimapShrinkChunks by int(32, MIN_SHRINK_CHUNKS, MAX_SHRINK_CHUNKS)

    /** Size of BlueMap's markers on the world map, as a multiple of the normal size. */
    var worldMapMarkerScale by float(1f, MIN_SCALE, MAX_SCALE)

    /** Size of other players' heads on the world map and over a locked player in the world. */
    var playerHeadScale by float(1f, MIN_SCALE, MAX_SCALE)

    /** Size of other players' heads on the minimap. */
    var minimapHeadScale by float(1f, MIN_SCALE, MAX_SCALE)

    /** Size of BlueMap's markers on the minimap, on top of their shrinking with distance. */
    var minimapMarkerScale by float(1f, MIN_SCALE, MAX_SCALE)

    /** Whether the bar on the world map is open, or folded down to its header. */
    var barOpen by bool(true)

    /**
     * Where the bar sits on the world map, from the screen's top-left corner, in GUI units. It
     * starts beside the column of buttons down the left edge (Xaero's settings, Sky's Map Shapes).
     */
    var barLeft by int(52)
    var barTop by int(2)

    /**
     * The same place as a share of the room the screen leaves for the bar (0 at the left or top
     * edge, 1 at the right edge or bottom), so it stays where it was whatever the GUI scale; below
     * 0 until the bar is first moved, when [barLeft] and [barTop] are used as they are.
     */
    var barLeftShare by double(-1.0, max = 1.0)
    var barTopShare by double(-1.0, max = 1.0)

    /** How many biomes the bar lists at once. */
    var barRows by int(6, 1, 40)

    /** The bar's size (1 = the game's own), the width added by dragging its right edge, and the panel it is docked under ("" for none). */
    var barScale by float(1f, 0.5f, 4f)
    var barExtra by int(0, 0, 1000)
    var barUnder by string("")

    /** The biggest the map panels may be made, before the letters look too blocky. */
    var panelMaxScale by float(2f, 1f, 4f)

    /** Experimental: the mods ("Bobby", "Voxy", "Distant Horizons") the terrain fill is switched off for. */
    val fillOff: MutableSet<String> = HashSet()

    /** Servers, by address as [normalise] writes it, whose "not set up" line ([SetupPrompt]) you turned off. */
    val setupDeclined: MutableSet<String> = HashSet()

    /**
     * Experimental: the world map's sea floor from BlueMap's 3D tiles close up ([SeaFloor]), rather
     * than from its flat pictures alone, which have none. Downloads far more.
     */
    var seaFloorHires by bool(false)

    /** Whether picked biomes are tinted on Xaero's minimap too, when it is installed. */
    var minimapBiomes by bool(true)

    /** Whether pings from trusted players are shown ([PlayerPings]). */
    var pings by bool(true)

    /** Whether a ping arriving plays a bell. */
    var pingSound by bool(true)

    /**
     * Whether the ping key needs pressing twice while something you could pick is within reach: a
     * block, a creature, or Litematica's schematic block. Bound to the middle mouse button, as pick
     * block often is, one press then only swaps blocks close up, a double press pings there too, and
     * anything out of reach is pinged at once.
     */
    var pingDoublePressNear by bool(true)

    /** The players whose pings you take and who get yours, by server address as [normalise] writes it. */
    val trusted: MutableMap<String, MutableSet<UUID>> = HashMap()

    /** Of those, the players who get your pings, by server address as [trusted]. */
    val pingTo: MutableMap<String, MutableSet<UUID>> = HashMap()

    /**
     * How often a tile on screen is asked about on the server ("has it changed?", which costs
     * nothing when the server can say "not modified"), and how often it is downloaded whole anyway
     * (in hours, 4 to a week, or 0 for never; each tile a little later than that, so they spread out).
     */
    var tileCheckMinutes by int(15, 1, 24 * 60)
    var fullDownloadHours by setting(72, { it.asInt.let { hours -> if (hours <= 0) 0 else hours.coerceIn(4, 24 * 7) } }, ::JsonPrimitive)

    /** What the settings offer for [fullDownloadHours]; 0 is off. */
    val FULL_DOWNLOAD_CHOICES = listOf(4, 8, 12, 24, 48, 72, 120, 168, 0)

    /** How often BlueMap's markers are read again. */
    var markerRefreshSeconds by int(30, min = 5)

    /** Video memory the BlueMap tiles may hold, in MB; tiles on screen are drawn coarser rather than dropped when over it. */
    var textureBudgetMb by int(256, 32, 8192)

    /** Whether every tile BlueMap has is downloaded and made ready in the background after joining ([MapPrep]), and how many a second at most. */
    var prepareMap by bool(true)
    var prepareTilesPerSecond by int(4, 1, 50)

    /** Whether chat says, once a visit, why BlueMap's terrain is missing while the full-screen map shows caves ([CaveNotice]). */
    var caveNotice by bool(true)

    /** None until you add one in the settings screen. */
    var servers: List<Server> = emptyList()

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
            for (setting in settings) read(setting.key, setting::load)
            // Before it had its own setting, the minimap used the world map's.
            if (!json.has("minimapHeadScale")) read("playerHeadScale", settingOf(::minimapHeadScale)::load)
            read("trusted") { readPlayers(it, trusted) }
            read("pingTo") { readPlayers(it, pingTo) }
            read("fillOff") { element ->
                val names = element.asJsonArray.map { it.asString }
                fillOff.clear()
                fillOff.addAll(names)
            }
            read("setupDeclined") { element ->
                val names = element.asJsonArray.map { it.asString }
                setupDeclined.clear()
                setupDeclined.addAll(names)
            }
            // The old "tileRefreshSeconds" and "tileHardRefreshHours" are ignored: they asked and
            // downloaded far too often ([tileCheckMinutes] and [fullDownloadHours] took over).
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

    /** Player lists by server, as [trusted] and [pingTo] are saved. */
    private fun readPlayers(element: JsonElement, into: MutableMap<String, MutableSet<UUID>>) {
        val read = element.asJsonObject.entrySet().associate { (server, players) ->
            server to players.asJsonArray.mapTo(LinkedHashSet()) { UUID.fromString(it.asString) }
        }
        into.clear()
        into.putAll(read)
    }

    private fun playersJson(lists: Map<String, Set<UUID>>) = JsonObject().also { servers ->
        lists.forEach { (server, players) ->
            servers.add(server, JsonArray().also { list -> players.forEach { list.add(it.toString()) } })
        }
    }

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
        try {
            SafeFiles.writeString(file, GSON.toJson(toJson()))
        } catch (e: Exception) {
            Log.error("Could not write $file", e)
        }
    }

    /** Everything [save] writes. */
    internal fun toJson(): JsonObject {
        val json = JsonObject()
        for (setting in settings) json.add(setting.key, setting.json())
        json.add("trusted", playersJson(trusted))
        json.add("pingTo", playersJson(pingTo))
        json.add("fillOff", JsonArray().also { list -> fillOff.sorted().forEach(list::add) })
        json.add("setupDeclined", JsonArray().also { list -> setupDeclined.sorted().forEach(list::add) })
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
        return json
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    const val MIN_SCALE = 0.25f
    const val MAX_SCALE = 3f

    const val MIN_SHRINK_CHUNKS = 4
    const val MAX_SHRINK_CHUNKS = 256

    const val OVERWORLD = "minecraft:overworld"
    const val NETHER = "minecraft:the_nether"
    const val END = "minecraft:the_end"

    /** Overworld blocks to one Nether block. */
    const val NETHER_SCALE = 8
}
