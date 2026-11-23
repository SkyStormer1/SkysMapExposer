package com.skystormer.skysmapexposer

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files

/**
 * The map's right-click menu as you lay it out (Edit menu…): which lines show at the top, the items
 * in your order, some in groups, and the ones you hid under More. One layout for each map mod, as
 * their own items differ; the arranging is the same for both ([arrange]), and each map only draws
 * the result its own way (Xaero's swaps the rows in place for a group, JourneyMap opens a sub-menu).
 *
 * Kept in `config/skysmapexposer-menu.json`. Items are known by an id that stays the same whatever
 * their label says just now; an item the layout does not know yet (a new one, another mod's) goes
 * at the end of the menu, and one that can't do anything just now is left out but keeps its place.
 */
object MenuLayout {

    const val XAERO = "xaero"
    const val JOURNEYMAP = "journeymap"

    sealed interface Entry {
        val id: String

        data class Item(override val id: String) : Entry

        data class Group(override val id: String, val name: String, val items: List<String>) : Entry
    }

    /** One map's layout: the top lines switched on or off by id, the menu, and More. */
    data class Layout(val header: Map<String, Boolean>, val menu: List<Entry>, val more: List<Entry>)

    /** A menu row, ready for a map to draw: one item, or a group of them. */
    sealed interface Node<T> {
        class Item<T>(val id: String, val value: T) : Node<T>

        class Group<T>(val name: String, val items: List<T>) : Node<T>
    }

    /** What a map's menu shows: the top lines, the menu's rows and More's (both empty of empty groups). */
    class Arranged<T>(val header: List<T>, val menu: List<Node<T>>, val more: List<Node<T>>)

    /**
     * [items] (id and whatever the map draws for it) in [map]'s layout. [header] are the lines at
     * the top, shown unless switched off. A group with nothing in it just now is left out.
     */
    fun <T> arrange(map: String, header: List<Pair<String, T>>, items: List<Pair<String, T>>): Arranged<T> {
        val layout = get(map)
        val byId = LinkedHashMap<String, T>()
        for ((id, value) in items) byId.putIfAbsent(id, value)
        val placed = HashSet<String>()
        fun nodes(list: List<Entry>): List<Node<T>> = list.mapNotNull { entry ->
            when (entry) {
                is Entry.Item -> byId[entry.id]?.let { placed += entry.id; Node.Item(entry.id, it) }
                is Entry.Group -> entry.items.mapNotNull { id -> byId[id]?.also { placed += id } }
                    .takeIf { it.isNotEmpty() }?.let { Node.Group(entry.name, it) }
            }
        }
        val menu = nodes(layout.menu)
        val more = nodes(layout.more)
        val leftover = byId.filterKeys { it !in placed }.map { (id, value) -> Node.Item(id, value) }
        for ((id, value) in items) names[map]?.put(id, labelOf(value))
        return Arranged(header.filter { (id, _) -> layout.header[id] != false }.map { it.second }, menu + leftover, more)
    }

    /** What the editor calls an item, for one that has been on the menu or that this mod knows. */
    fun name(map: String, id: String): String =
        // The maps' own by their plain names (Xaero's put a key hint in front of some); this mod's as last labelled.
        (if (id.startsWith("x_") || id.startsWith("j_")) KNOWN[id] else null) ?: names[map]?.get(id) ?: KNOWN[id] ?: id

    /** The lines at the top that [map] has, by id, with what the editor calls them. */
    fun headerLines(map: String): List<Pair<String, String>> = if (map == XAERO) {
        listOf("title" to "Title (Choose an Option)", "chunk" to "Chunk coordinates", "coords" to "Coordinates (click to copy)", "distance" to "Distance from you")
    } else {
        listOf("coords" to "Coordinates (click to copy)")
    }

    /** Every id [map]'s layout mentions or the menu has shown, for the editor to list the ones not placed. */
    fun knownIds(map: String): Set<String> = names[map]?.keys.orEmpty() + defaultIds(map)

    fun get(map: String): Layout {
        load()
        return layouts[map] ?: defaults(map)
    }

    fun set(map: String, layout: Layout) {
        load()
        layouts[map] = layout
        save()
    }

    fun reset(map: String) = set(map, defaults(map))

    fun defaults(map: String): Layout = if (map == XAERO) {
        Layout(
            mapOf("title" to true, "chunk" to true, "coords" to true, "distance" to true),
            listOf(
                Entry.Item("x_wp"), Entry.Item("ping"), Entry.Item("players"),
                Entry.Group("g1", "Waypoints", listOf("x_tmp", "counter", "sharewp", "x_wpmenu")),
                Entry.Group("g2", "BlueMap", listOf("download", "save")),
                Entry.Item("x_tp"),
            ),
            listOf(
                Entry.Item("copy"), Entry.Item("x_share"), Entry.Item("x_png"), Entry.Item("x_settings"),
                Entry.Group("g3", "Map clean-up", listOf("season", "border")),
            ),
        )
    } else {
        Layout(
            mapOf("coords" to true),
            listOf(
                Entry.Item("j_wp"), Entry.Item("ping"), Entry.Item("players"),
                Entry.Group("g1", "Waypoints", listOf("j_tmp", "j_global", "counter", "sharewp", "j_showall", "j_hideall")),
                Entry.Group("g2", "BlueMap", listOf("download", "save")),
                Entry.Group("g3", "Biomes", listOf("highlight", "clearhighlights")),
                Entry.Item("j_tp"),
            ),
            listOf(
                Entry.Item("copy"), Entry.Item("j_adv"),
                Entry.Group("g4", "Map clean-up", listOf("season", "border")),
            ),
        )
    }

