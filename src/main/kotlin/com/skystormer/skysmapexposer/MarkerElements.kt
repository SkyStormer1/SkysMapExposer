package com.skystormer.skysmapexposer

import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.BufferBuilder
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.joml.Matrix4f
import xaero.lib.client.graphics.XaeroBufferProvider
import xaero.lib.client.gui.widget.Tooltip
import xaero.map.WorldMapSession
import xaero.map.element.MapElementGraphics
import xaero.map.element.render.ElementReader
import xaero.map.element.render.ElementRenderInfo
import xaero.map.element.render.ElementRenderLocation
import xaero.map.element.render.ElementRenderProvider
import xaero.map.element.render.ElementRenderer
import xaero.map.graphics.CustomRenderTypes
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider
import xaero.map.gui.IRightClickableElement
import xaero.map.gui.dropdown.rightclick.RightClickOption

/**
 * BlueMap's markers and players as pins on Xaero's world map, through Xaero's own element system —
 * the one its waypoints and tracked players use — so they can be hovered for their name and
 * right-clicked like anything else on the map. They are drawn only on the world map (and, from
 * [MinimapMarkerElements], on the minimap), never in the world.
 */
object MarkerElements {

    /** What the world map's pins need between [ElementRenderer.preRender] and `postRender`. */
    class Context {
        var pins: List<Markers.Pin> = emptyList()
        var next = 0
        var iconRenderer: MultiTextureRenderTypeRenderer? = null
    }

    /** The pins for the dimension Xaero's world map is showing, or none. */
    fun pinsForWorldMap(): List<Markers.Pin> {
        val session = Session.current ?: return emptyList()
        val dimension = WorldMapSession.getCurrentSession()?.mapProcessor?.mapWorld?.currentDimension?.dimId
            ?.identifier()?.toString() ?: return emptyList()
        val map = session.server.markersFor(dimension) ?: return emptyList()
        val pins = ArrayList<Markers.Pin>()
        if (Config.showMarkers) session.markers.of(map)?.let { pins.addAll(it.points) }
        if (Config.showPlayers) {
            val me = Minecraft.getInstance().player?.uuid
            session.markers.players(map).filterTo(pins) { it.uuid != me }
        }
        return pins
    }

    class Provider : ElementRenderProvider<Markers.Pin, Context>() {
        override fun begin(location: ElementRenderLocation, context: Context) {
            context.pins = if (location == ElementRenderLocation.WORLD_MAP) pinsForWorldMap() else emptyList()
            context.next = 0
        }

        override fun hasNext(location: ElementRenderLocation, context: Context): Boolean = context.next < context.pins.size

        override fun getNext(location: ElementRenderLocation, context: Context): Markers.Pin = context.pins[context.next++]

        override fun end(location: ElementRenderLocation, context: Context) {
            context.pins = emptyList()
        }
    }

    class Reader : ElementReader<Markers.Pin, Context, Renderer>() {
        override fun isHidden(pin: Markers.Pin, context: Context): Boolean = false
        override fun getRenderX(pin: Markers.Pin, context: Context, partialTicks: Float): Double = pin.x
        override fun getRenderZ(pin: Markers.Pin, context: Context, partialTicks: Float): Double = pin.z
        override fun getRenderY(pin: Markers.Pin, context: Context, partialTicks: Float): Double = pin.y
        override fun hasYCoordinate(): Boolean = true
        // Xaero only hovers, labels and right-clicks elements that say they can be; the default is no.
        override fun isInteractable(location: ElementRenderLocation, pin: Markers.Pin): Boolean =
            location == ElementRenderLocation.WORLD_MAP
        override fun getInteractionBoxLeft(pin: Markers.Pin, context: Context, partialTicks: Float): Int = -halfOf(pin)
        override fun getInteractionBoxRight(pin: Markers.Pin, context: Context, partialTicks: Float): Int = halfOf(pin)
        override fun getInteractionBoxTop(pin: Markers.Pin, context: Context, partialTicks: Float): Int = -halfOf(pin)
        override fun getInteractionBoxBottom(pin: Markers.Pin, context: Context, partialTicks: Float): Int = halfOf(pin)
        override fun getRenderBoxLeft(pin: Markers.Pin, context: Context, partialTicks: Float): Int = -halfOf(pin) - 1
        override fun getRenderBoxRight(pin: Markers.Pin, context: Context, partialTicks: Float): Int = halfOf(pin) + 1
        override fun getRenderBoxTop(pin: Markers.Pin, context: Context, partialTicks: Float): Int = -halfOf(pin) - 1
        override fun getRenderBoxBottom(pin: Markers.Pin, context: Context, partialTicks: Float): Int = halfOf(pin) + 1
        override fun getLeftSideLength(pin: Markers.Pin, minecraft: Minecraft): Int = minecraft.font.width(pin.label) + 9
        override fun getMenuName(pin: Markers.Pin): String = pin.label
        override fun getFilterName(pin: Markers.Pin): String = pin.label
        override fun getMenuTextFillLeftPadding(pin: Markers.Pin): Int = 0
        override fun getRightClickTitleBackgroundColor(pin: Markers.Pin): Int = TITLE_BACKGROUND
        override fun shouldScaleBoxWithOptionalScale(): Boolean = true
        override fun isRightClickValid(pin: Markers.Pin): Boolean = true

