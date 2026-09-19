package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import xaero.common.HudMod
import xaero.common.graphics.CustomRenderTypes
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider
import xaero.hud.minimap.element.render.MinimapElementGraphics
import xaero.hud.minimap.element.render.MinimapElementReader
import xaero.hud.minimap.element.render.MinimapElementRenderInfo
import xaero.hud.minimap.element.render.MinimapElementRenderLocation
import xaero.hud.minimap.element.render.MinimapElementRenderProvider
import xaero.hud.minimap.element.render.MinimapElementRenderer
import xaero.lib.client.graphics.XaeroBufferProvider

/**
 * BlueMap's markers on Xaero's minimap, through the minimap's own element system, drawn over the
 * minimap the way its waypoints are. Only markers within [Config.minimapShrinkChunks] are shown,
 * smaller the further away they are, so a server's hundreds of markers do not crowd the minimap's
 * edge. Players are left out: the minimap's radar already shows the ones near you.
 *
 * Only touched when Xaero's Minimap is installed.
 */
object MinimapMarkerElements {

    class Context {
        var pins: List<Markers.Pin> = emptyList()
        var next = 0
        var iconRenderer: MultiTextureRenderTypeRenderer? = null
    }

    class Provider : MinimapElementRenderProvider<Markers.Pin, Context>() {
        override fun begin(location: MinimapElementRenderLocation, context: Context) {
            context.pins = if (Config.showMarkers) {
                MarkerElements.pinsForWorldMap().filter { it is Markers.Point && inRange(it) }
            } else {
                emptyList()
            }
            context.next = 0
        }

        override fun hasNext(location: MinimapElementRenderLocation, context: Context): Boolean = context.next < context.pins.size

        override fun getNext(location: MinimapElementRenderLocation, context: Context): Markers.Pin = context.pins[context.next++]

        override fun end(location: MinimapElementRenderLocation, context: Context) {
            context.pins = emptyList()
        }
    }

    class Reader : MinimapElementReader<Markers.Pin, Context>() {
        override fun isHidden(pin: Markers.Pin, context: Context): Boolean = false
        override fun getRenderX(pin: Markers.Pin, context: Context, partialTicks: Float): Double = pin.x
        override fun getRenderY(pin: Markers.Pin, context: Context, partialTicks: Float): Double = pin.y
        override fun getRenderZ(pin: Markers.Pin, context: Context, partialTicks: Float): Double = pin.z
        override fun getInteractionBoxLeft(pin: Markers.Pin, context: Context, partialTicks: Float): Int = -HALF
        override fun getInteractionBoxRight(pin: Markers.Pin, context: Context, partialTicks: Float): Int = HALF
        override fun getInteractionBoxTop(pin: Markers.Pin, context: Context, partialTicks: Float): Int = -HALF
        override fun getInteractionBoxBottom(pin: Markers.Pin, context: Context, partialTicks: Float): Int = HALF
        override fun getRenderBoxLeft(pin: Markers.Pin, context: Context, partialTicks: Float): Int = -HALF
        override fun getRenderBoxRight(pin: Markers.Pin, context: Context, partialTicks: Float): Int = HALF
        override fun getRenderBoxTop(pin: Markers.Pin, context: Context, partialTicks: Float): Int = -HALF
        override fun getRenderBoxBottom(pin: Markers.Pin, context: Context, partialTicks: Float): Int = HALF
        override fun getLeftSideLength(pin: Markers.Pin, minecraft: Minecraft): Int = minecraft.font.width(pin.label) + 9
        override fun getMenuName(pin: Markers.Pin): String = pin.label
        override fun getFilterName(pin: Markers.Pin): String = pin.label
        override fun getMenuTextFillLeftPadding(pin: Markers.Pin): Int = 0
        override fun getRightClickTitleBackgroundColor(pin: Markers.Pin): Int = 0xFF2A4A6A.toInt()
        override fun shouldScaleBoxWithOptionalScale(): Boolean = true
    }

    class Renderer(reader: Reader, provider: Provider, context: Context) :
        MinimapElementRenderer<Markers.Pin, Context>(reader, provider, context) {

        override fun shouldRender(location: MinimapElementRenderLocation): Boolean =
            Config.showMarkers && (location == MinimapElementRenderLocation.OVER_MINIMAP || location == MinimapElementRenderLocation.IN_MINIMAP)

        override fun preRender(info: MinimapElementRenderInfo, buffers: XaeroBufferProvider, renderers: MultiTextureRenderTypeRendererProvider) {
            context.iconRenderer = renderers.getRenderer(CustomRenderTypes.GUI_NEAREST)
        }

        override fun postRender(info: MinimapElementRenderInfo, buffers: XaeroBufferProvider, renderers: MultiTextureRenderTypeRendererProvider) {
            context.iconRenderer?.let(renderers::draw)
            context.iconRenderer = null
            buffers.endBatch()
        }

        override fun renderElement(
            pin: Markers.Pin, highlighted: Boolean, outOfBounds: Boolean, depth: Double, scale: Float,
            partialX: Double, partialY: Double, info: MinimapElementRenderInfo, graphics: MinimapElementGraphics, buffers: XaeroBufferProvider,
        ): Boolean {
            val pose = graphics.pose()
            pose.pushPose()
            pose.translate(partialX, partialY, 0.0)
            val size = scaleFor(pin)
            pose.scale(scale * size, scale * size, 1f)
            val icon = MarkerElements.iconFor(pin)
            val renderer = context.iconRenderer
            if (icon != null && renderer != null) {
                PinDrawing.quad(renderer.begin(icon.view), pose.last().pose(), icon, 12f)
            } else {
                graphics.fill(-HALF + 2, -HALF + 2, HALF - 2, HALF - 2, 0xFF000000.toInt())
                graphics.fill(-HALF + 3, -HALF + 3, HALF - 3, HALF - 3, MarkerElements.dotColour(pin))
            }
            pose.popPose()
            return true
        }
    }

    /** How far from you, in blocks, a marker is. */
    private fun distanceTo(pin: Markers.Pin): Double? {
        val player = Minecraft.getInstance().player ?: return null
        return Math.hypot(pin.x - player.x, pin.z - player.z)
    }

    /** Whether a marker is close enough to show on the minimap at all: within [Config.minimapShrinkChunks]. */
    fun inRange(pin: Markers.Pin): Boolean {
        val distance = distanceTo(pin) ?: return true
        return distance <= Config.minimapShrinkChunks * 16.0
    }

    /**
     * How big a marker is drawn on the minimap, inside it or pinned to its edge alike: largest
     * ([NEAREST]) when you are standing on it, then only smaller with distance, down to [FARTHEST]
     * at [Config.minimapShrinkChunks]. Beyond that it is not shown ([inRange]).
     */
    fun scaleFor(pin: Markers.Pin): Float {
        val distance = distanceTo(pin) ?: return NEAREST
        val t = (distance / (Config.minimapShrinkChunks * 16.0)).coerceIn(0.0, 1.0).toFloat()
        return NEAREST + t * (FARTHEST - NEAREST)
    }

    /** A marker's size when you are standing on it, relative to the world map's. */
    const val NEAREST = 0.5f

    /** A marker's size at the edge of its range, relative to the world map's. */
    const val FARTHEST = 0.2f

    fun register(): Boolean {
        val handler = HudMod.INSTANCE?.minimap?.overMapRendererHandler ?: return false
        handler.add(Renderer(Reader(), Provider(), Context()))
        return true
    }

    private const val HALF = MarkerElements.HALF
}
