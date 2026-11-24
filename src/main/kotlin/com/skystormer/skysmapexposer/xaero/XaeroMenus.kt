package com.skystormer.skysmapexposer.xaero

import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MenuLayout
import com.skystormer.skysmapexposer.Ui
import com.skystormer.skysmapexposer.gui.MenuEditScreen
import com.skystormer.skysmapexposer.mixin.menu.GuiMapMenuAccess
import com.skystormer.skysmapexposer.mixin.menu.RightClickOptionAccess
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import xaero.map.gui.GuiMap
import xaero.map.gui.IRightClickableElement
import xaero.map.gui.MapTileSelection
import xaero.map.gui.dropdown.rightclick.RightClickOption

/**
 * Xaero's world map right-click menu in your layout ([MenuLayout]): the lines at the top you keep,
 * the items in your order, groups, More and Edit menu…. Xaero's menu can't open a second menu
 * beside it, so a group (and More) swaps the menu for one of its own at the same spot, starting
 * with the group's name and a Back row.
 *
 * Xaero always closes its menu once a row is picked, so the new one opens on the next tick ([tick]).
 */
object XaeroMenus {

    private class Row(val option: RightClickOption) : MenuLayout.Labelled {
        override val menuLabel: String get() = option.displayName.string.trim()
    }

    /** Where the map's menu last opened, in screen units, so a group's page opens in its place. */
    private var openedX = 0
    private var openedY = 0

    /** For `GuiMapMixin`: Xaero opening a right-click menu at ([x], [y]) for [element]. */
    @JvmStatic
    fun opened(element: IRightClickableElement, x: Int, y: Int) {
        if (element is GuiMap) {
            openedX = x
            openedY = y
        }
    }

    /** The map's menu as last laid out, for its pages. */
    private var last: MenuLayout.Arranged<Row>? = null

    private sealed interface Page {
        data object Main : Page
        data object More : Page
        class Group(val group: MenuLayout.Node.Group<Row>, val inMore: Boolean) : Page
    }

    /**
     * Xaero's chunk selection when the menu was made. Xaero clears it whenever a menu closes, picking
     * a group included; its chunk line, Download and an area's Save terrain file need it back.
     */
    private var selection: MapTileSelection? = null

    /** The page to open on the next tick. */
    private var pending: Page? = null

    /** This mod's rows on the menu being made, each to the id the layout knows it by. */
    private val ours = java.util.IdentityHashMap<RightClickOption, String>()
    private var copy: () -> Unit = {}

    /**
     * Called as this mod adds [rows] to the map's menu (they go on the end as they are), with
     * Xaero's chunk [selection] and how to [copy] the clicked block's coordinates. The menu is laid
     * out once every mod has added its rows, as Xaero opens it ([arrange]).
     */
    fun prepare(rows: List<Pair<String, RightClickOption>>, selection: MapTileSelection?, copy: () -> Unit) {
        ours.clear()
        for ((id, row) in rows) ours[row] = id
        this.selection = selection
        this.copy = copy
    }

    /**
     * For `GuiRightClickMenuMixin`: the map's menu ([options], every mod's rows in it, Xaero's own
     * first) in your layout, in place, as Xaero opens it. Left as it is if that fails.
     */
    @JvmStatic
    fun arrange(options: ArrayList<RightClickOption>, target: IRightClickableElement) {
        if (target !is GuiMap) return
        try {
            val header = ArrayList<Pair<String, Row>>()
            val items = ArrayList<Pair<String, Row>>()
            for (option in options) {
                val name = (option as RightClickOptionAccess).`skysmapexposer$name`() ?: ""
                val label = option.displayName.string.trim()
                val mine = ours[option]
                when {
                    mine != null -> items += mine to Row(option)
                    name == TITLE -> header += "title" to Row(option)
                    name.startsWith("C: (") -> header += "chunk" to Row(option)
                    // Xaero's line does nothing when clicked: here it copies them.
                    name.startsWith("X: ") -> header += "coords" to Row(MapMenus.option(option.displayName.string, 0, target) { copy() })
                    // How far the clicked block is from you.
                    DISTANCE.matches(label) -> header += "distance" to Row(option)
                    else -> items += (KEYS[name] ?: MenuLayout.otherId("xaero", name.ifBlank { label })) to Row(option)
                }
            }
            val arranged = MenuLayout.arrange(MenuLayout.XAERO, header, items)
            last = arranged
            val page = ArrayList<RightClickOption>()
            arranged.header.forEach { page += it.option }
            arranged.menu.forEach { page += row(it, target, inMore = false) }
            if (arranged.more.isNotEmpty()) page += MapMenus.option("More  ›", 0, target) { pending = Page.More }
            page += MapMenus.option("§7Edit menu…", 0, target) { parent -> Ui.open(MenuEditScreen(parent, MenuLayout.XAERO)) }
            replace(options, page)
        } catch (e: Throwable) {
            Log.warn("Could not lay out Xaero's right-click menu: {}", e.toString())
        }
    }

