package com.skystormer.skysmapexposer

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt

/**
 * The head of every player you have locked on to, floating over where they are, the way one of
 * Xaero's waypoints floats over its spot — except that this one follows them.
 *
 * It shows only while you are looking their way and cannot already see them: once the player is
 * loaded, lit and unobscured in front of you ([LockedPlayers.inSight]), the head goes, because you
 * are looking at the real thing. Behind a hill, through a wall, or far past your render distance,
 * the head stays, from BlueMap's position when your game has none.
 *
 * Drawn as a HUD element rather than in the world, so it is always the same size on screen and
 * never hidden by terrain, and so no world render pipeline has to be touched.
 */
object PlayerBeacon : HudElement {

    /** How far above a player's feet the head sits, in blocks: just clear of their own head. */
    private const val HEIGHT = 2.6

    /** The head's size on screen at 100%, in scaled pixels. */
    private const val SIZE = 16

    /** Kept off the very edge of the screen, so a half-drawn head never hangs off it. */
    private const val MARGIN = 4

    private var complained = false

    override fun extractRenderState(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
        try {
            draw(graphics, delta.getGameTimeDeltaPartialTick(true))
        } catch (e: Throwable) {
            // Every frame comes through here, so this is said once and then left alone.
            if (!complained) {
                complained = true
                Log.error("Could not draw the locked players' heads; they are off for the rest of this game", e)
            }
        }
    }

    private fun draw(graphics: GuiGraphicsExtractor, partialTick: Float) {
        if (complained || !Config.showPlayers) return
        val minecraft = Minecraft.getInstance()
        if (minecraft.gui.hud.isHidden) return
        val session = Session.current ?: return
        if (session.locks.size == 0) return
        val me = minecraft.player ?: return
        val level = minecraft.level ?: return
        val map = session.server.markersFor(level.dimension().identifier().toString()) ?: return
        val font = minecraft.font
        val camera = minecraft.gameRenderer.mainCamera()
        val eye = camera.position()
        val forward = camera.forwardVector()
        val size = (SIZE * Config.playerHeadScale).roundToInt().coerceAtLeast(4)
        val half = size / 2

        for (player in session.markers.players(map)) {
            if (!session.locks.isLocked(player.uuid)) continue
            // Their own client's position while the game has them, BlueMap's when it does not.
            val entity = LockedPlayers.loaded(player.uuid)
            if (entity != null && LockedPlayers.inSight(entity)) continue
            val feet = entity?.getPosition(partialTick) ?: Vec3(player.x, player.y, player.z)
            val anchor = feet.add(0.0, HEIGHT, 0.0)

            // Someone behind you has to be dropped before projecting at all. The perspective
            // divide is by a negative number back there, which flips the point through the middle
            // of the screen: they would be drawn facing the opposite way, as a second copy of a
            // player who is really behind you. Vanilla's own waypoints test this with z > 1, which
            // only holds for a forward-Z projection; Minecraft's depth is reversed (near is 1, far
            // is 0), so behind the camera z comes out negative and that test never fires. Whether
            // they are in front of the camera at all is the same question and does not care.
            val toward = anchor.subtract(eye)
            if (toward.x * forward.x() + toward.y * forward.y() + toward.z * forward.z() <= 0.0) continue

            val screen = minecraft.gameRenderer.projectPointToScreen(anchor)
            val x = ((screen.x * 0.5 + 0.5) * graphics.guiWidth()).roundToInt()
            val y = ((0.5 - screen.y * 0.5) * graphics.guiHeight()).roundToInt()
            if (x < half + MARGIN || x > graphics.guiWidth() - half - MARGIN) continue
            if (y < half + MARGIN + font.lineHeight || y > graphics.guiHeight() - half - MARGIN - font.lineHeight) continue

            val distance = me.position().distanceTo(feet).roundToInt()
            val label = "${player.label} ${distance}m"
            val labelWidth = font.width(label)
            graphics.fill(x - labelWidth / 2 - 2, y - half - font.lineHeight - 2, x + labelWidth / 2 + 2, y - half - 1, BACKDROP)
            graphics.centeredText(font, label, x, y - half - font.lineHeight - 1, LABEL)
            graphics.fill(x - half - 1, y - half - 1, x + half + 1, y + half + 1, BACKDROP)
            PinDrawing.face(graphics, player.uuid, x - half, y - half, size)
        }
    }

    private const val BACKDROP = 0xA0000000.toInt()
    private const val LABEL = 0xFFFFFFFF.toInt()
}
