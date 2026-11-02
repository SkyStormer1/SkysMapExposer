package com.skystormer.skysmapexposer

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What floats over a spot in the world, the way one of Xaero's waypoints floats over its own:
 *
 * - The head of every player you have locked on to, following them. It shows only while you are
 *   looking their way and cannot already see them: once the player is loaded, lit and unobscured
 *   in front of you ([LockedPlayers.inSight]), the head goes, because you are looking at the real
 *   thing. Behind a hill, through a wall, or far past your render distance, the head stays, from
 *   BlueMap's position when your game has none.
 * - A flashing beam and the sender's head over each ping ([PlayerPings]).
 *
 * Drawn as a HUD element rather than in the world, so a head is always the same size on screen, a
 * beam shows however far off it is, neither is hidden by terrain, and no world render pipeline has
 * to be touched. It is the first thing on the HUD ([DRAWN_BEFORE]), so the minimap, hotbar, health
 * and XP bars stay on top of it.
 */
object PlayerBeacon : HudElement {

    /** How far above a player's feet the head sits, in blocks: just clear of their own head. */
    private const val HEIGHT = 2.6

    /** The head's size on screen at 100%, in scaled pixels at GUI scale 2. */
    private const val SIZE = 16

    /** The GUI scale [SIZE] is meant for; at bigger scales the head and its label are drawn smaller to match. */
    private const val BASE_GUI_SCALE = 2.0

    /** Kept off the very edge of the screen, so a half-drawn head never hangs off it. */
    private const val MARGIN = 4

    /** How tall a beam rises for each block it is away, so a far one still reaches well up the screen. */
    private const val BEAM_RISE = 3.0

    /** How close in front of the camera a beam is cut off: the projection is meaningless at or behind it. */
    private const val BEAM_NEAR = 0.2

    /** The beam's layers from outside in: half its width in pixels, and how much of its brightness. */
    private val BEAM_LAYERS = listOf(3 to 0.25f, 2 to 0.45f, 1 to 1f)

    /**
     * The vanilla HUD element the heads are drawn just before: the first of them, so every part of
     * the HUD, and Xaero's minimap (attached after it), is drawn over a head rather than under it.
     */
    val DRAWN_BEFORE: net.minecraft.resources.Identifier = net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.MISC_OVERLAYS

    private var complained = false

    override fun extractRenderState(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        try {
            draw(graphics, delta.getGameTimeDeltaPartialTick(true))
        } catch (e: Throwable) {
            // Every frame comes through here, so this is said once and then left alone.
            if (!complained) {
                complained = true
                Log.error("Could not draw the locked players' heads and pings; they are off for the rest of this game", e)
            }
        }
    }

    private fun draw(graphics: GuiGraphicsExtractor, partialTick: Float) {
        if (complained) return
        val minecraft = Minecraft.getInstance()
        if (minecraft.gui.hud.isHidden) return
        val level = minecraft.level ?: return
        val dimension = level.dimension().identifier().toString()

        for (ping in PlayerPings.pinsIn(dimension)) {
            beam(graphics, ping)
            head(graphics, ping.uuid, ping.label, Vec3(ping.x, ping.y, ping.z))
        }

        if (!Config.showPlayers) return
        val session = Session.current ?: return
        if (session.locks.size == 0) return
        val map = session.server.markersFor(dimension) ?: return
        for (player in session.markers.players(map)) {
            if (!session.locks.isLocked(player.uuid)) continue
            // Their own client's position while the game has them, BlueMap's when it does not.
            val entity = LockedPlayers.loaded(player.uuid)
            if (entity != null && LockedPlayers.inSight(entity)) continue
            head(graphics, player.uuid, player.label, entity?.getPosition(partialTick) ?: Vec3(player.x, player.y, player.z))
        }
    }

    /** How far in front of the camera [point] is; negative behind it. */
    private fun depth(point: Vec3): Double {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera()
        val eye = camera.position()
        val forward = camera.forwardVector()
        return (point.x - eye.x) * forward.x() + (point.y - eye.y) * forward.y() + (point.z - eye.z) * forward.z()
    }

