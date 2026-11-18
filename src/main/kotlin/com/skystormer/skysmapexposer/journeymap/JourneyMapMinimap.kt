package com.skystormer.skysmapexposer.journeymap

import com.mojang.blaze3d.systems.RenderSystem
import com.skystormer.skysmapexposer.Log
import journeymap.api.v2.common.Context
import journeymap.client.render.JmRenderRouter
import journeymap.client.render.map.Renderer
//? if >=26.3 {
import com.mojang.renderpearl.api.textures.FilterMode
//?} else {
/*import com.mojang.blaze3d.textures.FilterMode
*///?}
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.core.BlockPos
import org.joml.Matrix3x2f
import org.joml.Matrix3x2fStack

/**
 * This mod's pictures on JourneyMap's minimap: BlueMap's terrain ([JourneyMapTerrain]) and the biome
 * tint ([JourneyMapBiomes]), as Xaero's minimap shows both.
 *
 * JourneyMap draws the minimap's tiles and outlines into a picture of its own, then cuts it to the
 * minimap's shape and turns it; picture overlays it draws afterwards straight onto the screen, uncut
 * and unturned. So these are drawn into that picture ([draw], from `OutlinePassMixin`), placed as
 * JourneyMap places its outlines. JourneyMap only draws that picture again when something in it
 * changes, so it is told whenever one of these does ([changed]).
 */
internal object JourneyMapMinimap {

    /** The minimap's renderer, once it has drawn. */
    private var renderer: Renderer? = null

    private var failed = false

    /** False once drawing on it has failed, for `/mapexposer`. */
    val working: Boolean get() = !failed

    /** One of the minimap's pictures came, went or changed: JourneyMap draws its picture again. */
    fun changed() {
        try {
            renderer?.markOverlaySurfaceDirty()
        } catch (e: Exception) {
            Log.warn("Could not have JourneyMap redraw its minimap: {}", e.toString())
        }
    }

    fun forget() {
        renderer = null
        area = null
    }

    /**
     * The blocks the minimap shows, as left, top, right, bottom, from when it last drew; null until it
     * has. JourneyMap's own account of the minimap's area (its `UIState`) is not kept up to date, so
     * this is worked out from its renderer each time it draws: around you, at its zoom, wide enough
     * for the corners of a turning minimap.
     */
    var area: DoubleArray? = null
        private set

    /**
     * While JourneyMap draws a map into its own picture, just before its outlines: on the minimap,
     * the terrain and then the biome tint of [renderer]'s dimension, each block's pixel taken from
     * JourneyMap's grid and moved by [xOffset], [yOffset], with [pose]. Render thread.
     */
    private fun measure(renderer: Renderer) {
        val player = Minecraft.getInstance().player ?: return
        val here = renderer.getBlockPixelInGridExact(player.x, player.z)
        val east = renderer.getBlockPixelInGridExact(player.x + MEASURE, player.z)
        val pixelsPerBlock = (east.x - here.x) / MEASURE
        if (pixelsPerBlock <= 0) return
        val half = Math.hypot(renderer.width.toDouble(), renderer.height.toDouble()) / 2 / pixelsPerBlock
        area = doubleArrayOf(player.x - half, player.z - half, player.x + half, player.z + half)
    }

    private const val MEASURE = 64.0

    @JvmStatic
    fun draw(graphics: GuiGraphicsExtractor, pose: Matrix3x2fStack, xOffset: Double, yOffset: Double, renderer: Renderer) {
        if (failed) return
        try {
            if (renderer.context != Context.UI.Minimap) return
            this.renderer = renderer
            measure(renderer)
            val dimension = renderer.uiState?.dimension?.identifier()?.toString() ?: return
            val pictures = JourneyMapTerrain.minimapPictures(dimension) + JourneyMapBiomes.minimapPictures(dimension)
            if (pictures.isEmpty()) return
            val textures = Minecraft.getInstance().textureManager
            val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true)
            for (picture in pictures) {
                val view = textures.getTexture(picture.texture).textureView
                val northWest = renderer.getBlockPixelInGridExact(BlockPos(picture.westX, 0, picture.northZ))
                val southEast = renderer.getBlockPixelInGridExact(BlockPos(picture.westX + picture.reach, 0, picture.northZ + picture.reach))
                JmRenderRouter.addGuiElement(graphics, BlitRenderState(
                    RenderPipelines.GUI_TEXTURED, TextureSetup.singleTexture(view, sampler), Matrix3x2f(pose),
                    Math.round(northWest.x + xOffset).toInt(), Math.round(northWest.y + yOffset).toInt(),
                    Math.round(southEast.x + xOffset).toInt(), Math.round(southEast.y + yOffset).toInt(),
                    0f, 1f, 0f, 1f, -1, null,
                ))
            }
        } catch (e: Throwable) {
            // Something of JourneyMap's has changed shape: stop trying, once, rather than every frame.
            failed = true
            Log.error("Could not draw on JourneyMap's minimap; BlueMap's terrain and biome tint are off there", e)
        }
    }
}
