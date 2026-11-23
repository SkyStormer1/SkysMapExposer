package com.skystormer.skysmapexposer.gui

import com.skystormer.skysmapexposer.MenuLayout
import com.skystormer.skysmapexposer.MenuLayout.Entry
import com.skystormer.skysmapexposer.Ui
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Edit menu…: the map's right-click menu laid out your way ([MenuLayout]), for [map] (one layout
 * for each map mod). The lines at the top switched on or off; the menu's items in order, put in
 * groups, or hidden under More, which is laid out the same way. Every change is saved at once and
 * shows the next time the menu opens.
 */
class MenuEditScreen(private val parent: Screen?, private val map: String) : Screen(Component.literal("Edit menu")) {

    private var layout = withAll(MenuLayout.get(map))

    /** Which list is shown: the menu, or More. */
    private var showingMore = false
    private var page = 0

    private sealed interface Line {
        class Item(val id: String, val group: Entry.Group?, val index: Int, val count: Int) : Line
        class Group(val group: Entry.Group, val index: Int, val count: Int) : Line
        data object Empty : Line
    }

    override fun init() {
        val width = minOf(WIDTH, this.width - 16)
        val left = (this.width - width) / 2
        var y = 6
        val mapName = if (map == MenuLayout.XAERO) "Xaero's World Map" else "JourneyMap"
        addRenderableWidget(StringWidget(left, y, width, font.lineHeight, Component.literal("Edit menu · $mapName"), font))
        y += font.lineHeight + GAP

        // The lines at the top, each switched on or off.
        val lines = MenuLayout.headerLines(map)
        val each = (width - GAP * (lines.size - 1)) / lines.size
        lines.forEachIndexed { i, (id, name) ->
            val on = layout.header[id] != false
            addRenderableWidget(Button.builder(Component.literal((if (on) "§a☑ " else "§7☐ ") + "§r" + shortHeader(id))) {
                change(layout.copy(header = layout.header + (id to !on)))
            }.bounds(left + i * (each + GAP), y, each, ROW).tooltip(SafeTooltip.of("$name, at the top of the menu. Click to ${if (on) "hide" else "show"} it.")).build())
        }
        y += ROW + GAP * 2

        // The menu or More, and a new group in the one shown.
        val third = (width - GAP * 2) / 3
        val moreCount = count(layout.more)
        addRenderableWidget(Button.builder(Component.literal(if (!showingMore) "§e§nMenu" else "Menu")) { showingMore = false; page = 0; rebuildWidgets() }
            .bounds(left, y, third, ROW).tooltip(SafeTooltip.of("What the right-click menu shows, top to bottom.")).build())
        addRenderableWidget(Button.builder(Component.literal((if (showingMore) "§e§n" else "") + "More ($moreCount)")) { showingMore = true; page = 0; rebuildWidgets() }
            .bounds(left + third + GAP, y, third, ROW).tooltip(SafeTooltip.of("What you hid: still on the menu under More, laid out the same way. More is left off the menu while it is empty.")).build())
        addRenderableWidget(Button.builder(Component.literal("+ New group")) { newGroup() }
            .bounds(left + (third + GAP) * 2, y, width - (third + GAP) * 2, ROW).tooltip(SafeTooltip.of("A group in this list. Put items in it with their group button; it stays off the menu while it is empty.")).build())
        y += ROW + GAP * 2

        // The rows of the list shown, a page at a time.
        val footer = ROW * 2 + GAP * 3
        val rowsPerPage = maxOf(3, (this.height - y - footer) / (ROW + GAP))
        val all = lines()
        val pages = maxOf(1, (all.size + rowsPerPage - 1) / rowsPerPage)
        page = page.coerceIn(0, pages - 1)
        for (line in all.drop(page * rowsPerPage).take(rowsPerPage)) {
            addLine(line, left, y, width)
            y += ROW + GAP
        }
        if (all.isEmpty()) {
            addRenderableWidget(StringWidget(left, y + 6, width, font.lineHeight,
                Component.literal(if (showingMore) "§7Nothing hidden, so More is left off the menu" else "§7Everything is hidden: the menu shows only More"), font))
        }

        // Pages, reset and done, along the bottom.
        val bottom = this.height - ROW - GAP
        val quarter = (width - GAP * 3) / 4
        addRenderableWidget(Button.builder(Component.literal("<")) { page--; rebuildWidgets() }.bounds(left, bottom - ROW - GAP, quarter / 2 - 2, ROW).build()).active = page > 0
        addRenderableWidget(Button.builder(Component.literal(">")) { page++; rebuildWidgets() }.bounds(left + quarter / 2 + 2, bottom - ROW - GAP, quarter / 2 - 2, ROW).build()).active = page < pages - 1
        addRenderableWidget(StringWidget(left + quarter + GAP, bottom - ROW - GAP + 6, width - quarter - GAP, font.lineHeight,
            Component.literal("§7Page ${page + 1} of $pages · changes show next time the menu opens"), font))
        addRenderableWidget(Button.builder(Component.literal("Reset to default")) {
            MenuLayout.reset(map)
            layout = withAll(MenuLayout.get(map))
            rebuildWidgets()
        }.bounds(left, bottom, (width - GAP) / 2, ROW).tooltip(SafeTooltip.of("Puts this map's menu back as Map Exposer first laid it out.")).build())
        addRenderableWidget(Button.builder(Component.literal("Done")) { onClose() }.bounds(left + (width - GAP) / 2 + GAP, bottom, width - (width - GAP) / 2 - GAP, ROW).build())
    }

