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
 * This mod's pictures on JourneyMap's maps: BlueMap's terrain ([JourneyMapTerrain]) and the biome
 * tint ([JourneyMapBiomes]), as Xaero's maps show both; and what the minimap shows ([area]).
 *
 * JourneyMap draws a map's tiles, then its grid, then its outlines into a picture of its own, which
 * on the minimap it then cuts to shape and turns; picture overlays it draws afterwards straight onto
 * the screen, uncut, unturned, and over the grid. So these are drawn into that picture ([draw], from
 * `GridPassMixin`, or `OutlinePassMixin` when there is no grid): over the tiles, under the grid and
 * outlines, placed as JourneyMap places its outlines. JourneyMap only draws that picture again when
 * something in it changes, so it is told whenever one of these does ([changed]).
 */
internal object JourneyMapMinimap {

    /** Each map's renderer, once it has drawn. */
    private val renderers = java.util.EnumMap<Context.UI, Renderer>(Context.UI::class.java)

    /**
     * The maps (the minimap, the full-screen map) whose pictures went in under their grid this frame,
     * so not again before their outlines. One each: a flag shared by both could skip the wrong one.
     */
    private val drawnUnderGrid: MutableSet<Renderer> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    private var failed = false

    /** False once drawing on the maps has failed, for `/mapexposer`. */
    val working: Boolean get() = !failed

    /** One of [ui]'s pictures came, went or changed: JourneyMap draws that map's picture again. */
    fun changed(ui: Context.UI) {
        try {
            renderers[ui]?.markOverlaySurfaceDirty()
        } catch (e: Exception) {
            Log.warn("Could not have JourneyMap redraw its map: {}", e.toString())
        }
    }

    fun forget() {
        renderers.clear()
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

    /** Works out [area] from [renderer]: the blocks to [MEASURE] east of you, in its pixels, give its zoom. */
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

    /**
     * While JourneyMap draws a map into its own picture, just before its grid ([beforeGrid]) or, with
     * no grid, its outlines: the terrain and then the biome tint of [renderer]'s dimension, each
     * block's pixel taken from JourneyMap's grid and moved by [xOffset], [yOffset], with [pose].
     * Render thread.
     */
    @JvmStatic
    fun draw(graphics: GuiGraphicsExtractor, pose: Matrix3x2fStack, xOffset: Double, yOffset: Double, renderer: Renderer, beforeGrid: Boolean) {
        if (beforeGrid) drawnUnderGrid.add(renderer) else if (drawnUnderGrid.remove(renderer)) return
        if (failed) return
        try {
            val ui = renderer.context ?: return
            if (ui != Context.UI.Minimap && ui != Context.UI.Fullscreen) return
            renderers[ui] = renderer
            if (ui == Context.UI.Minimap) measure(renderer)
            val dimension = renderer.uiState?.dimension?.identifier()?.toString() ?: return
            val pictures = JourneyMapTerrain.pictures(ui, dimension) + JourneyMapBiomes.pictures(ui, dimension)
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
            Log.error("Could not draw on JourneyMap's maps; BlueMap's terrain and biome tint are off there", e)
        }
    }
}
