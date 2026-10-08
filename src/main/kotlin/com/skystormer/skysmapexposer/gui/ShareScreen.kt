package com.skystormer.skysmapexposer.gui

import com.skystormer.skysmapexposer.ChatShare
import com.skystormer.skysmapexposer.MapMenus
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * Picks who to share with: everyone else on the server, narrowed by a search box. Picking a name
 * hands it to [share], goes to [after] (the map) and says what [share] said; Cancel goes back to
 * [parent].
 */
class ShareScreen(
    private val parent: Screen?,
    title: String,
    private val after: Screen? = parent,
    private val share: (String) -> String,
) : Screen(Component.literal(title)) {

    private var search: EditBox? = null
    private val rows = ArrayList<Button>()

    override fun init() {
        val width = minOf(WIDTH, this.width - 32)
        val left = (this.width - width) / 2
        var y = this.height / 2 - (ROWS * (ROW + GAP) + 60) / 2

        addRenderableWidget(StringWidget(left, y, width, font.lineHeight, title, font))
        y += font.lineHeight + GAP * 2
        val box = EditBox(font, left, y, width, ROW, Component.literal("Search"))
        box.setHint(Component.literal("Search players"))
        box.setValue(search?.value ?: "")
        box.setResponder { fill() }
        search = box
        addRenderableWidget(box)
        y += ROW + GAP * 2
        rows.clear()
        repeat(ROWS) {
            val row = Button.builder(Component.empty()) { button -> pick(button.message.string) }.bounds(left, y, width, ROW).build()
            rows.add(row)
            addRenderableWidget(row)
            y += ROW + GAP
        }
        y += GAP
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL) { onClose() }.bounds(left, y, width, ROW).build())
        setInitialFocus(box)
        fill()
    }

    /** The first [ROWS] players whose names have the search in them; the spare rows hide. */
    private fun fill() {
        val wanted = search?.value?.trim()?.lowercase().orEmpty()
        val names = ChatShare.others().filter { wanted in it.lowercase() }
        rows.forEachIndexed { i, row ->
            val name = names.getOrNull(i)
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

    private fun pick(name: String) {
        val said = share(name)
        MapMenus.open(after)
        MapMenus.say(said)
    }

    override fun onClose() {
        MapMenus.open(parent)
    }

    private companion object {
        const val WIDTH = 200
        const val ROWS = 6
        const val ROW = 20
        const val GAP = 4
    }
}
