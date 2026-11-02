package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * The hooks into Xaero's drawing run every frame, the minimap's all through normal play, so an
 * error in one would crash the game, possibly straight after every join. Each hook catches it and
 * hands it here: the feature is switched off until you rejoin, the full error goes to the log once,
 * and you are told once in chat. Other features keep going.
 */
object HookErrors {

    const val WORLD_MAP = "world map overlay"
    const val MINIMAP = "minimap overlay"
    const val OTHER_DIMENSION = "other dimension's coordinates"

    private val off = LinkedHashSet<String>()

    @JvmStatic
    fun isOff(feature: String): Boolean = feature in off

    @JvmStatic
    fun failed(feature: String, e: Throwable) {
        if (!off.add(feature)) return
        Log.error("The $feature hit an error and is off until you rejoin", e)
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal("§7Sky's Map Exposer: the $feature hit an error and is off until you rejoin. The log says why.")
        )
    }

    /** What is switched off, for `/mapexposer`; empty when nothing is. */
    fun switchedOff(): List<String> = off.toList()

    /** On leaving a world: a rejoin gives everything another go. */
    fun forget() = off.clear()
}
