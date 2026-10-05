package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.gui.DockPanel
import com.skystormer.skysmapexposer.mixin.menu.DropDownWidgetAccess
import com.skystormer.skysmapexposer.mixin.menu.RightClickMenuAccess
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import xaero.lib.client.gui.widget.dropdown.DropDownWidget
import xaero.map.gui.IRightClickableElement
import xaero.map.gui.dropdown.rightclick.GuiRightClickMenu
import xaero.map.gui.dropdown.rightclick.RightClickOption

/**
 * Tooltips for this mod's lines of Xaero's right-click menu, which has none of its own. Called by
 * `DropDownWidgetMixin` after any of Xaero's dropdowns draws.
 */
object MenuTooltips {

    /** A right-click option that explains itself when hovered. */
    open class Option(
        name: String,
        index: Int,
        target: IRightClickableElement,
        val tooltip: List<Component>,
        private val action: (Screen) -> Unit,
    ) : RightClickOption(name, index, target) {
        override fun onAction(screen: Screen) = action(screen)
    }

    /** Set if the hooks are missing (a different Xaero), so it is only said once. */
    private var broken = false

    @JvmStatic
    fun draw(widget: Any, graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, scaledHeight: Int) {
        if (broken || widget !is GuiRightClickMenu || widget.isClosed) return
        try {
            val menu = widget as DropDownWidget
            if (mouseX < menu.xWithOffset || mouseX >= menu.xWithOffset + menu.width) return
            if (mouseY < menu.renderYWithOffset) return
            val sums = widget as DropDownWidgetAccess
            val limit = sums.`skysmapexposer$optionLimit`(scaledHeight)
            val id = sums.`skysmapexposer$hoveredId`(mouseX, mouseY, sums.`skysmapexposer$scrolling`(limit), limit)
            if (id < 0) return
            val option = (widget as RightClickMenuAccess).`skysmapexposer$options`().getOrNull(id) as? Option ?: return
            // Wrapped and kept on screen, which vanilla's tooltip is not at a big GUI scale.
            DockPanel.tooltip(graphics, option.tooltip.joinToString("\n") { it.string }, mouseX, mouseY)
        } catch (e: Throwable) {
            broken = true
            Log.warn("Could not show tooltips on Xaero's right-click menu: {}", e.toString())
        }
    }
}
