package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapExposerClient
import journeymap.client.io.ThemeLoader
import journeymap.client.ui.fullscreen.Fullscreen
import journeymap.client.ui.theme.Theme
import journeymap.client.ui.theme.ThemeButton
import journeymap.client.ui.theme.ThemeToolbar
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.lang.ref.WeakReference

/**
 * The full-screen map's dimension button, as Xaero's map has one: it shows the next of the
 * Overworld, the Nether and the End ([JourneyMapDimension.showNext]), and its picture is the
 * dimension shown now, in white like JourneyMap's own icons: a grass block, a nether portal, an
 * eye of ender. It goes at the end of the top row (`FullscreenToolbarMixin`), or in JourneyMap's
 * side column if that hook could not apply.
 */
internal object JourneyMapToolbar {

    private var button = WeakReference<DimensionButton>(null)

    private val OVERWORLD = Identifier.fromNamespaceAndPath("skysmapexposer", "textures/gui/jm_overworld.png")
    private val NETHER = Identifier.fromNamespaceAndPath("skysmapexposer", "textures/gui/jm_nether.png")
    private val END = Identifier.fromNamespaceAndPath("skysmapexposer", "textures/gui/jm_end.png")
    private const val TIP = "Shows the next of the Overworld, the Nether and the End; back to yours when you leave the map"

    /** Whether the button goes at the end of the top row; if not, JourneyMap's side column takes it. */
    val inTopRow: Boolean get() = MapExposerClient.injected("journeymap.client.ui.fullscreen.Fullscreen", "dimensionButton")

    /** The picture for [dimension]: the Overworld's for any other. */
    fun iconFor(dimension: ResourceKey<Level>?): Identifier = when (dimension) {
        Level.NETHER -> NETHER
        Level.END -> END
        else -> OVERWORLD
    }

    /** The dimension the full-screen map shows: the one picked with the button, else yours. */
    private fun shown(): ResourceKey<Level>? = JourneyMapDimension.chosen ?: Minecraft.getInstance().level?.dimension()

    /** At the end of JourneyMap's button setup: the button added to the end of [options], its top row's last toolbar. */
    @JvmStatic
    fun append(map: Fullscreen, options: ThemeToolbar?) {
        options ?: return
        // JourneyMap sets its buttons up more than once on the same toolbar: one button is enough.
        // The screen's own widgets start again each time (a new GUI scale, say), so it is handed
        // to the screen again, or it would be drawn but never hear the mouse.
        button.get()?.let { old ->
            if (options.contains(old)) {
                if (old !in map.children()) map.addButtonWidget(old)
                return
            }
        }
        try {
            val made = DimensionButton(ThemeLoader.getCurrentTheme(), iconFor(shown())) { JourneyMapDimension.showNext(map) }
            made.setTooltip(TIP)
            options.add(made)
            map.addButtonWidget(made)
            button = WeakReference(made)
        } catch (e: Throwable) {
            Log.error("Could not add the dimension button to JourneyMap's top row", e)
        }
    }

    /** Each frame of the full-screen map: the picture follows the dimension shown. */
    fun refresh() {
        button.get()?.picture(iconFor(shown()))
    }

    /** JourneyMap's own themed button, with a picture that can change. */
    private class DimensionButton(theme: Theme, icon: Identifier, press: () -> Unit) :
        ThemeButton(theme, "Dimension", icon, Button.OnPress { press() }) {

        fun picture(next: Identifier) {
            if (icon == next) return
            icon = next
            textureIcon = next
        }
    }
}
