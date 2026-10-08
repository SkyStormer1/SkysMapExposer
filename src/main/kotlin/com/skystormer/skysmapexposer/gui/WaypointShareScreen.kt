package com.skystormer.skysmapexposer.gui

import com.skystormer.skysmapexposer.ChatShare
import com.skystormer.skysmapexposer.MapMenus
import com.skystormer.skysmapexposer.WaypointShare
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Picks waypoints to share: every one of the dimension you are in, from all its sets, a page at a
 * time, narrowed by a search box; click a row to tick it. "Share with…" then picks the player
 * ([ShareScreen]) and sends them in chat ([ChatShare.shareWaypoints]).
 *
 * Only opened with Xaero's Minimap installed.
 */
class WaypointShareScreen(
    private val parent: Screen?,
    private val dimensionId: String,
    private val entries: List<WaypointShare.Entry>,
) : Screen(Component.literal("Share waypoints")) {

    private val picked: MutableSet<WaypointShare.Entry> = Collections.newSetFromMap(IdentityHashMap())
    private var search: EditBox? = null
    private val rows = ArrayList<Button>()
    private var page = 0
    private var shown: List<WaypointShare.Entry> = entries
    private var pageLabel: StringWidget? = null
    private var previous: Button? = null
    private var nextPage: Button? = null
    private var shareButton: Button? = null

    override fun init() {
        val width = minOf(WIDTH, this.width - 32)
        val left = (this.width - width) / 2
        var y = maxOf(8, this.height / 2 - (ROWS * (ROW + GAP) + 110) / 2)
        val sets = entries.map { it.set }.distinct().size
        val name = MapMenus.dimensionName(dimensionId) ?: dimensionId

        addRenderableWidget(StringWidget(left, y, width, font.lineHeight, title, font))
        y += font.lineHeight + GAP
        addRenderableWidget(StringWidget(left, y, width, font.lineHeight,
            Component.literal("§7${entries.size} in the $name" + if (sets > 1) ", from $sets sets" else ""), font))
        y += font.lineHeight + GAP * 2

        val box = EditBox(font, left, y, width, ROW, Component.literal("Search"))
        box.setHint(Component.literal("Search waypoints"))
        box.setValue(search?.value ?: "")
        box.setResponder { page = 0; fill() }
        search = box
        addRenderableWidget(box)
        y += ROW + GAP

        val third = (width - GAP * 2) / 3
        addRenderableWidget(Button.builder(Component.literal("Tick all")) { picked.addAll(shown); fill() }
            .bounds(left, y, third, ROW).tooltip(Tooltip.create(Component.literal("Ticks every waypoint the search shows, on every page."))).build())
        addRenderableWidget(Button.builder(Component.literal("Untick all")) { picked.clear(); fill() }
            .bounds(left + third + GAP, y, third, ROW).build())
        previous = addRenderableWidget(Button.builder(Component.literal("<")) { page--; fill() }
            .bounds(left + (third + GAP) * 2, y, (third - GAP) / 2, ROW).build())
        nextPage = addRenderableWidget(Button.builder(Component.literal(">")) { page++; fill() }
            .bounds(left + (third + GAP) * 2 + (third - GAP) / 2 + GAP, y, third - (third - GAP) / 2 - GAP, ROW).build())
        y += ROW + GAP * 2

        rows.clear()
        repeat(ROWS) { i ->
            val row = Button.builder(Component.empty()) { toggle(i) }.bounds(left, y, width, ROW).build()
            rows.add(row)
            addRenderableWidget(row)
            y += ROW + GAP
        }
        pageLabel = addRenderableWidget(StringWidget(left, y, width, font.lineHeight, Component.empty(), font))
        y += font.lineHeight + GAP * 2

        val half = (width - GAP) / 2
        shareButton = addRenderableWidget(Button.builder(Component.literal("Share with…")) { next() }.bounds(left, y, half, ROW).build())
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL) { onClose() }.bounds(left + half + GAP, y, width - half - GAP, ROW).build())
        setInitialFocus(box)
        fill()
    }

    private fun toggle(row: Int) {
        val entry = shown.getOrNull(page * ROWS + row) ?: return
        if (!picked.add(entry)) picked.remove(entry)
        fill()
    }

    private fun fill() {
        val wanted = search?.value?.trim()?.lowercase().orEmpty()
        shown = entries.filter { wanted.isEmpty() || wanted in it.name.lowercase() || wanted in it.set.lowercase() }
        val pages = maxOf(1, (shown.size + ROWS - 1) / ROWS)
        page = page.coerceIn(0, pages - 1)
        rows.forEachIndexed { i, row ->
            val entry = shown.getOrNull(page * ROWS + i)
            row.visible = entry != null
            row.active = entry != null
            row.message = Component.literal(if (entry == null) "" else (if (entry in picked) "§a☑ " else "§7☐ ") + "§r" + entry.label)
            row.setTooltip(entry?.let { Tooltip.create(Component.literal("Set: ${it.set}")) })
        }
        if (shown.isEmpty()) {
            rows.first().apply {
                visible = true
                active = false
                message = Component.literal(if (entries.isEmpty()) "You have no waypoints here" else "None by that name")
            }
        }
        pageLabel?.message = Component.literal("§7Page ${page + 1} of $pages · ${picked.size} ticked")
        previous?.active = page > 0
        nextPage?.active = page < pages - 1
        shareButton?.active = picked.isNotEmpty()
        shareButton?.message = Component.literal(if (picked.isEmpty()) "Share with…" else "Share ${picked.size} with…")
    }

    private fun next() {
        // In the order they are listed, not the order they were ticked.
        val chosen = entries.filter { it in picked }.map { it.shared() }
        MapMenus.open(ShareScreen(this, "Share ${chosen.size} waypoint${if (chosen.size == 1) "" else "s"} with…", parent) { name ->
            ChatShare.shareWaypoints(name, dimensionId, chosen)
        })
    }

    override fun onClose() {
        MapMenus.open(parent)
    }

    private companion object {
        const val WIDTH = 240
        const val ROWS = 8
        const val ROW = 20
        const val GAP = 4
    }
}
