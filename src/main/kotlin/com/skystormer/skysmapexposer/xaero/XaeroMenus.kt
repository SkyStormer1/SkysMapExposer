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

    /** The page to open on the next tick. */
    private var pending: Page? = null

    /**
     * [options] (Xaero's own, as it made them) and [ours] in your layout, in place. [copy] copies
     * the clicked block's coordinates, for Xaero's coordinates line. False if it could not be done,
     * leaving [options] as they were.
     */
    fun arrange(options: ArrayList<RightClickOption>, ours: List<Pair<String, RightClickOption>>, target: IRightClickableElement, copy: () -> Unit): Boolean = try {
        val header = ArrayList<Pair<String, Row>>()
        val items = ArrayList<Pair<String, Row>>()
        for (option in options) {
            val name = (option as RightClickOptionAccess).`skysmapexposer$name`() ?: ""
            when {
                name == TITLE -> header += "title" to Row(option)
                name.startsWith("C: (") -> header += "chunk" to Row(option)
                // Xaero's line does nothing when clicked: here it copies them.
                name.startsWith("X: ") -> header += "coords" to Row(MapMenus.option(option.displayName.string, 0, target) { copy() })
                // How far the clicked block is from you.
                DISTANCE.matches(option.displayName.string.trim()) -> header += "distance" to Row(option)
                else -> items += (KEYS[name] ?: "xaero:$name") to Row(option)
            }
        }
        for ((id, option) in ours) items += id to Row(option)
        val arranged = MenuLayout.arrange(MenuLayout.XAERO, header, items)
        last = arranged
        val page = ArrayList<RightClickOption>()
        arranged.header.forEach { page += it.option }
        arranged.menu.forEach { page += row(it, target, inMore = false) }
        if (arranged.more.isNotEmpty()) page += MapMenus.option("More  ›", 0, target) { pending = Page.More }
        page += MapMenus.option("§7Edit menu…", 0, target) { parent -> Ui.open(MenuEditScreen(parent, MenuLayout.XAERO)) }
        replace(options, page)
        true
    } catch (e: Throwable) {
        Log.warn("Could not lay out Xaero's right-click menu: {}", e.toString())
        false
    }

    private fun row(node: MenuLayout.Node<Row>, target: IRightClickableElement, inMore: Boolean): RightClickOption = when (node) {
        is MenuLayout.Node.Item -> node.value.option
        is MenuLayout.Node.Group -> MapMenus.option("${node.name}  ›", 0, target) { pending = Page.Group(node, inMore) }
    }

    private fun replace(options: ArrayList<RightClickOption>, rows: List<RightClickOption>) {
        options.clear()
        options.addAll(rows)
        options.forEachIndexed { i, option -> (option as RightClickOptionAccess).`skysmapexposer$setIndex`(i) }
    }

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
            (map as GuiMapMenuAccess).`skysmapexposer$handleRightClick`(element, openedX, openedY)
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
            replace(page, ArrayList(page))
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
