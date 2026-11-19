package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.ShareFormat
import journeymap.api.v2.client.event.FullscreenMapEvent
import journeymap.api.v2.client.fullscreen.IFullscreen
import journeymap.api.v2.common.event.FullscreenEventRegistry
import journeymap.client.ui.fullscreen.Fullscreen
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos

/**
 * Picking an area on JourneyMap's full-screen map as on Xaero's: hold the right button and drag,
 * and the chunks it covers are picked (a quick click picks nothing), boxed on the map ([draw]), for the right-click menu's Save terrain
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
    /** How long the right button must be held, and how far in window pixels the mouse must move, to start picking an area. */
    private const val HOLD_MS = 250L
    private const val DRAG_PIXELS = 8.0
    private const val FILL = 0x30FFFFFF
    private const val LINE = 0xF0FFFFFF.toInt()

    private var start: BlockPos? = null
    private var end: BlockPos? = null
    private var holding = false
    /** Where the mouse was when the right button went down, in window pixels, and whether it has moved enough since to be a drag. */
    private var pressedAt = 0.0 to 0.0
    private var pressedTime = 0L
    private var moved = false
    /** True while the held-back press is handed to JourneyMap, so it is let through. */
    private var replaying = false
    private var picked: ShareFormat.Area? = null
    /** The dimension the map showed when the area was picked; the box shows only there. */
    private var pickedIn: String? = null

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

    /** Lets go of the pick: on leaving a world or opening the map again. */
    fun forget() {
        holding = false
        picked = null
    }

    private fun pressed(event: FullscreenMapEvent.ClickEvent) {
        if (event.stage != FullscreenMapEvent.Stage.PRE || event.button != RIGHT || replaying) return
        event.cancel()
        start = event.location
        end = event.location
        holding = true
        moved = false
        pickedIn = JourneyMapAdapter.shownDimension()
        val mouse = Minecraft.getInstance().mouseHandler
        pressedAt = mouse.xpos() to mouse.ypos()
        pressedTime = System.currentTimeMillis()
        picked = null
    }

    private fun dragged(map: Fullscreen) {
        // A box comes from holding the right button and moving: a click, even with the hand moving a
        // little, is only a click.
        if (!moved) {
            val mouse = Minecraft.getInstance().mouseHandler
            val (x, y) = pressedAt
            moved = System.currentTimeMillis() - pressedTime >= HOLD_MS && Math.hypot(mouse.xpos() - x, mouse.ypos() - y) >= DRAG_PIXELS
            if (!moved) return
        }
        end = map.blockAtMouse ?: return
    }

    private fun released(map: Screen, event: MouseButtonEvent) {
        if (event.button() != RIGHT || !holding) return
        holding = false
        picked = current()?.takeIf { moved && (it.width > 1 || it.height > 1) }
        picked?.let { Log.info("Picked {} × {} chunks on JourneyMap's map", it.width, it.height) }
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

    /**
     * Draws the box over the finished map (from [JourneyMapMenus]' render): while dragging, and the
     * picked area after, in the dimension it was picked in. Drawn here rather than as one of
     * JourneyMap's polygon overlays, which its map pictures (BlueMap's terrain among them) cover
     * whatever their order. Placed by the map's live centre and zoom, as the terrain is.
     */
    fun draw(map: IFullscreen, graphics: GuiGraphicsExtractor) {
        val area = (if (holding && moved) current() else picked) ?: return
        if (area.width <= 1 && area.height <= 1) return
        val state = map.uiState ?: return
        if (pickedIn != null && state.dimension?.identifier()?.toString() != pickedIn) return
        val window = Minecraft.getInstance().window
        val perBlock = JourneyMapMenus.pixelsPerBlock(state) / window.guiScale
        if (perBlock <= 0) return
        val centreX = map.getCenterBlockX(false)
        val centreZ = map.getCenterBlockZ(false)
        fun x(block: Int) = (map.screen.width / 2.0 + (block - centreX) * perBlock).toInt()
        fun y(block: Int) = (map.screen.height / 2.0 + (block - centreZ) * perBlock).toInt()
        val left = x(area.left * 16)
        val top = y(area.top * 16)
        val right = maxOf(x((area.right + 1) * 16), left + 1)
        val bottom = maxOf(y((area.bottom + 1) * 16), top + 1)
        graphics.fill(left, top, right, bottom, FILL)
        graphics.fill(left, top, right, top + 1, LINE)
        graphics.fill(left, bottom - 1, right, bottom, LINE)
        graphics.fill(left, top, left + 1, bottom, LINE)
        graphics.fill(right - 1, top, right, bottom, LINE)
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.error("JourneyMap area picking failed", e)
        }
    }
}
