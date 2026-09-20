package com.skystormer.skysmapexposer.gui

import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.LockedPlayers
import com.skystormer.skysmapexposer.MapCamera
import com.skystormer.skysmapexposer.MapMenus
import com.skystormer.skysmapexposer.MapView
import com.skystormer.skysmapexposer.MarkerElements
import com.skystormer.skysmapexposer.Markers
import com.skystormer.skysmapexposer.Session
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Everyone BlueMap can see on this server, like Xaero's waypoint list: search by name, your
 * dimension or all of them, jump the world map to someone, and lock on to them.
 *
 * Opened from the Players button on the world map, its right-click menu, the settings screen, or
 * `/mapexposer players`. The list refreshes itself while it is open, because people move; the
 * mouse wheel turns the pages.
 */
class PlayerListScreen(private val parent: Screen?) : Screen(Component.literal("Players")) {

    /** One player, and the dimension of the BlueMap map they were found on. */
    private class Row(val dimension: String, val player: Markers.Player)

    private var page = 0
    private var query = ""
    private var allDimensions = false

    /** The widgets of the list itself, replaced on every change without touching the search box. */
    private val rowWidgets = ArrayList<AbstractWidget>()
    private var rowsTop = 0
    private var rows = 0

    /** What the rows last said, so they are only rebuilt when something actually changed. */
    private var shown = ""
    private var ticks = 0

    private lateinit var pageLabel: StringWidget
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var unlockAll: Button

    private val left get() = width / 2 - listWidth / 2
    private val listWidth get() = minOf(MAX_WIDTH, width - 16)