    private fun addLine(line: Line, left: Int, y: Int, width: Int) {
        val buttons = ARROW * 2 + GROUP + FLIP + GAP * 4
        val labelWidth = width - buttons
        var x = left + labelWidth + GAP
        fun button(text: String, w: Int, tip: String?, active: Boolean = true, onPress: () -> Unit) {
            val b = Button.builder(Component.literal(text)) { onPress() }.bounds(x, y, w, ROW)
            if (tip != null) b.tooltip(SafeTooltip.of(tip))
            addRenderableWidget(b.build()).active = active
            x += w + GAP
        }
        val flipText = if (showingMore) "Show" else "Hide"
        when (line) {
            is Line.Empty -> addRenderableWidget(StringWidget(left + INDENT, y + 6, width - INDENT, font.lineHeight,
                Component.literal("§8Empty: stays off the menu until something is put in it"), font))
            is Line.Group -> {
                val box = EditBox(font, left, y, labelWidth, ROW, Component.literal("Group name"))
                box.setMaxLength(24)
                box.setValue(line.group.name)
                box.setResponder { name -> rename(line.group.id, name) }
                addRenderableWidget(box)
                button("↑", ARROW, "Move the group up", line.index > 0) { moveTop(line.index, -1) }
                button("↓", ARROW, "Move the group down", line.index < line.count - 1) { moveTop(line.index, 1) }
                button("Ungroup", GROUP, "Its items go back into the list where the group was") { ungroup(line.group.id) }
                button(flipText, FLIP, if (showingMore) "The whole group back onto the menu" else "The whole group under More") { flipGroup(line.group.id) }
            }
            is Line.Item -> {
                val indent = if (line.group != null) INDENT else 0
                val name = MenuLayout.name(map, line.id)
                addRenderableWidget(StringWidget(left + indent + 4, y + 6, labelWidth - indent - 4, font.lineHeight,
                    Component.literal((if (line.group != null) "§7└ §r" else "") + name), font))
                button("↑", ARROW, "Move up", line.index > 0) { if (line.group != null) moveIn(line.group.id, line.index, -1) else moveTop(line.index, -1) }
                button("↓", ARROW, "Move down", line.index < line.count - 1) { if (line.group != null) moveIn(line.group.id, line.index, 1) else moveTop(line.index, 1) }
                val groups = groupsShown()
                val groupText = line.group?.name?.let { "▸ $it" } ?: "No group"
                button(groupText, GROUP, if (groups.isEmpty()) "Make a group with + New group first" else "Click for the next group (or none)", groups.isNotEmpty()) { nextGroup(line.id, line.group) }
                button(flipText, FLIP, if (showingMore) "Back onto the menu" else "Under More, off the menu") { flip(line.id) }
            }
        }
    }

    // The list shown and what is done to it

    private fun shown(): List<Entry> = if (showingMore) layout.more else layout.menu

    private fun withShown(list: List<Entry>) = if (showingMore) layout.copy(more = list) else layout.copy(menu = list)

    private fun groupsShown() = shown().filterIsInstance<Entry.Group>()

