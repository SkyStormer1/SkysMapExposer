package com.skystormer.skysmapexposer

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

/** The green chat buttons that run a command when clicked: shares, terrain files, clearing and restoring. */
object ChatButton {

    fun of(text: String, command: String, tip: String): Component =
        Component.literal(text).withStyle { style ->
            style.withColor(ChatFormatting.GREEN)
                .withClickEvent(ClickEvent.RunCommand(command))
                .withHoverEvent(HoverEvent.ShowText(Component.literal(tip)))
        }
}
