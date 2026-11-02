package com.skystormer.skysmapexposer.gui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.network.chat.Component
import java.util.WeakHashMap

/**
 * Tooltips for widgets (`.tooltip(...)`, `setTooltip(...)`) that behave like [DockPanel.tooltip]:
 * wrapped to fit, kept wholly on screen, cut with "…" if they still cannot fit. Vanilla only keeps
 * a tooltip off the right and bottom edges, so at a big GUI scale a long one ran off the top or
 * out of the side.
 *
 * Vanilla's tooltip can't be subclassed, so each one made here is remembered, and
 * `WidgetTooltipHolderMixin` draws the remembered ones through [draw] instead of vanilla's
 * placement. Every tooltip in the mod goes through this or [DockPanel.tooltip]; a test checks that
 * nothing calls vanilla's directly. If the mixin does not apply, these are vanilla tooltips.
 */
object SafeTooltip {

    // Identity keys: Tooltip has no equals of its own. Weak, as widgets are rebuilt with the screen.
    private val texts = WeakHashMap<Tooltip, String>()

    fun of(text: String): Tooltip = Tooltip.create(Component.literal(text)).also { texts[it] = text }

    /** The text of a tooltip made by [of], or null for anyone else's. */
    @JvmStatic
    fun textOf(tooltip: Tooltip?): String? = tooltip?.let { texts[it] }

    /** Shows [text] near ([x], [y]), which is the mouse, or for a widget reached by keyboard, just below it. */
    @JvmStatic
    fun draw(graphics: GuiGraphicsExtractor, text: String, x: Int, y: Int) = DockPanel.tooltip(graphics, text, x, y)
}
