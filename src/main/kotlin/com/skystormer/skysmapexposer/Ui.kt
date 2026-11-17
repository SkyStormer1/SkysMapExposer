package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** Small things every part of the mod says or shows, whichever map is installed. */
object Ui {

    /** True while [say] is showing a line, which passes through the same chat events as the server's. */
    var saying = false
        private set

    /** A message on the action bar, which only this client sees. */
    fun say(message: String) {
        saying = true
        try {
            Minecraft.getInstance().player?.sendOverlayMessage(Component.literal(message))
        } finally {
            saying = false
        }
    }

    fun open(screen: Screen?) {
        Minecraft.getInstance().gui.setScreen(screen)
    }

    /** "Overworld", "Nether", "End", or the id without `minecraft:` for any other dimension. */
    fun dimensionName(dimension: String?): String? = when (dimension) {
        null -> null
        Config.OVERWORLD -> "Overworld"
        Config.NETHER -> "Nether"
        Config.END -> "End"
        else -> dimension.removePrefix("minecraft:")
    }
}
