package com.skystormer.skysmapexposer.gui

import com.skystormer.skysmapexposer.BlueMapDownload
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * Asks what to download from BlueMap for the dimension the world map is showing: everything, or
 * only the chunks you have never mapped. [started] is told what [BlueMapDownload.start] said, or
 * null if you cancelled.
 */
class DownloadScreen(
    private val dimension: String,
    private val started: (String?) -> Unit,
) : Screen(Component.literal("Download from BlueMap")) {

    override fun init() {
        val width = minOf(WIDTH, this.width - 32)
        val left = (this.width - width) / 2
        var y = this.height / 2 - 70

        addRenderableWidget(StringWidget(left, y, width, font.lineHeight, title, font))
        y += font.lineHeight + GAP * 2
        val text = MultiLineTextWidget(
            left, y,
            Component.literal(
                "Writes BlueMap's terrain for the $dimension into your own Xaero map. Each region is backed " +
                    "up just before it is changed. You can close this and play while it runs."
            ),
            font,
        ).setMaxWidth(width)
        addRenderableWidget(text)
        y += font.lineHeight * 4 + GAP * 2

        addRenderableWidget(
            Button.builder(Component.literal("Unexplored only")) { choose(unexploredOnly = true) }
                .bounds(left, y, width, ROW)
                .tooltip(Tooltip.create(Component.literal(
                    "Fill in only the chunks you have never mapped. Everything you have explored stays exactly as it is."
                )))
                .build()
        )
        y += ROW + GAP
        addRenderableWidget(
            Button.builder(Component.literal("Everything")) { choose(unexploredOnly = false) }
                .bounds(left, y, width, ROW)
                .tooltip(Tooltip.create(Component.literal(
                    "Fill in the chunks you have never mapped, and replace what your map has wherever BlueMap " +
                        "shows something different. Where BlueMap has nothing, your map is left alone."
                )))
                .build()
        )
        y += ROW + GAP
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL) { onClose() }.bounds(left, y, width, ROW).build())
    }

    private fun choose(unexploredOnly: Boolean) = started(BlueMapDownload.start(null, unexploredOnly))

    override fun onClose() = started(null)

    private companion object {
        const val WIDTH = 260
        const val ROW = 20
        const val GAP = 4
    }
}