    private fun lines(): List<Line> {
        val list = shown()
        val out = ArrayList<Line>()
        list.forEachIndexed { i, entry ->
            when (entry) {
                is Entry.Item -> out += Line.Item(entry.id, null, i, list.size)
                is Entry.Group -> {
                    out += Line.Group(entry, i, list.size)
                    if (entry.items.isEmpty()) out += Line.Empty
                    entry.items.forEachIndexed { j, id -> out += Line.Item(id, entry, j, entry.items.size) }
                }
            }
        }
        return out
    }

    private fun change(next: Layout) {
        layout = next
        MenuLayout.set(map, next)
        rebuildWidgets()
    }

    private fun <T> List<T>.swapped(i: Int, j: Int): List<T> = toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t }

    private fun moveTop(i: Int, by: Int) = change(withShown(shown().swapped(i, i + by)))

    private fun moveIn(groupId: String, i: Int, by: Int) = change(withShown(shown().map {
        if (it is Entry.Group && it.id == groupId) it.copy(items = it.items.swapped(i, i + by)) else it
    }))

    private fun without(list: List<Entry>, id: String): List<Entry> = list.filterNot { it is Entry.Item && it.id == id }
        .map { if (it is Entry.Group) it.copy(items = it.items - id) else it }

    /** [id] into the next group of the list shown, after the last one back out where its group was. */
    private fun nextGroup(id: String, from: Entry.Group?) {
        val groups = groupsShown()
        val next = if (from == null) groups.firstOrNull() else groups.getOrNull(groups.indexOfFirst { it.id == from.id } + 1)
        val list = without(shown(), id).toMutableList()
        if (next == null) {
            val at = list.indexOfFirst { it.id == from?.id }
            list.add(at + 1, Entry.Item(id))
        } else {
            list.replaceAll { if (it is Entry.Group && it.id == next.id) it.copy(items = it.items + id) else it }
        }
        change(withShown(list))
    }

    private fun flip(id: String) {
        val here = without(shown(), id)
        val there = (if (showingMore) layout.menu else layout.more) + Entry.Item(id)
        change(if (showingMore) layout.copy(more = here, menu = there) else layout.copy(menu = here, more = there))
    }

    private fun flipGroup(groupId: String) {
        val group = shown().firstOrNull { it.id == groupId } ?: return
        val here = shown().filterNot { it.id == groupId }
        val there = (if (showingMore) layout.menu else layout.more) + group
        change(if (showingMore) layout.copy(more = here, menu = there) else layout.copy(menu = here, more = there))
    }

    private fun ungroup(groupId: String) = change(withShown(shown().flatMap {
        if (it is Entry.Group && it.id == groupId) it.items.map { id -> Entry.Item(id) } else listOf(it)
    }))

    /** Renamed as you type: saved, without making the rows again (which would take the typing away). */
    private fun rename(groupId: String, name: String) {
        layout = withShown(shown().map { if (it is Entry.Group && it.id == groupId) it.copy(name = name.ifBlank { "Group" }) else it })
        MenuLayout.set(map, layout)
    }

    private fun newGroup() {
        val taken = (layout.menu + layout.more).map { it.id }.toSet()
        val id = generateSequence(1) { it + 1 }.map { "g$it" }.first { it !in taken }
        change(withShown(shown() + Entry.Group(id, "New group", emptyList())))
    }

    private fun count(list: List<Entry>) = list.sumOf { if (it is Entry.Group) it.items.size else 1 }

    private fun shortHeader(id: String) = when (id) {
        "title" -> "Title"
        "chunk" -> "Chunk"
        "distance" -> "Distance"
        else -> "Coords"
    }

    override fun onClose() {
        Ui.open(parent)
    }

    private companion object {
        const val WIDTH = 380
        const val ROW = 20
        const val GAP = 4
        const val ARROW = 20
        const val GROUP = 92
        const val FLIP = 44
        const val INDENT = 12
    }

    /** [layout] with every item known for the map placed somewhere: one not in it goes at the end of the menu, as on the menu itself. */
    private fun withAll(layout: Layout): Layout {
        val placed = (layout.menu + layout.more).flatMap { if (it is Entry.Group) it.items else listOf(it.id) }.toSet()
        val missing = MenuLayout.knownIds(map).filter { it !in placed }
        return if (missing.isEmpty()) layout else layout.copy(menu = layout.menu + missing.map { Entry.Item(it) })
    }
}

private typealias Layout = MenuLayout.Layout
