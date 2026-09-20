package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Locking on to a player, and the two questions everything that draws players has to ask: does the
 * game have them loaded, and can you actually see them from where you are standing.
 *
 * A locked player's pin keeps following them on both maps, and [PlayerBeacon] puts their head over
 * them in the world — but only while you cannot see the player themselves, so the head never sits
 * on top of someone standing in front of you.
 */
object LockedPlayers {

    fun isLocked(uuid: UUID): Boolean = Session.current?.locks?.isLocked(uuid) == true

    fun count(): Int = Session.current?.locks?.size ?: 0

    /** Locks or unlocks [player], and says which on the action bar. */
    fun toggle(player: Markers.Player): Boolean = set(player, !isLocked(player.uuid))

    fun set(player: Markers.Player, on: Boolean): Boolean {
        val locks = Session.current?.locks
        if (locks == null) {
            MapMenus.say("This server has no BlueMap set up, so there is nobody to lock on to")
            return false
        }
        locks.set(player.uuid, on)
        MapMenus.say(
            if (on) "Locked on to ${player.label}. Their head follows them until you unlock them."
            else "Unlocked ${player.label}"
        )
        return on
    }

    /**
     * The entity for [uuid] if your game has it loaded, which is what "in render distance" means
     * here: inside it Xaero's own radar already has them, and their position is exact rather than
     * BlueMap's couple of seconds old.
     */
    fun loaded(uuid: UUID): net.minecraft.world.entity.player.Player? =
        Minecraft.getInstance().level?.getPlayerByUUID(uuid)

    /**
     * Whether you can see [entity]'s hitbox from the camera: they are loaded, not invisible, and
     * some part of them — feet, middle or eyes — has a clear line from the camera. Blocks in the
     * way, so not seen; nothing in the way, so no need to draw a marker for them.
     *
     * Only block shapes are tested, so glass counts as in the way. Erring that way shows a marker
     * that was not needed, rather than hiding one that was.
     */
    fun inSight(entity: net.minecraft.world.entity.player.Player): Boolean {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return false
        if (entity.isInvisible || entity.isSpectator) return false
        val from = minecraft.gameRenderer.mainCamera().position()
        val box = entity.boundingBox
        val middleX = (box.minX + box.maxX) / 2
        val middleZ = (box.minZ + box.maxZ) / 2
        val targets = arrayOf(
            Vec3(middleX, box.minY + (box.maxY - box.minY) * 0.1, middleZ),
            Vec3(middleX, (box.minY + box.maxY) / 2, middleZ),
            Vec3(middleX, box.maxY - 0.1, middleZ),
        )
        return targets.any { to ->
            level.clip(ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, entity)).type == HitResult.Type.MISS
        }
    }
}
