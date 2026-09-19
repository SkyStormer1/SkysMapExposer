package com.skystormer.skysmapexposer

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import org.joml.Matrix4f
import org.joml.Vector3f
import xaero.lib.XaeroLib
import xaero.map.MapProcessor
import xaero.map.graphics.CustomRenderTypes
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider
import xaero.map.gui.GuiMap
import kotlin.math.sqrt

/**
 * The world map screen: BlueMap's terrain where [Backfill] says so, then BlueMap's outlines (world
 * border, zones) on top of all terrain. Markers are drawn separately, by [MarkerElements], through
 * Xaero's own element system so that they can be hovered and right-clicked.
 */
object Overlay {

    /** Whether Xaero's map has called in at least once. `/mapexposer` reports it. */
    var hookRan = false
        private set

    /** One line on what the last frame did, for `/mapexposer` and the settings screen. */
    var lastSummary = "The world map has not been opened yet."
        private set

    @JvmStatic
    fun draw(
        mapProcessor: MapProcessor,
        matrix: Matrix4f,
        flooredCameraX: Int,
        flooredCameraZ: Int,
        cameraX: Double,
        cameraZ: Double,
        rendererProvider: MultiTextureRenderTypeRendererProvider,
    ) {
        Backfill.nextFrame()
        if (!hookRan) {
            hookRan = true
        }
        // How much of the world is on screen, from Xaero's own matrix. Its units are at most window
        // pixels, so measuring with the window's size never sees too little.
        val inverse = Matrix4f(matrix).invert()
        val blocksPerUnit = inverse.transformDirection(Vector3f(1f, 0f, 0f)).length().coerceAtLeast(1e-4f)
        val window = Minecraft.getInstance().window
        val halfWidth = window.width * blocksPerUnit * 0.5 + 32
        val halfHeight = window.height * blocksPerUnit * 0.5 + 32
        val minX = cameraX - halfWidth
        val maxX = cameraX + halfWidth
        val minZ = cameraZ - halfHeight
        val maxZ = cameraZ + halfHeight

        drawOutlines(mapProcessor, matrix, flooredCameraX, flooredCameraZ, blocksPerUnit, minX, maxX, minZ, maxZ)

        val target = Backfill.target(mapProcessor, ::standDown) ?: return
        val factor = Backfill.factorFor(target.layout.tileSize, blocksPerUnit)
        val plan = Backfill.plan(target, mapProcessor, cameraX, cameraZ, minX, maxX, minZ, maxZ, factor, Backfill.Budget())
            ?: return standDown("too-far", "Zoomed out too far to cover with BlueMap")

        var renderer: MultiTextureRenderTypeRenderer? = null
        for (piece in plan.pieces) {
            val batch = renderer ?: rendererProvider.getRenderer(CustomRenderTypes.MAP).also { renderer = it }
            val size = piece.tileSize.toFloat()
            val r = piece.mask.rectangles
            for (i in 0 until piece.mask.count) {
                val rMinX = r[i * 4]
                val rMinZ = r[i * 4 + 1]
                val rMaxX = r[i * 4 + 2]
                val rMaxZ = r[i * 4 + 3]
                GuiMap.renderTexturedModalSubRectWithLighting(
                    matrix,
                    rMinX - flooredCameraX, rMinZ - flooredCameraZ,
                    (rMinX - piece.originX) / size, (rMinZ - piece.originZ) / size,
                    (rMaxX - piece.originX) / size, (rMaxZ - piece.originZ) / size,
                    rMaxX - rMinX, rMaxZ - rMinZ,
                    piece.texture, false, batch,
                )
            }
        }
        renderer?.let(rendererProvider::draw)

        lastSummary = plan.summary + " (${"%.2f".format(blocksPerUnit)} blocks per screen unit)"
    }

    /**
     * The world border and zones, into Xaero's own overlay buffer, which it draws once its
     * highlights are in. Shown wherever the dimension has a BlueMap map, even with its terrain off.
     */
    private fun drawOutlines(
        mapProcessor: MapProcessor, matrix: Matrix4f, originX: Int, originZ: Int, blocksPerUnit: Float,
        minX: Double, maxX: Double, minZ: Double, maxZ: Double,
    ) {
        // Not skipped in cave mode: Xaero shows the nether in cave mode all the time.
        if (!Config.showOutlines) return
        val session = Session.current ?: return
        val dimension = mapProcessor.mapWorld?.currentDimension?.dimId?.identifier()?.toString() ?: return
        val map = session.server.markersFor(dimension) ?: return
        val outlines = session.markers.of(map)?.outlines ?: return
        val buffer = XaeroLib.INSTANCE.client.bufferProvider.getBuffer(CustomRenderTypes.MAP_COLOR_OVERLAY)
        Outlines.draw(buffer, matrix, outlines, originX, originZ, blocksPerUnit, minX, maxX, minZ, maxZ)
    }

    private fun standDown(key: String, reason: String) {
        lastSummary = "Not drawing: $reason."
    }
}

/**
 * BlueMap's outline markers as lines of quads, into a position-and-colour buffer, for either map.
 * Line width is kept in screen units, the way BlueMap draws it, whatever the zoom.
 */
object Outlines {

    fun draw(
        buffer: VertexConsumer,
        matrix: Matrix4f,
        outlines: List<Markers.Outline>,
        originX: Int,
        originZ: Int,
        blocksPerUnit: Float,
        minX: Double,
        maxX: Double,
        minZ: Double,
        maxZ: Double,
    ) {
        for (outline in outlines) {
            val p = outline.points
            val corners = p.size / 2
            val half = outline.width * blocksPerUnit * 0.5f
            val a = (outline.colour ushr 24) / 255f
            val red = ((outline.colour shr 16) and 0xFF) / 255f
            val green = ((outline.colour shr 8) and 0xFF) / 255f
            val blue = (outline.colour and 0xFF) / 255f
            val segments = if (outline.closed) corners else corners - 1
            for (i in 0 until segments) {
                val j = (i + 1) % corners
                val x1 = p[i * 2]
                val z1 = p[i * 2 + 1]
                val x2 = p[j * 2]
                val z2 = p[j * 2 + 1]
                if (maxOf(x1, x2) < minX || minOf(x1, x2) > maxX || maxOf(z1, z2) < minZ || minOf(z1, z2) > maxZ) continue
                val dx = x2 - x1
                val dz = z2 - z1
                val length = sqrt(dx * dx + dz * dz)
                if (length < 1e-3f) continue
                // Widened sideways, and lengthened by half the width at each end so corners meet.
                val nx = -dz / length * half
                val nz = dx / length * half
                val ex = dx / length * half
                val ez = dz / length * half
                val ax = x1 - ex - originX
                val az = z1 - ez - originZ
                val bx = x2 + ex - originX
                val bz = z2 + ez - originZ
                buffer.addVertex(matrix, ax + nx, az + nz, 0f).setColor(red, green, blue, a)
                buffer.addVertex(matrix, bx + nx, bz + nz, 0f).setColor(red, green, blue, a)
                buffer.addVertex(matrix, bx - nx, bz - nz, 0f).setColor(red, green, blue, a)
                buffer.addVertex(matrix, ax - nx, az - nz, 0f).setColor(red, green, blue, a)
            }
        }
    }
}