    override fun init() {
        var y = 6
        val session = Session.current
        val heading = when {
            session == null -> "Players: this server has no BlueMap in the settings"
            else -> "Players on ${session.server.addresses.firstOrNull() ?: session.server.url}"
        }
        addRenderableWidget(StringWidget(left, y, listWidth, font.lineHeight, Component.literal(heading), font))
        y += font.lineHeight + GAP * 2

        val half = (listWidth - GAP) / 2
        val search = EditBox(font, left, y, half, ROW, Component.literal("Search"))
        search.setHint(Component.literal("Search names…"))
        search.setMaxLength(64)
        search.value = query
        search.setResponder { query = it; page = 0; refreshRows(force = true) }
        addRenderableWidget(search)
        addRenderableWidget(
            CycleButton.builder<Boolean>({ Component.literal(if (it) "All dimensions" else "My dimension") }, allDimensions)
                .withValues(listOf(false, true))
                .displayOnlyValue()
                .create(left + half + GAP, y, listWidth - half - GAP, ROW, Component.literal("Dimension")) { _, all ->
                    allDimensions = all
                    page = 0
                    refreshRows(force = true)
                }
        )
        y += ROW + GAP * 2

        rowsTop = y
        val footer = (ROW + GAP) * 2 + GAP * 2
        rows = ((height - rowsTop - footer - 4) / (ROW + GAP)).coerceAtLeast(2)
        y = rowsTop + rows * (ROW + GAP) + GAP

        val quarter = (listWidth - GAP * 3) / 4
        previous = addRenderableWidget(Button.builder(Component.literal("<")) { page--; refreshRows(force = true) }
            .bounds(left, y, quarter, ROW).build())
        pageLabel = addRenderableWidget(StringWidget(left + quarter + GAP, y + 6, quarter, font.lineHeight, Component.empty(), font))
        next = addRenderableWidget(Button.builder(Component.literal(">")) { page++; refreshRows(force = true) }
            .bounds(left + (quarter + GAP) * 2, y, quarter, ROW).build())
        unlockAll = addRenderableWidget(
            Button.builder(Component.literal("Unlock all")) {
                Session.current?.locks?.clear()
                MapMenus.say("Unlocked everyone")
                refreshRows(force = true)
            }.bounds(left + (quarter + GAP) * 3, y, listWidth - (quarter + GAP) * 3, ROW)
                .tooltip(Tooltip.create(Component.literal("Stop following every player you have locked on to.")))
                .build()
        )
        y += ROW + GAP

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }.bounds(left, y, listWidth, ROW).build())

        refreshRows(force = true)
    }

    /**
     * Asks BlueMap again while the list is open, so people move and join without reopening it.
     * BlueMap is only read every couple of seconds anyway, so once a second is often enough.
     */
    override fun tick() {
        super.tick()
        if (++ticks % 20 == 0) refreshRows(force = false)
    }

    /** The players this list shows: matching the search, in the chosen dimensions, nearest first. */
    private fun players(): List<Row> {
        val session = Session.current ?: return emptyList()
        val here = myDimension()
        val me = minecraft.player?.uuid
        val words = query.trim().lowercase()
        val dimensions = if (allDimensions) session.server.maps.keys else setOfNotNull(here)
        val found = ArrayList<Row>()
        for (dimension in dimensions) {
            val map = session.server.markersFor(dimension) ?: continue
            for (player in session.markers.players(map)) {
                if (player.uuid == me) continue
                if (words.isNotEmpty() && !player.label.lowercase().contains(words)) continue
                found.add(Row(dimension, player))
            }
        }
        return found.sortedWith(
            compareBy(
                { !LockedPlayers.isLocked(it.player.uuid) },
                { it.dimension != here },
                { distanceTo(it) ?: Double.MAX_VALUE },
                { it.player.label.lowercase() },
            )
        )
    }

    private fun refreshRows(force: Boolean) {
        val players = players()
        val pages = maxOf(1, (players.size + rows - 1) / rows)
        page = page.coerceIn(0, pages - 1)
        val visible = players.drop(page * rows).take(rows)
        // Rebuilding every tick would flicker the tooltips, so it only happens on a real change.
        val signature = visible.joinToString("|") {
            "${it.player.uuid}${it.player.x.toInt()}${it.player.z.toInt()}${LockedPlayers.isLocked(it.player.uuid)}"
        } + "/$pages/$page"
        if (!force && signature == shown) return
        shown = signature

        rowWidgets.forEach(::removeWidget)
        rowWidgets.clear()
        pageLabel.message = Component.literal("${page + 1} / $pages")
        previous.active = page > 0
        next.active = page < pages - 1
        unlockAll.active = LockedPlayers.count() > 0

        if (players.isEmpty()) {
            val text = when {
                Session.current == null -> "This server has no BlueMap in the settings, so there is nobody to list."
                !Config.showPlayers -> "Players are switched off in the settings."
                query.isNotBlank() -> "Nobody here matches \"$query\"."
                allDimensions -> "BlueMap is not reporting anybody online."
                else -> "Nobody else is in your dimension. Choose All dimensions to see the rest."
            }
            row(StringWidget(left, rowsTop + 6, listWidth, font.lineHeight, Component.literal(text), font))
            return
        }

        // Go to moves the open world map, or opens one; either way only for the dimension it shows.
        val mapDimension = MarkerElements.worldMapDimension() ?: myDimension()
        val buttonWidth = 44
        val textWidth = listWidth - (buttonWidth + GAP) * 2

        // The columns are as wide as the widest thing going in them, measured over every player the
        // list holds rather than just this page, so paging does not shuffle them sideways.
        val cells = players.map { it to text(it) }
        val columns = PlayerRowWidget.columns(
            cells.maxOf { font.width(it.first.player.label) },
            cells.maxOf { font.width(it.second.first) },
            cells.maxOf { font.width(it.second.second) },
            textWidth - GAP,
        )

        var y = rowsTop
        for (entry in visible) {
            val player = entry.player
            val locked = LockedPlayers.isLocked(player.uuid)
            val (coordinates, distance) = text(entry)
            val text = PlayerRowWidget(
                left, y, textWidth - GAP, ROW,
                player.uuid, player.label, coordinates, distance,
                if (locked) LOCKED else NAME,
                columns,
            )
            text.setTooltip(Tooltip.create(Component.literal(
                "${player.label}\n$coordinates in ${dimensionName(entry.dimension)}\n" +
                    if (locked) "Locked on: their head follows them in the world." else "Not locked on."
            )))
            row(text)

            var x = left + textWidth
            val go = Button.builder(Component.literal("Go to")) { goTo(entry) }
                .bounds(x, y, buttonWidth, ROW)
                .tooltip(Tooltip.create(Component.literal(
                    if (entry.dimension == mapDimension) "Show ${player.label} on the world map."
                    else "Switch the world map to the ${dimensionName(entry.dimension)} and show " +
                        "${player.label}. It switches back when you leave the map."
                )))
                .build()
            row(go)
            x += buttonWidth + GAP

            val lock = Button.builder(Component.literal(if (locked) "Unlock" else "Lock")) {
                LockedPlayers.set(player, !locked)
                refreshRows(force = true)
            }
                .bounds(x, y, listWidth - (x - left), ROW)
                .tooltip(Tooltip.create(Component.literal(
                    if (locked) "Stop following ${player.label}."
                    else "Follow ${player.label}: their pin keeps up with them and their head shows over them in the world."
                )))
                .build()
            lock.active = Session.current != null
            row(lock)
            y += ROW + GAP
        }
    }

    private fun row(widget: AbstractWidget) {
        rowWidgets.add(addRenderableWidget(widget))
    }

    /**
     * A row's coordinates and its distance, as they are written. The locked state is not in here:
     * it is the green name and the Unlock button, because a word of varying length in front of the
     * coordinates would push them out of line, which is the whole thing the columns are for.
     */
    private fun text(entry: Row): Pair<String, String> {
        val player = entry.player
        val coordinates = "${player.x.toInt()}, ${player.y.toInt()}, ${player.z.toInt()}"
        val distance = buildString {
            distanceTo(entry)?.let { append(it.toInt()).append("m away") }
            if (allDimensions) {
                if (isNotEmpty()) append(" · ")
                append(dimensionName(entry.dimension))
            }
        }
        return coordinates to distance
    }

    /**
     * Shows this player on the world map, switching the map to their dimension when it is showing
     * another one. [MapView] puts that back when you leave the map, so a look at someone in the
     * Nether does not quietly leave your map — and your minimap — stuck there.
     */
    private fun goTo(entry: Row) {
        val x = floor(entry.player.x).toInt()
        val z = floor(entry.player.z).toInt()
        if (MapView.goTo(entry.dimension, x, z, parent)) {
            // Only step back to a map that was already open. When it was not, MapView has just
            // opened one, and closing this list would put whatever was behind it back on top.
            if (parent is MapCamera) onClose()
        } else {
            MapMenus.say("Could not open the world map at ${entry.player.label}")
        }
    }

    private fun myDimension(): String? = minecraft.player?.level()?.dimension()?.identifier()?.toString()

    /** Whole blocks from you to them, when you are in their dimension. */
    private fun distanceTo(entry: Row): Double? {
        val player = minecraft.player ?: return null
        if (myDimension() != entry.dimension) return null
        return hypot(entry.player.x - player.x, entry.player.z - player.z)
    }

    /** A dark panel behind everything, so the text reads over any background. */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        graphics.fill(left - 6, 2, left + listWidth + 6, height - 2, PANEL)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY != 0.0) {
            page += if (scrollY < 0) 1 else -1
            refreshRows(force = true)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    companion object {
        fun dimensionName(dimension: String): String = MapMenus.dimensionName(dimension) ?: dimension

        private const val MAX_WIDTH = 420
        // ARGB: these are drawn onto the screen by PlayerRowWidget, not put in a Component.
        private const val NAME = 0xFFFFFFFF.toInt()
        private const val LOCKED = 0xFF55FF55.toInt()
        private const val PANEL = 0xC0101010.toInt()
        private const val ROW = 20
        private const val GAP = 2
    }
}
