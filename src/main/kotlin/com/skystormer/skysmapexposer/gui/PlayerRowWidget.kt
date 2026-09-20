package com.skystormer.skysmapexposer.gui

import com.skystormer.skysmapexposer.PinDrawing
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component
import java.util.UUID

/**
 * One player's line in [PlayerListScreen]: their head, then their name, their coordinates and how
 * far away they are.
 *
 * The name and coordinate columns start at the same offset in every row, so that reading down the
 * list for one player's coordinates works — a plain run of text would shift them along by however
 * long the name above happened to be. The widths come from [columns], measured from the players
 * actually listed rather than from the longest name and coordinates Minecraft allows, so the
 * columns are only as wide as they need to be. The distance sits against the right-hand end, the
 * way a number column in a table does, instead of leaving a ragged gap before the buttons.
 *
 * A widget of its own rather than a `StringWidget`, because a string cannot draw a head and cannot
 * hold columns.
 */
class PlayerRowWidget(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val uuid: UUID,
    private val name: String,
    private val coordinates: String,
    private val distance: String,
    private val nameColour: Int,
    private val columns: IntArray,
) : AbstractWidget(x, y, width, height, Component.literal(name)) {

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val font = Minecraft.getInstance().font
        val textY = this.y + (height - font.lineHeight) / 2 + 1
        PinDrawing.face(graphics, uuid, this.x, this.y + (height - HEAD) / 2, HEAD)

        var left = this.x + HEAD + GAP
        graphics.text(font, clip(font, name, columns[0]), left, textY, nameColour, true)
        left += columns[0] + GAP
        graphics.text(font, clip(font, coordinates, columns[1]), left, textY, DETAILS, true)

        val text = clip(font, distance, columns[2])
        graphics.text(font, text, this.x + width - font.width(text), textY, DETAILS, true)
    }

    override fun updateWidgetNarration(narration: NarrationElementOutput) {
        defaultButtonNarrationText(narration)
    }

    /** Nothing to click: the row's buttons are separate widgets beside it. */
    override fun isActive(): Boolean = false

    /**
     * An inactive widget normally asks for the "not allowed" cursor, which is not what a line of
     * text that simply has a tooltip should say. Leaving the cursor alone gives the ordinary arrow.
     * Hovering still works: the tooltip goes by `isHovered`, not by this.
     */
    override fun handleCursor(graphics: GuiGraphicsExtractor) {
    }

    private fun clip(font: Font, text: String, width: Int): String =
        if (font.width(text) <= width) text else font.plainSubstrByWidth(text, width - font.width("…")) + "…"

    companion object {
        /** The head, square, at the start of the row. */
        const val HEAD = 16

        const val GAP = 6

        /**
         * How wide to make the three text columns of a row [rowWidth] wide, given the widest name,
         * coordinates and distance that will go in them.
         *
         * They keep their measured widths when there is room, which is what stops the list being
         * mostly gap, and share what there is in proportion when there is not, so the columns still
         * line up on a narrow screen.
         */
        fun columns(name: Int, coordinates: Int, distance: Int, rowWidth: Int): IntArray {
            val room = (rowWidth - HEAD - GAP * 3).coerceAtLeast(3)
            val wanted = name + coordinates + distance
            if (wanted <= room) return intArrayOf(name, coordinates, distance)
            if (wanted <= 0) return intArrayOf(room / 3, room / 3, room - (room / 3) * 2)
            val shrunkName = room * name / wanted
            val shrunkCoordinates = room * coordinates / wanted
            return intArrayOf(shrunkName, shrunkCoordinates, room - shrunkName - shrunkCoordinates)
        }

        /**
         * Drawing straight onto the screen takes an ARGB colour, unlike `Component.withColor`,
         * which takes plain RGB. A colour written without its alpha here is invisible.
         */
        private const val DETAILS = 0xFFE0E0E0.toInt()
    }
}