    private fun row(node: MenuLayout.Node<Row>, target: IRightClickableElement, inMore: Boolean): RightClickOption = when (node) {
        is MenuLayout.Node.Item -> node.value.option
        is MenuLayout.Node.Group -> MapMenus.option("${node.name}  ›", 0, target) { pending = Page.Group(node, inMore) }
    }

    private fun replace(options: ArrayList<RightClickOption>, rows: List<RightClickOption>) {
        options.clear()
        options.addAll(rows)
        renumber(options)
    }

    /** Gives each row its place in [options] as its index, which Xaero hovers and clicks by. */
    private fun renumber(options: List<RightClickOption>) =
        options.forEachIndexed { i, option -> (option as RightClickOptionAccess).`skysmapexposer$setIndex`(i) }

    /**
     * True while this mod opens a page of the menu itself. A page is a new Xaero menu, which clicks as
     * it opens; the click on More or the group that asked for it has just clicked, so this one is
     * kept quiet (`DropDownWidgetMixin`), or every page would sound like a double click.
     */
    @JvmStatic
    var openingPage = false
        private set

    /** Client thread, each tick: a group's page (or More, or back to the menu) opened where the menu was. */
    fun tick(minecraft: Minecraft) {
        val page = pending ?: return
        pending = null
        val map = minecraft.gui.screen() as? GuiMap ?: return
        val arranged = last ?: return
        try {
            val element: IRightClickableElement = when (page) {
                Page.Main -> map
                Page.More -> PageElement(map, "More", Page.Main) { target -> arranged.more.map { row(it, target, inMore = true) } }
                is Page.Group -> PageElement(map, page.group.name, if (page.inMore) Page.More else Page.Main) { page.group.items.map { it.option } }
            }
            val access = map as GuiMapMenuAccess
            access.`skysmapexposer$setSelection`(selection)
            openingPage = true
            try {
                access.`skysmapexposer$handleRightClick`(element, openedX, openedY)
            } finally {
                openingPage = false
            }
        } catch (e: Throwable) {
            Log.warn("Could not open a group of Xaero's right-click menu: {}", e.toString())
        }
    }

    /** A page of the menu: its name as the title, Back, then its rows. */
    private class PageElement(
        private val map: GuiMap,
        private val name: String,
        private val back: Page,
        private val rows: (IRightClickableElement) -> List<RightClickOption>,
    ) : IRightClickableElement {
        override fun getRightClickOptions(): ArrayList<RightClickOption> {
            val page = ArrayList<RightClickOption>()
            page += object : RightClickOption(name, 0, this) {
                override fun onAction(screen: Screen) {}
            }
            page += MapMenus.option("‹ Back", 0, this) { pending = back }
            page += rows(this)
            renumber(page)
            return page
        }

        override fun isRightClickValid(): Boolean = map.isRightClickValid
    }

    private const val TITLE = "gui.xaero_right_click_map_title"
    private val DISTANCE = Regex("""[0-9.,]+\s*(m|km)""")

    /** Xaero's own rows by their names (translation keys), each to the id the layout knows it by. */
    private val KEYS = mapOf(
        "gui.xaero_right_click_map_create_waypoint" to "x_wp",
        "gui.xaero_right_click_map_create_temporary_waypoint" to "x_tmp",
        "gui.xaero_right_click_map_teleport" to "x_tp",
        "gui.xaero_wm_right_click_map_teleport_not_allowed" to "x_tp",
        "gui.xaero_right_click_map_cant_teleport" to "x_tp",
        "gui.xaero_right_click_map_cant_teleport_world" to "x_tp",
        "gui.xaero_right_click_map_share_location" to "x_share",
        "gui.xaero_right_click_map_waypoints_menu" to "x_wpmenu",
        "gui.xaero_right_click_box_map_export" to "x_png",
        "gui.xaero_right_click_box_map_settings" to "x_settings",
    )
}
