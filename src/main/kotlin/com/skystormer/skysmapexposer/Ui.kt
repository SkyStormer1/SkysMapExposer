package com.skystormer.skysmapexposer

import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.floor

/** Small things every part of the mod says or shows, whichever map is installed. */
object Ui {

    /** True while [say] is showing a line, which passes through the same chat events as the server's. */
    var saying = false
        private set

    /** [text] in chat as a command's reply; 1, the command's result. */
    fun reply(context: CommandContext<FabricClientCommandSource>, text: String): Int {
        context.source.sendFeedback(Component.literal(text))
        return 1
    }

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

    /**
     * Puts a position on the clipboard, as `-350 72 200 (Overworld)` — the numbers first, the way
     * `/tp` and most chat messages want them, and the dimension after, because coordinates from a
     * map you can switch between dimensions mean nothing without it.
     *
     * Answers on the action bar, which only this client sees.
     */
    fun copy(x: Double, y: Double?, z: Double, dimension: String?) =
        copy(floor(x).toInt(), y?.let { floor(it).toInt() }, floor(z).toInt(), dimension)

    fun copy(x: Int, y: Int?, z: Int, dimension: String?) {
        val position = if (y == null) "$x $z" else "$x $y $z"
        val name = dimensionName(dimension)
        val text = if (name == null) position else "$position ($name)"
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(text)
            say("Copied $text")
        } catch (e: Throwable) {
            Log.error("Could not copy $text to the clipboard", e)
            say("Could not copy $text to the clipboard")
        }
    }
}