    private fun defaultIds(map: String): Set<String> {
        val layout = defaults(map)
        return (layout.menu + layout.more).flatMap { if (it is Entry.Group) it.items else listOf(it.id) }.toSet()
    }

    /** Labels for the editor, from the last time each item was on the menu (they can change with what it does). */
    private val names = mapOf(XAERO to LinkedHashMap<String, String>(), JOURNEYMAP to LinkedHashMap())

    private fun labelOf(value: Any?): String = (value as? Labelled)?.menuLabel ?: value.toString()

    /** Anything a map draws for an item can say what the editor should call it. */
    interface Labelled {
        val menuLabel: String
    }

    private val KNOWN = mapOf(
        "x_wp" to "Create Waypoint", "x_tmp" to "Set Temporary Waypoint", "x_tp" to "Teleport Here",
        "x_share" to "Share Location In Chat", "x_wpmenu" to "Open Waypoint Menu", "x_png" to "Export Map as PNG",
        "x_settings" to "Open Settings",
        "j_wp" to "Create Waypoint", "j_tmp" to "Temp Waypoint", "j_global" to "Create Global Waypoint", "j_tp" to "Teleport",
        "j_showall" to "Show all waypoints", "j_hideall" to "Hide all waypoints", "j_adv" to "Advanced (delete region)",
        "copy" to "Copy coordinates", "ping" to "Ping here", "players" to "Players…", "counter" to "Other dimension temp waypoint",
        "sharewp" to "Share waypoints…", "download" to "Download BlueMap…", "save" to "Save terrain file",
        "highlight" to "Highlight biome", "clearhighlights" to "Clear biome highlights",
        "season" to "Clear old season…", "border" to "Clear past border…",
    )

    // Saving

    private val layouts = HashMap<String, Layout>()
    private var loaded = false
    private val file get() = FabricLoader.getInstance().configDir.resolve("skysmapexposer-menu.json")
    private val GSON = GsonBuilder().setPrettyPrinting().create()

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!Files.isRegularFile(file)) return
            val root = JsonParser.parseString(Files.readString(file)).asJsonObject
            for (map in listOf(XAERO, JOURNEYMAP)) {
                val o = root.getAsJsonObject(map) ?: continue
                val header = o.getAsJsonObject("header")?.entrySet()?.associate { it.key to it.value.asBoolean }.orEmpty()
                layouts[map] = Layout(header, entries(o.getAsJsonArray("menu")), entries(o.getAsJsonArray("more")))
                root.getAsJsonObject("names")?.getAsJsonObject(map)?.entrySet()?.forEach { names[map]?.put(it.key, it.value.asString) }
            }
        } catch (e: Exception) {
            Log.warn("Could not read your right-click menu layout ({}); using the default one", e.toString())
            layouts.clear()
        }
    }

    private fun entries(array: JsonArray?): List<Entry> = array?.mapNotNull { element ->
        val o = element.asJsonObject
        when {
            o.has("item") -> Entry.Item(o.get("item").asString)
            o.has("group") -> Entry.Group(o.get("id")?.asString ?: ("g" + o.hashCode()), o.get("group").asString, o.getAsJsonArray("items").map { it.asString })
            else -> null
        }
    }.orEmpty()

    private fun save() {
        val root = JsonObject()
        for ((map, layout) in layouts) {
            val o = JsonObject()
            o.add("header", JsonObject().apply { layout.header.forEach { (k, v) -> addProperty(k, v) } })
            o.add("menu", json(layout.menu))
            o.add("more", json(layout.more))
            root.add(map, o)
        }
        root.add("names", JsonObject().apply {
            names.forEach { (map, labels) -> add(map, JsonObject().apply { labels.forEach { (k, v) -> addProperty(k, v) } }) }
        })
        try {
            SafeFiles.write(file, GSON.toJson(root).toByteArray())
        } catch (e: Exception) {
            Log.warn("Could not save your right-click menu layout: {}", e.toString())
        }
    }

    private fun json(list: List<Entry>) = JsonArray().apply {
        for (entry in list) add(JsonObject().apply {
            when (entry) {
                is Entry.Item -> addProperty("item", entry.id)
                is Entry.Group -> {
                    addProperty("group", entry.name)
                    addProperty("id", entry.id)
                    add("items", JsonArray().apply { entry.items.forEach(::add) })
                }
            }
        })
    }
}
