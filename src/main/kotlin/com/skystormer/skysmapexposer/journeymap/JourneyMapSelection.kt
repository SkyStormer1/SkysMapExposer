package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.ShareFormat
import journeymap.api.v2.common.Context
import journeymap.api.v2.client.display.PolygonOverlay
import journeymap.api.v2.client.event.FullscreenMapEvent
import journeymap.api.v2.client.model.MapPolygon
import journeymap.api.v2.client.model.ShapeProperties
import journeymap.api.v2.common.event.FullscreenEventRegistry
import journeymap.client.ui.fullscreen.Fullscreen
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos

/**
 * Picking an area on JourneyMap's full-screen map as on Xaero's: drag with the right button held,
 * and the chunks it covers are picked, outlined on the map, for the right-click menu's Save terrain
 * file and Download BlueMap…, until the next right-click or the map closes.
 *
 * JourneyMap opens its menu the moment the right button goes down, so that press is held back here
 * and handed to JourneyMap when the button comes up, drag or not: the menu then opens where the
 * mouse is, as Xaero's does after a drag. JourneyMap pans with the left button only, and its drag
 * event is for that one, so the right button's drag is followed through the screen's own mouse
 * events, with the block under the mouse as JourneyMap works it out.
 */
internal object JourneyMapSelection {

    private const val RIGHT = 1
    /** How far, in window pixels, the mouse must move with the right button down to start picking an area. */
    private const val DRAG_PIXELS = 8.0

    private var start: BlockPos? = null
    private var end: BlockPos? = null
    private var holding = false
    /** Where the mouse was when the right button went down, in window pixels, and whether it has moved enough since to be a drag. */
    private var pressedAt = 0.0 to 0.0
    private var moved = false
    /** True while the held-back press is handed to JourneyMap, so it is let through. */
    private var replaying = false
    private var picked: ShareFormat.Area? = null
    private var outline: PolygonOverlay? = null

    /** The chunks dragged over with the right button, if the last right-click was a drag; null after a plain one. */
    val area: ShareFormat.Area? get() = picked

    fun register() {
        FullscreenEventRegistry.FULLSCREEN_MAP_CLICK_EVENT.subscribe(JourneyMapAdapter.MOD_ID) { guard { pressed(it) } }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen !is Fullscreen) return@register
            // A map opened afresh starts with nothing picked.
            forget()
            // JourneyMap's own drag event is for the left button only, so the right one is followed here.
            ScreenMouseEvents.allowMouseDrag(screen).register { map, event, _, _ ->
                if (event.button() != RIGHT || !holding) return@register true
                guard { dragged(map as Fullscreen) }
                false
            }
            ScreenMouseEvents.allowMouseRelease(screen).register { map, event ->
                try {
                    released(map, event)
                } catch (e: Throwable) {
                    Log.error("JourneyMap area picking failed", e)
                    holding = false
                }
                true
            }
        }
    }

    /** Lets go of the pick and its outline: on leaving a world or opening the map again. */
    fun forget() {
        holding = false
        picked = null
        hideOutline()
    }

    private fun pressed(event: FullscreenMapEvent.ClickEvent) {
        if (event.stage != FullscreenMapEvent.Stage.PRE || event.button != RIGHT || replaying) return
        event.cancel()
        start = event.location
        end = event.location
        holding = true
        moved = false
        val mouse = Minecraft.getInstance().mouseHandler
        pressedAt = mouse.xpos() to mouse.ypos()
        picked = null
        hideOutline()
    }

    private fun dragged(map: Fullscreen) {
        // A hand moving a little during a right-click is not a drag.
        if (!moved) {
            val mouse = Minecraft.getInstance().mouseHandler
            val (x, y) = pressedAt
            moved = Math.hypot(mouse.xpos() - x, mouse.ypos() - y) >= DRAG_PIXELS
            if (!moved) return
        }
        end = map.blockAtMouse ?: return
        val area = current() ?: return
        if (area.width > 1 || area.height > 1) showOutline(area) else hideOutline()
    }

    private fun released(map: Screen, event: MouseButtonEvent) {
        if (event.button() != RIGHT || !holding) return
        holding = false
        picked = current()?.takeIf { moved && (it.width > 1 || it.height > 1) }
        picked?.let { Log.info("Picked {} × {} chunks on JourneyMap's map", it.width, it.height) } ?: hideOutline()
        replaying = true
        try {
            map.mouseClicked(event, false)
        } finally {
            replaying = false
        }
    }

    /** The chunks between where the drag began and where it is now. */
    private fun current(): ShareFormat.Area? {
        val from = start ?: return null
        val to = end ?: return null
        val (x1, x2) = Math.floorDiv(from.x, 16) to Math.floorDiv(to.x, 16)
        val (z1, z2) = Math.floorDiv(from.z, 16) to Math.floorDiv(to.z, 16)
        return ShareFormat.Area(minOf(x1, x2), minOf(z1, z2), maxOf(x1, x2), maxOf(z1, z2))
    }

    private fun showOutline(area: ShareFormat.Area) {
        val api = JourneyMapAdapter.api ?: return
        val dimension = JourneyMapAdapter.shownDimension()?.let(JourneyMapAdapter::levelKey) ?: return
        hideOutline()
        val left = area.left * 16
        val top = area.top * 16
        val right = (area.right + 1) * 16
        val bottom = (area.bottom + 1) * 16
        val corners = listOf(BlockPos(left, 0, top), BlockPos(right, 0, top), BlockPos(right, 0, bottom), BlockPos(left, 0, bottom))
        val properties = ShapeProperties()
            .setStrokeColor(0xFFFFFF).setStrokeOpacity(0.9f).setStrokeWidth(2f)
            .setFillColor(0xFFFFFF).setFillOpacity(0.15f)
        val overlay = PolygonOverlay(JourneyMapAdapter.MOD_ID, dimension, properties, MapPolygon(corners))
        overlay.setActiveUIs(Context.UI.Fullscreen)
        try {
            api.show(overlay)
            outline = overlay
        } catch (e: Exception) {
            Log.error("Could not outline the picked area on JourneyMap's map", e)
        }
    }

    private fun hideOutline() {
        val overlay = outline ?: return
        outline = null
        runCatching { JourneyMapAdapter.api?.remove(overlay) }
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.error("JourneyMap area picking failed", e)
        }
    }
}
