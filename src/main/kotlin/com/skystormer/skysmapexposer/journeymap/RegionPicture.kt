package com.skystormer.skysmapexposer.journeymap

import com.mojang.blaze3d.platform.NativeImage
import journeymap.api.v2.common.Context
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/**
 * A picture over a square of the world on one of JourneyMap's maps (BlueMap's terrain, the biome
 * tint): registered as a texture of ours by name, as marker icons are, and drawn into the map's own
 * picture with its tiles, under its grid and outlines ([JourneyMapMinimap.draw]), on the full-screen
 * map as on the minimap. JourneyMap's picture overlays are drawn after its grid, over it, so the grid
 * vanished wherever one was. Client thread only.
 */
internal class RegionPicture private constructor(
    val texture: Identifier,
    /** The picture's side in pixels, and the blocks a pixel covers. */
    val side: Int,
    val scale: Int,
    val westX: Int,
    val northZ: Int,
    private val ui: Context.UI,
) {

    /** The blocks it covers, along each side. */
    val reach: Int get() = side * scale

    /** Whether it is drawn; one kept aside ([hide]) is not. */
    var shown: Boolean = false
        private set

    /** Takes it off the map and frees its texture. */
    fun remove() {
        hide()
        release()
    }

    /** Takes it off the map but keeps its texture, to be put back by [unhide] without being made again. */
    fun hide() {
        if (!shown) return
        shown = false
        JourneyMapMinimap.changed(ui)
    }

    /** Puts a picture kept by [hide], or made hidden, back on the map. */
    fun unhide() {
        if (shown) return
        shown = true
        JourneyMapMinimap.changed(ui)
    }

    /** Frees its texture. */
    fun release() {
        Minecraft.getInstance().textureManager.release(texture)
    }

    companion object {
        private var next = 0

        /**
         * Puts [image] (square, a pixel [scale] blocks across) up with its north-west corner at
         * ([westX], [northZ]) on [ui]'s map; made but kept off the map unless [visible].
         */
        fun show(kind: String, westX: Int, northZ: Int, scale: Int, image: NativeImage, ui: Context.UI, visible: Boolean = true): RegionPicture {
            val texture = Identifier.fromNamespaceAndPath("skysmapexposer", "journeymap_$kind/${next++}")
            Minecraft.getInstance().textureManager.register(texture, DynamicTexture({ "skysmapexposer $kind $westX,$northZ" }, image))
            val picture = RegionPicture(texture, image.width, scale, westX, northZ, ui)
            if (visible) picture.unhide()
            return picture
        }
    }
}
