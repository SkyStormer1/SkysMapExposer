package com.skystormer.skysmapexposer.gui

import com.skystormer.skysmapexposer.Ui
import com.skystormer.skysmapexposer.ChatShare
import com.skystormer.skysmapexposer.Markers
import com.skystormer.skysmapexposer.ShareFormat
import com.skystormer.skysmapexposer.WaypointRules
import kotlin.math.floor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * Picks who to share with: everyone else on the server, a page at a time (the arrows or the
 * scroll wheel), narrowed by a search box; or, given [everyone], everyone at once in chat. Picking
 * hands the name to [share] (or calls [everyone]), goes to [after] (the map) and says what it said;
 * Cancel goes back to [parent].
 */
class ShareScreen(
    private val parent: Screen?,
    title: String,
    private val after: Screen? = parent,
    private val everyone: (() -> String)? = null,
    private val share: (String) -> String,
) : Screen(Component.literal(title)) {

    private var search: EditBox? = null
    private val rows = ArrayList<Button>()
    private var page = 0
    private var pages = 1
    private var pageLabel: StringWidget? = null
    private var previous: Button? = null
    private var nextPage: Button? = null

    override fun init() {
        val width = minOf(WIDTH, this.width - 32)
        val left = (this.width - width) / 2
        val extra = if (everyone != null) ROW + GAP else 0
        var y = maxOf(8, this.height / 2 - (ROWS * (ROW + GAP) + 76 + extra) / 2)

        addRenderableWidget(StringWidget(left, y, width, font.lineHeight, title, font))
        y += font.lineHeight + GAP * 2
        val arrows = ROW + GAP
        val box = EditBox(font, left, y, width - arrows * 2, ROW, Component.literal("Search"))
        box.setHint(Component.literal("Search players"))
        box.setValue(search?.value ?: "")
        box.setResponder { page = 0; fill() }
        search = box
        addRenderableWidget(box)
        previous = addRenderableWidget(Button.builder(Component.literal("<")) { turn(-1) }.bounds(left + width - arrows * 2 + GAP, y, ROW, ROW).build())
        nextPage = addRenderableWidget(Button.builder(Component.literal(">")) { turn(1) }.bounds(left + width - arrows + GAP, y, ROW, ROW).build())
        y += ROW + GAP * 2
        everyone?.let { toAll ->
            addRenderableWidget(Button.builder(Component.literal("Everyone, in chat")) { done(toAll()) }.bounds(left, y, width, ROW)
                .tooltip(SafeTooltip.of("Posts them in chat as Xaero's waypoint lines: anyone with Xaero's Minimap or JourneyMap can add them, with this mod or without.")).build())
            y += ROW + GAP
        }
        rows.clear()
        repeat(ROWS) {
            val row = Button.builder(Component.empty()) { button -> pick(button.message.string) }.bounds(left, y, width, ROW).build()
            rows.add(row)
            addRenderableWidget(row)
            y += ROW + GAP
        }
        pageLabel = addRenderableWidget(StringWidget(left, y, width, font.lineHeight, Component.empty(), font))
        y += font.lineHeight + GAP * 2
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL) { onClose() }.bounds(left, y, width, ROW).build())
        setInitialFocus(box)
        fill()
    }

    private fun turn(by: Int) {
        page += by
        fill()
    }

    /** This page of the players whose names have the search in them; the spare rows hide. */
    private fun fill() {
        val wanted = search?.value?.trim()?.lowercase().orEmpty()
        val names = ChatShare.others().filter { wanted in it.lowercase() }
        pages = maxOf(1, (names.size + ROWS - 1) / ROWS)
        page = page.coerceIn(0, pages - 1)
        pageLabel?.message = Component.literal("§7Page ${page + 1} of $pages · ${names.size} player${if (names.size == 1) "" else "s"}")
        previous?.active = page > 0
        nextPage?.active = page < pages - 1
        rows.forEachIndexed { i, row ->
            val name = names.getOrNull(page * ROWS + i)
            row.visible = name != null
            row.active = name != null
            row.message = Component.literal(name ?: "")
        }
        if (names.isEmpty()) {
            rows.first().apply {
                visible = true
                active = false
                message = Component.literal(if (wanted.isEmpty()) "Nobody else is on the server" else "Nobody by that name")
            }
        }
    }

    private fun pick(name: String) = done(share(name))

    private fun done(said: String) {
        Ui.open(after)
        Ui.say(said)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY == 0.0 || pages <= 1) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        turn(if (scrollY < 0) 1 else -1)
        return true
    }

    override fun onClose() {
        Ui.open(parent)
    }

    companion object {
        private const val WIDTH = 200
        private const val ROWS = 6
        private const val ROW = 20
        private const val GAP = 4

        /** Picks who gets [waypoint] of [dimension]: a player, or everyone in chat; back to [parent] after. */
        fun forWaypoint(parent: Screen?, dimension: String, waypoint: ShareFormat.SharedWaypoint): ShareScreen =
            ShareScreen(parent, "Share ${waypoint.name} with…", everyone = { ChatShare.shareWaypointsWithEveryone(dimension, listOf(waypoint)) }) { name ->
                ChatShare.shareWaypoints(name, dimension, listOf(waypoint))
            }

        /** A BlueMap marker or player as a waypoint to share, named and coloured as saving it makes one. */
        fun forPin(parent: Screen?, dimension: String, pin: Markers.Pin): ShareScreen = forWaypoint(parent, dimension, ShareFormat.SharedWaypoint(
            pin.label, WaypointRules.initials(pin.label), floor(pin.x).toInt(), floor(pin.y).toInt(), floor(pin.z).toInt(), true, WaypointRules.colourFor(pin),
        ))
    }
}
