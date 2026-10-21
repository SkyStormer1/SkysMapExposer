package com.skystormer.skysmapexposer.gui

import com.mojang.blaze3d.platform.InputConstants
import com.skystormer.skysmapexposer.GuiMapFields
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import java.lang.ref.WeakReference

/**
 * Panels on Xaero's world map hear the mouse before Xaero's own buttons do.
 *
 * The screen hands a click to its widgets first to last, and panels are moved to the end so they
 * draw on top ([DockPanel.toFront]), so a Xaero button under a panel used to take the click. Here,
 * called first from `GuiMapMixin`, a click over any panel goes to the front one and no further, and
 * the drag it starts stays with it until the button is let go.
 *
 * A panel is any widget the screen has that is neither Xaero's nor the game's: this mod's bar and
 * its search box, and the panels and dropdowns of Sky's Structure Map and Sky's Map Shapes.
 */
object PanelClicks {

    private var held = WeakReference<GuiEventListener>(null)

    private fun isPanel(listener: Any): Boolean {
        val name = listener.javaClass.name
        return !name.startsWith("xaero.") && !name.startsWith("net.minecraft.")
    }

    /** The panel under the mouse, the one in front first (the last added is drawn last, on top). */
    fun panelAt(screen: Screen, mouseX: Double, mouseY: Double): GuiEventListener? =
        screen.children().asReversed().firstOrNull { isPanel(it) && it.isMouseOver(mouseX, mouseY) }

    /** True when the click was over a panel, which then had it, so Xaero must not. */
    @JvmStatic
    fun clicked(screen: Screen, event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        // An open Xaero menu has the mouse until it closes, wherever it is.
        if (menuOpen(screen)) return false
        val panel = panelAt(screen, event.x(), event.y()) ?: return false
        if (panel.mouseClicked(event, doubleClick)) {
            if (panel.shouldTakeFocusAfterInteraction()) screen.focused = panel
            if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
                screen.isDragging = true
                held = WeakReference(panel)
            }
        }
        return true
    }

    /** True when the button let go of had been pressed on a panel, which hears it instead of Xaero. */
    @JvmStatic
    fun released(screen: Screen, event: MouseButtonEvent): Boolean {
        val panel = held.get() ?: return false
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
        held = WeakReference(null)
        screen.isDragging = false
        panel.mouseReleased(event)
        return true
    }

    /**
     * Where a widget of Xaero's map screen is told the mouse is: nowhere, for Xaero's own buttons
     * while the mouse is over a panel, so they show no highlight or tooltip under it.
     */
    @JvmStatic
    fun hidesHover(screen: Screen, widget: Any, mouseX: Int, mouseY: Int): Boolean =
        !isPanel(widget) && !menuOpen(screen) && panelAt(screen, mouseX.toDouble(), mouseY.toDouble()) != null

    private fun menuOpen(screen: Screen): Boolean = try {
        GuiMapFields.openDropdown.get(screen) != null
    } catch (e: Exception) {
        false
    }
}
