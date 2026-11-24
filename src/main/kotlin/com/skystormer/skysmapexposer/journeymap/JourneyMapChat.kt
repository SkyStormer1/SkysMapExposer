package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.ChatCoords
import net.minecraft.client.Minecraft

/** Positions JourneyMap made clickable in chat. */
object JourneyMapChat {

    /**
     * For `ChatWaypointMixin`: [args] of JourneyMap's chat click command (who sent it, then the
     * position as written in chat). With Ctrl held, the position is shown on the full-screen map,
     * the way the chat go-to does it for Xaero's ([ChatCoords.show]), and true is returned so that
     * JourneyMap does not also add it to its pending waypoints.
     */
    @JvmStatic
    fun ctrlClicked(args: Array<String>): Boolean {
        if (args.size < 2 || !Minecraft.getInstance().hasControlDown()) return false
        val position = ChatCoords.read(args.drop(1).joinToString(" ")) ?: return false
        ChatCoords.show(position.dimension, position.x, position.z)
        return true
    }
}