        override fun getTooltip(pin: Markers.Pin, context: Context, overMenu: Boolean): Tooltip = Tooltip(describe(pin))

        override fun getRightClickOptions(pin: Markers.Pin, target: IRightClickableElement): ArrayList<RightClickOption> {
            val options = ArrayList<RightClickOption>()
            options.add(object : RightClickOption(pin.label, 0, target) {
                override fun onAction(screen: net.minecraft.client.gui.screens.Screen) {}
            })
            options.add(object : RightClickOption("Save as waypoint", 1, target) {
                override fun onAction(screen: net.minecraft.client.gui.screens.Screen) {
                    Waypoints.save(pin)
                }
            }.setActive(Waypoints.available()))
            return options
        }
    }

    class Renderer(context: Context, provider: Provider, reader: Reader) :
        ElementRenderer<Markers.Pin, Context, Renderer>(context, provider, reader) {

        // Xaero renders elements in two passes, shadows first ("pre"); both go through here.
        override fun shouldRender(location: ElementRenderLocation, pre: Boolean): Boolean =
            location == ElementRenderLocation.WORLD_MAP && (Config.showMarkers || Config.showPlayers)

        override fun preRender(info: ElementRenderInfo, buffers: XaeroBufferProvider, renderers: MultiTextureRenderTypeRendererProvider, pre: Boolean) {
            context.iconRenderer = renderers.getRenderer(CustomRenderTypes.GUI_NEAREST)
        }

        override fun postRender(info: ElementRenderInfo, buffers: XaeroBufferProvider, renderers: MultiTextureRenderTypeRendererProvider, pre: Boolean) {
            context.iconRenderer?.let(renderers::draw)
            context.iconRenderer = null
            buffers.endBatch()
        }

        override fun renderElementShadow(
            pin: Markers.Pin, hovered: Boolean, scale: Float, partialX: Double, partialY: Double,
            info: ElementRenderInfo, graphics: MapElementGraphics, buffers: XaeroBufferProvider, renderers: MultiTextureRenderTypeRendererProvider,
        ) {
        }

        override fun renderElement(
            pin: Markers.Pin, hovered: Boolean, depth: Double, scale: Float, partialX: Double, partialY: Double,
            info: ElementRenderInfo, graphics: MapElementGraphics, buffers: XaeroBufferProvider, renderers: MultiTextureRenderTypeRendererProvider,
        ): Boolean {
            val pose = graphics.pose()
            pose.pushPose()
            pose.translate(partialX, partialY, 0.0)
            pose.scale(scale, scale, 1f)
            val half = halfOf(pin)
            val icon = iconFor(pin)
            val renderer = context.iconRenderer
            if (icon != null && renderer != null) {
                PinDrawing.quad(renderer.begin(icon.view), pose.last().pose(), icon, sizeOf(pin))
            } else {
                graphics.fill(-half + 2, -half + 2, half - 2, half - 2, 0xFF000000.toInt())
                graphics.fill(-half + 3, -half + 3, half - 3, half - 3, dotColour(pin))
            }
            if (hovered) {
                val font = Minecraft.getInstance().font
                val width = font.width(pin.label)
                graphics.fill(-width / 2 - 2, -half - 12, width / 2 + 2, -half - 1, 0x99000000.toInt())
                graphics.drawCenteredString(font, pin.label, 0, -half - 10, 0xFFFFFFFF.toInt())
            }
            pose.popPose()
            return true
        }
    }

