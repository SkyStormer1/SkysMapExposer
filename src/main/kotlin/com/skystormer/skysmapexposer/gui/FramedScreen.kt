package com.skystormer.skysmapexposer.gui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/**
 * A settings screen laid out like vanilla's Controls: the title in a bar along the top, the
 * settings in the middle, and **Reset to defaults** and **Done** in a bar along the bottom. When the
 * settings are taller than the space between the bars (at a big GUI scale), the middle scrolls,
 * with a bar on the right, and the title and buttons stay put.
 *
 * Sky's Map Exposer, Sky's Structure Map and Sky's Map Shapes have this same class.
 */
abstract class FramedScreen(title: Component) : Screen(title) {

    /** Adds the settings, top edge at [top], with the usual `addRenderableWidget`. */
    protected abstract fun content(top: Int)

    /** Puts every setting this screen shows back to how the mod comes. */
    protected abstract fun resetToDefaults()

    /** What **Reset to defaults** puts back, and anything it leaves alone. */
    protected open val resetTip: String = "Puts every setting here back to how the mod comes."

    private var scroll = 0
    private var contentHeight = 0
    private var draggingBar = false
    private var resetArmed = false
    private var resetButton: Button? = null
    private var fixed: List<AbstractWidget> = emptyList()
    private var scrolled: List<AbstractWidget> = emptyList()

    private fun top(): Int = HEADER
    private fun bottom(): Int = height - FOOTER

    /** How far the middle can scroll: 0 when it all fits. */
    private fun maxScroll(): Int = maxOf(0, contentHeight + PAD * 2 - (bottom() - top()))

    final override fun init() {
        // The buttons go in first, so where they overlap something scrolled under them they win.
        resetArmed = false
        val footerWidth = minOf(FOOTER_WIDTH, width - 16)
        val half = (footerWidth - GAP) / 2
        val left = width / 2 - footerWidth / 2
        val y = height - FOOTER / 2 - ROW / 2
        val reset = Button.builder(Component.literal(RESET)) { reset() }
            .bounds(left, y, half, ROW)
            .tooltip(Tooltip.create(Component.literal(resetTip)))
            .build()
        val done = Button.builder(CommonComponents.GUI_DONE) { onClose() }.bounds(left + half + GAP, y, footerWidth - half - GAP, ROW).build()
        addRenderableWidget(reset)
        addRenderableWidget(done)
        resetButton = reset
        fixed = listOf(reset, done)

        val first = top() + PAD - scroll
        content(first)
        scrolled = children().filterIsInstance<AbstractWidget>().filter { it !in fixed }
        contentHeight = (scrolled.maxOfOrNull { it.y + it.height } ?: first) - first
        val wanted = scroll.coerceIn(0, maxScroll())
        if (wanted != scroll) {
            val by = wanted - scroll
            scroll = wanted
            scrolled.forEach { it.y -= by }
        }
    }

    /** Asks first: the first click only changes the button, and clicking anywhere else calls it off. */
    private fun reset() {
        if (!resetArmed) {
            resetArmed = true
            resetButton?.message = Component.literal(RESET_AGAIN)
            return
        }
        resetToDefaults()
        rebuildWidgets()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val top = top()
        val bottom = bottom()
        // Widgets scrolled under a bar are cut off there, and cannot be hovered through it.
        val inside = mouseY in top until bottom
        graphics.enableScissor(0, top, width, bottom)
        scrolled.forEach { it.extractRenderState(graphics, mouseX, if (inside) mouseY else OUTSIDE, partialTick) }
        graphics.disableScissor()

        graphics.fill(0, top - 1, width, top, EDGE_DARK)
        graphics.fill(0, bottom, width, bottom + 1, EDGE_DARK)
        graphics.fill(0, top, width, top + 1, EDGE_LIGHT)
        graphics.fill(0, bottom - 1, width, bottom, EDGE_LIGHT)
        graphics.centeredText(font, title, width / 2, (HEADER - font.lineHeight) / 2, TITLE)
        fixed.forEach { it.extractRenderState(graphics, mouseX, mouseY, partialTick) }

        if (maxScroll() > 0) {
            val left = barLeft()
            graphics.fill(left, top + 1, left + BAR, bottom - 1, 0x40FFFFFF)
            val lit = draggingBar || (inside && mouseX >= left && mouseX < left + BAR)
            graphics.fill(left, thumbTop(), left + BAR, thumbTop() + thumbHeight(), if (lit) 0xFFFFFFFF.toInt() else 0xC0FFFFFF.toInt())
        }
    }

    private fun barLeft(): Int = width - BAR - 2

    private fun trackHeight(): Int = bottom() - top() - 2

    private fun thumbHeight(): Int =
        (trackHeight() * trackHeight() / maxOf(1, contentHeight + PAD * 2)).coerceIn(minOf(16, trackHeight()), trackHeight())

    private fun thumbTop(): Int = top() + 1 + (trackHeight() - thumbHeight()) * scroll / maxOf(1, maxScroll())

    /** Moves the scrolled widgets rather than rebuilding them, so a half-typed box keeps its text and focus. */
    private fun scrollTo(wanted: Int) {
        val to = wanted.coerceIn(0, maxScroll())
        val by = to - scroll
        if (by == 0) return
        scroll = to
        scrolled.forEach { it.y -= by }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY != 0.0 && maxScroll() > 0) {
            scrollTo(scroll - (scrollY * SCROLL_STEP).roundToInt())
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (maxScroll() > 0 && event.button() == 0 && event.x() >= barLeft() && event.x() < barLeft() + BAR &&
            event.y() >= top() && event.y() < bottom()
        ) {
            draggingBar = true
            dragBarTo(event.y())
            return true
        }
        // Over a bar, only the bar's own buttons answer, not whatever is scrolled under it.
        if (event.y() < top() || event.y() >= bottom()) {
            for (widget in fixed) if (widget.mouseClicked(event, doubleClick)) return true
            return false
        }
        if (resetArmed && fixed.none { it.isMouseOver(event.x(), event.y()) }) {
            resetArmed = false
            resetButton?.message = Component.literal(RESET)
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (draggingBar) {
            dragBarTo(event.y())
            return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (draggingBar) {
            draggingBar = false
            return true
        }
        return super.mouseReleased(event)
    }

    /** Puts the middle of the bar's thumb under the mouse. */
    private fun dragBarTo(mouseY: Double) {
        val room = maxOf(1, trackHeight() - thumbHeight())
        scrollTo(((mouseY - top() - 1 - thumbHeight() / 2.0) / room * maxScroll()).roundToInt())
    }

    companion object {
        private const val RESET = "Reset to defaults"
        private const val RESET_AGAIN = "Click again to reset"
        const val HEADER = 24
        const val FOOTER = 32
        private const val FOOTER_WIDTH = 308
        private const val PAD = 6
        private const val ROW = 20
        private const val GAP = 8
        private const val BAR = 4
        private const val SCROLL_STEP = 20
        private const val OUTSIDE = -10_000
        private const val TITLE = 0xFFFFFFFF.toInt()
        private const val EDGE_DARK = 0xFF000000.toInt()
        private const val EDGE_LIGHT = 0x40FFFFFF
    }
}
