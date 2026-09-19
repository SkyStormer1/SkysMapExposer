package com.skystormer.skysmapexposer

import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.rendertype.RenderType
import org.joml.Matrix4f
import org.joml.Vector3f
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRenderer
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider
import xaero.map.MapProcessor
import java.util.function.Consumer

/**
 * Xaero's minimap, when it draws its terrain from Xaero's World Map (which it does whenever both
 * are installed): the same backfill as the world map, from the same tiles and the same decisions,
 * then BlueMap's outlines.
 *
 * The minimap has its own copy of Xaero's renderer classes, and its terrain draws at block
 * coordinates relative to the player's floored position, with a pose matrix already set up for
 * the minimap's zoom and rotation.
 */
object MinimapOverlay {

    var hookRan = false
        private set

    var lastSummary = "The minimap has not been drawn yet."
        private set

    @JvmStatic
    fun draw(
        mapProcessor: MapProcessor,
        pose: PoseStack,
        originX: Int,
        originZ: Int,
        minViewX: Int,
        minViewZ: Int,
        maxViewX: Int,
        maxViewZ: Int,
        overlayBuffer: VertexConsumer,
        rendererProvider: MultiTextureRenderTypeRendererProvider,
        mapRenderType: RenderType,
        finalizer: Consumer<GpuTextureView>?,
    ) {
        Backfill.nextFrame()
        if (!hookRan) {
            hookRan = true
        }
        val matrix: Matrix4f = pose.last().pose()

        // The minimap's view bounds come in Xaero's 64-block units.
        val minX = minViewX * 64.0
        val maxX = (maxViewX + 1) * 64.0
        val minZ = minViewZ * 64.0
        val maxZ = (maxViewZ + 1) * 64.0
        val blocksPerUnit = Matrix4f(matrix).invert().transformDirection(Vector3f(1f, 0f, 0f)).length().coerceAtLeast(1e-4f)

        // Outlines first in the code, but into the overlay buffer, which the minimap draws after its terrain.
        if (Config.showOutlines) {
            val session = Session.current
            val dimension = mapProcessor.mapWorld?.currentDimension?.dimId?.identifier()?.toString()
            val map = if (session != null && dimension != null) session.server.markersFor(dimension) else null
            if (session != null && map != null) {
                session.markers.of(map)?.outlines?.let { outlines ->
                    Outlines.draw(overlayBuffer, matrix, outlines, originX, originZ, blocksPerUnit, minX, maxX, minZ, maxZ)
                }
            }
        }

        val target = Backfill.target(mapProcessor, ::standDown) ?: return
        val factor = Backfill.factorFor(target.layout.tileSize, blocksPerUnit)

        val plan = Backfill.plan(target, mapProcessor, originX.toDouble(), originZ.toDouble(), minX, maxX, minZ, maxZ, factor, Backfill.Budget(uploads = 4, masks = 4))
            ?: return standDown("too-far", "The minimap covers too much to backfill")

        var renderer: MultiTextureRenderTypeRenderer? = null
        for (piece in plan.pieces) {
            val batch = renderer ?: rendererProvider.getRenderer(finalizer, mapRenderType).also { renderer = it }
            val size = piece.tileSize.toFloat()
            val r = piece.mask.rectangles
            val buffer = batch.begin(piece.texture)
            for (i in 0 until piece.mask.count) {
                val x1 = r[i * 4]
                val z1 = r[i * 4 + 1]
                val x2 = r[i * 4 + 2]
                val z2 = r[i * 4 + 3]
                val u1 = (x1 - piece.originX) / size
                val v1 = (z1 - piece.originZ) / size
                val u2 = (x2 - piece.originX) / size
                val v2 = (z2 - piece.originZ) / size
                val left = x1 - originX
                val top = z1 - originZ
                val right = x2 - originX
                val bottom = z2 - originZ
                // Same corner order and texture orientation as the minimap's own terrain.
                buffer.addVertex(matrix, left, bottom, 0f).setUv(u1, v2)
                buffer.addVertex(matrix, right, bottom, 0f).setUv(u2, v2)
                buffer.addVertex(matrix, right, top, 0f).setUv(u2, v1)
                buffer.addVertex(matrix, left, top, 0f).setUv(u1, v1)
            }
        }
        renderer?.let(rendererProvider::draw)
        lastSummary = plan.summary
    }

    private fun standDown(key: String, reason: String) {
        lastSummary = "Not drawing: $reason."
    }
}
