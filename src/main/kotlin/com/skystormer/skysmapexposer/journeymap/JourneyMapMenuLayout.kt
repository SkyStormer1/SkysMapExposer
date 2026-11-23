package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MenuLayout
import com.skystormer.skysmapexposer.Ui
import com.skystormer.skysmapexposer.gui.MenuEditScreen
import journeymap.api.client.impl.ModPopupMenuImpl
import journeymap.api.v2.client.fullscreen.ModPopupMenu
import journeymap.client.Constants
import net.minecraft.client.gui.screens.Screen

/**
 * JourneyMap's right-click menu on the full-screen map in your layout ([MenuLayout]): every item,
 * JourneyMap's own and this mod's, is in one list by the time JourneyMap draws the menu, so they are
 * put in your order there, your groups becoming JourneyMap sub-menus, with More and Edit menu… at
 * the end. Through `PopupMenuLayoutMixin`, only for the map's own menu (not a waypoint's).
 */
internal object JourneyMapMenuLayout {

    private class Row(val item: ModPopupMenuImpl.MenuItem) : MenuLayout.Labelled {
        override val menuLabel: String get() = item.label.replace(Regex("§."), "")
    }

    /** This mod's items on the menu being made, by label, to the id the layout knows each by. */
    private val ids = HashMap<String, String>()
    private var parent: Screen? = null

    /** A new menu is being made, over [screen]. */
    fun begin(screen: Screen?) {
        ids.clear()
        parent = screen
    }

    /** Adds this mod's item [id] to [menu] as [label]. */
    fun item(menu: ModPopupMenu, id: String, label: String, action: ModPopupMenu.Action) {
        ids[label] = id
        menu.addMenuItem(label, action)
    }

    /** Whether JourneyMap is making the map's own menu just now (not a waypoint's). */
    private var basic = false

    @JvmStatic
    fun basicStart() {
        basic = true
    }

    @JvmStatic
    fun basicEnd() {
        basic = false
    }

    /** For `PopupMenuLayoutMixin`: [menu], with every item in it, put in your layout just before JourneyMap draws it. */
    @JvmStatic
    fun arrange(menu: ModPopupMenu) {
        if (!basic) return
        basic = false
        try {
            val impl = menu as? ModPopupMenuImpl ?: return
            val list = impl.menuItemList
            val header = ArrayList<Pair<String, Row>>()
            val items = ArrayList<Pair<String, Row>>()
            fun add(id: String, item: ModPopupMenuImpl.MenuItem) {
                if (id == "coords") header += id to Row(item) else items += id to Row(item)
            }
            // JourneyMap's own Waypoints sub-menu opened up, so each of its items can be placed on its own.
            val children = childMenus(impl)
            var sub = 0
            for (item in ArrayList(list)) {
                val child = if (isSubMenu(item)) children.getOrNull(sub++) else null
                val own = OWN[item.label]
                if (own == WAYPOINTS && child != null) {
                    for (inner in child.menuItemList) add(OWN[inner.label] ?: "jm:${inner.label}", inner)
                    continue
                }
                add(ids[item.label] ?: own ?: "jm:${item.label}", item)
            }
            val arranged = MenuLayout.arrange(MenuLayout.JOURNEYMAP, header, items)
            list.clear()
            arranged.header.forEach { list += it.item }
            put(impl, arranged.menu)
            if (arranged.more.isNotEmpty()) put(impl.createSubItemList("More") as ModPopupMenuImpl, arranged.more)
            val over = parent
            impl.addMenuItem("§7Edit menu…") { Ui.open(MenuEditScreen(over, MenuLayout.JOURNEYMAP)) }
        } catch (e: Throwable) {
            Log.warn("Could not lay out JourneyMap's right-click menu: {}", e.toString())
        }
    }

    private fun put(menu: ModPopupMenuImpl, nodes: List<MenuLayout.Node<Row>>) {
        for (node in nodes) when (node) {
            is MenuLayout.Node.Item -> menu.menuItemList += node.value.item
            is MenuLayout.Node.Group -> {
                val sub = menu.createSubItemList(node.name) as ModPopupMenuImpl
                node.items.forEach { sub.menuItemList += it.item }
            }
        }
    }

    /** Whether [item] opens a sub-menu: JourneyMap keeps either kind of action in one field, and its getters cast it. */
    private fun isSubMenu(item: ModPopupMenuImpl.MenuItem): Boolean = try {
        ACTION.get(item) is ModPopupMenuImpl.SubMenuAction
    } catch (e: ReflectiveOperationException) {
        false
    }

    private val ACTION by lazy { ModPopupMenuImpl.MenuItem::class.java.getDeclaredField("action").apply { isAccessible = true } }

    @Suppress("UNCHECKED_CAST")
    private fun childMenus(menu: ModPopupMenuImpl): List<ModPopupMenuImpl> = try {
        ModPopupMenuImpl::class.java.getDeclaredField("childMenus").apply { isAccessible = true }.get(menu) as List<ModPopupMenuImpl>
    } catch (e: ReflectiveOperationException) {
        emptyList()
    }

    private const val WAYPOINTS = "j_waypoints"

    /** JourneyMap's own items by their label in your language, to the id the layout knows each by. */
    private val OWN: Map<String, String> by lazy {
        mapOf(
            "key.journeymap.create_waypoint" to "j_wp",
            "jm.waypoint.create_temp_waypoint" to "j_tmp",
            "jm.waypoint.create_global_waypoint" to "j_global",
            "jm.waypoint.teleport" to "j_tp",
            "jm.waypoint.waypoints" to WAYPOINTS,
            "jm.waypoint.show_all" to "j_showall",
            "jm.waypoint.hide_all" to "j_hideall",
            "jm.fullscreen.menu.advanced" to "j_adv",
        ).mapKeys { (key, _) -> Constants.getString(key) }
    }
}
