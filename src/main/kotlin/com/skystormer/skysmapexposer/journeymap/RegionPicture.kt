package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.levelKey
import com.mojang.blaze3d.platform.NativeImage
import com.skystormer.skysmapexposer.Log
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.client.display.ImageOverlay
import journeymap.api.v2.client.model.MapImage
import journeymap.api.v2.common.Context
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier

/**
 * A picture over a square of the world on JourneyMap's maps (BlueMap's terrain, the biome tint):
 * registered as a texture of ours by name, as marker icons are, rather than handed to JourneyMap as
 * a picture it would keep a second copy of; and on the full-screen map, an overlay that draws it.
 * Minimap pictures have no overlay: they are drawn into the minimap's own picture
 * ([JourneyMapMinimap]). Client thread only.
 */
internal class RegionPicture private constructor(
    val texture: Identifier,
    /** The picture's side in pixels, and the blocks a pixel covers. */
    val side: Int,
    val scale: Int,
    val westX: Int,
    val northZ: Int,
    private val overlay: ImageOverlay?,
) {

    /** The blocks it covers, along each side. */
    val reach: Int get() = side * scale

    /** Takes it off the map and frees its texture. */
    fun remove(api: IClientAPI) {
        overlay?.let(api::remove)
        release()
    }

    /** Frees its texture, once JourneyMap has dropped the overlay itself (on leaving a world). */
    fun release() {
        Minecraft.getInstance().textureManager.release(texture)
    }

    companion object {
        private var next = 0

        /**
         * Puts [image] (square, a pixel [scale] blocks across) up with its north-west corner at
         * ([westX], [northZ]) of [dimension]: as an overlay drawn [order]th on the full-screen map
         * when [fullscreen], else for the minimap. Null, with [image] freed, if JourneyMap refuses it.
         */
        fun show(
            api: IClientAPI, kind: String, dimension: String, westX: Int, northZ: Int, scale: Int, image: NativeImage,
            fullscreen: Boolean, order: Int,
        ): RegionPicture? {
            val texture = Identifier.fromNamespaceAndPath("skysmapexposer", "journeymap_$kind/${next++}")
            val side = image.width
            Minecraft.getInstance().textureManager.register(texture, DynamicTexture({ "skysmapexposer $kind $westX,$northZ" }, image))
            if (!fullscreen) return RegionPicture(texture, side, scale, westX, northZ, null)
            val reach = side * scale
            val overlay = ImageOverlay(JourneyMapAdapter.MOD_ID, BlockPos(westX, 0, northZ), BlockPos(westX + reach, 0, northZ + reach), MapImage(texture, side, side))
            overlay.setDimension(levelKey(dimension))
            overlay.setDisplayOrder(order)
            overlay.setActiveUIs(Context.UI.Fullscreen)
            return try {
                api.show(overlay)
                RegionPicture(texture, side, scale, westX, northZ, overlay)
            } catch (e: Exception) {
                Minecraft.getInstance().textureManager.release(texture)
                Log.warn("JourneyMap would not show the {} picture at {}, {}: {}", kind, westX, northZ, e.toString())
                null
            }
        }
    }
}