    /** Registers with Xaero's world map. Safe to call again; does nothing once done. */
    fun register(): Boolean {
        val handler = xaero.map.WorldMap.mapElementRenderHandler ?: return false
        val context = Context()
        handler.add(Renderer(context, Provider(), Reader()))
        return true
    }

    fun describe(pin: Markers.Pin): Component = when (pin) {
        is Markers.Point -> Component.literal(pin.label).append(
            Component.literal("\n${pin.set} · ${pin.x.toInt()}, ${pin.y.toInt()}, ${pin.z.toInt()}\nRight-click to save as a waypoint")
                .withStyle { it.withColor(0xAAAAAA) }
        )
        is Markers.Player -> Component.literal(pin.label).append(
            Component.literal("\nPlayer · ${pin.x.toInt()}, ${pin.y.toInt()}, ${pin.z.toInt()}").withStyle { it.withColor(0xAAAAAA) }
        )
    }

    /** A pin's picture: the BlueMap icon for a marker, the face from the skin for a player. */
    fun iconFor(pin: Markers.Pin): PinDrawing.Icon? = when (pin) {
        is Markers.Point -> Session.current?.markers?.icon(pin.icon)?.let { (view, width, height) ->
            PinDrawing.Icon(view, 0f, 0f, 1f, 1f, width, height)
        }
        is Markers.Player -> PinDrawing.face(pin.uuid)
    }

    fun dotColour(pin: Markers.Pin): Int = when (pin) {
        is Markers.Player -> 0xFFFFFFFF.toInt()
        is Markers.Point -> 0xFF55FFFF.toInt()
    }

    /** How big a pin is drawn, in the element's units: players' faces 2.5 times a marker's 8. */
    fun sizeOf(pin: Markers.Pin): Float = if (pin is Markers.Player) 20f else 12f

    fun halfOf(pin: Markers.Pin): Int = (sizeOf(pin) / 2).toInt()

    const val HALF = 6
    private const val TITLE_BACKGROUND = 0xFF2A4A6A.toInt()
}

/** Quads for pin pictures, shared by the world map and minimap renderers. */
object PinDrawing {

    /** A picture inside a texture: [u1]..[u2], [v1]..[v2], drawn at up to [width]×[height] units. */
    class Icon(val view: GpuTextureView, val u1: Float, val v1: Float, val u2: Float, val v2: Float, val width: Int, val height: Int)

    /** Draws [icon] centred on the pose's origin, [size] units along its longer side. */
    fun quad(buffer: BufferBuilder, matrix: Matrix4f, icon: Icon, size: Float) {
        val longest = maxOf(icon.width, icon.height).coerceAtLeast(1)
        val halfW = size * icon.width / longest / 2f
        val halfH = size * icon.height / longest / 2f
        buffer.addVertex(matrix, -halfW, halfH, 0f).setColor(1f, 1f, 1f, 1f).setUv(icon.u1, icon.v2)
        buffer.addVertex(matrix, halfW, halfH, 0f).setColor(1f, 1f, 1f, 1f).setUv(icon.u2, icon.v2)
        buffer.addVertex(matrix, halfW, -halfH, 0f).setColor(1f, 1f, 1f, 1f).setUv(icon.u2, icon.v1)
        buffer.addVertex(matrix, -halfW, -halfH, 0f).setColor(1f, 1f, 1f, 1f).setUv(icon.u1, icon.v1)
    }

    /**
     * A player's face from their skin, as the tab list has it: the 8×8 face at (8, 8) of the 64×64
     * skin. Null for a player the client has no skin for.
     */
    fun face(uuid: java.util.UUID): Icon? {
        val minecraft = Minecraft.getInstance()
        val info = minecraft.connection?.getPlayerInfo(uuid) ?: return null
        val path = info.skin.body().texturePath()
        val view = minecraft.textureManager.getTexture(path).textureView
        return Icon(view, 8f / 64f, 8f / 64f, 16f / 64f, 16f / 64f, 8, 8)
    }
}