    /** Where [point], in front of the camera, lands on the HUD. */
    private fun project(graphics: GuiGraphicsExtractor, point: Vec3): Pair<Float, Float> {
        val screen = Minecraft.getInstance().gameRenderer.projectPointToScreen(point)
        return ((screen.x * 0.5 + 0.5) * graphics.guiWidth()).toFloat() to ((0.5 - screen.y * 0.5) * graphics.guiHeight()).toFloat()
    }

    /**
     * [ping]'s beam, a column from the world's floor to high in the sky. A vertical line leans on
     * screen when you look up or down, so it is drawn along its projected ends, cut off where it
     * passes behind the camera.
     */
    private fun beam(graphics: GuiGraphicsExtractor, ping: Markers.Ping) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return
        val eye = minecraft.gameRenderer.mainCamera().position()
        val away = hypot(ping.x - eye.x, ping.z - eye.z)
        val low = Vec3(ping.x, level.minY.toDouble(), ping.z)
        val high = Vec3(ping.x, max(level.maxY.toDouble(), ping.y + away * BEAM_RISE), ping.z)
        val lowDepth = depth(low)
        val highDepth = depth(high)
        if (lowDepth <= BEAM_NEAR && highDepth <= BEAM_NEAR) return
        fun cut(point: Vec3, pointDepth: Double) =
            if (pointDepth >= BEAM_NEAR) point
            else low.lerp(high, (BEAM_NEAR - lowDepth) / (highDepth - lowDepth))
        val (startX, startY) = project(graphics, cut(low, lowDepth))
        val (endX, endY) = project(graphics, cut(high, highDepth))

        val lit = PlayerPings.brightness(ping)
        val length = hypot(endX - startX, endY - startY).toInt()
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(startX, startY)
        pose.rotate(atan2(endY - startY, endX - startX))
        for ((half, share) in BEAM_LAYERS) {
            graphics.fill(0, -half, length, half, PinDrawing.beamColour(lit * share))
        }
        pose.popMatrix()
    }

    /** A head and its label over a player or ping at [feet], if that is on screen. */
    private fun head(graphics: GuiGraphicsExtractor, uuid: UUID, name: String, feet: Vec3) {
        val minecraft = Minecraft.getInstance()
        val me = minecraft.player ?: return
        val font = minecraft.font
        // The HUD is in scaled pixels, so at GUI scale 3 or 4 everything here would grow with it.
        // Head and label are drawn in one scale instead: the same size on screen whatever the GUI
        // scale, and the label grows and shrinks with the head.
        val scale = (Config.playerHeadScale * BASE_GUI_SCALE / max(1.0, minecraft.window.guiScale.toDouble())).toFloat()
        val half = SIZE / 2
        val anchor = feet.add(0.0, HEIGHT, 0.0)

        // Someone behind you has to be dropped before projecting at all. The perspective divide is
        // by a negative number back there, which flips the point through the middle of the screen:
        // they would be drawn facing the opposite way, as a second copy of a player who is really
        // behind you. Vanilla's own waypoints test this with z > 1, which only holds for a
        // forward-Z projection; Minecraft's depth is reversed (near is 1, far is 0), so behind the
        // camera z comes out negative and that test never fires. Whether they are in front of the
        // camera at all is the same question and does not care.
        if (depth(anchor) <= 0.0) return

        val (screenX, screenY) = project(graphics, anchor)
        val reach = (half + font.lineHeight) * scale
        if (screenX < half * scale + MARGIN || screenX > graphics.guiWidth() - half * scale - MARGIN) return
        if (screenY < reach + MARGIN || screenY > graphics.guiHeight() - reach - MARGIN) return

        val distance = me.position().distanceTo(feet).roundToInt()
        val label = "$name ${distance}m"
        val labelWidth = font.width(label)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(screenX, screenY)
        pose.scale(scale, scale)
        graphics.fill(-labelWidth / 2 - 2, -half - font.lineHeight - 2, labelWidth / 2 + 2, -half - 1, BACKDROP)
        graphics.centeredText(font, label, 0, -half - font.lineHeight - 1, LABEL)
        graphics.fill(-half - 1, -half - 1, half + 1, half + 1, BACKDROP)
        PinDrawing.face(graphics, uuid, -half, -half, SIZE)
        pose.popMatrix()
    }

    private const val BACKDROP = 0xA0000000.toInt()
    private const val LABEL = 0xFFFFFFFF.toInt()
}
