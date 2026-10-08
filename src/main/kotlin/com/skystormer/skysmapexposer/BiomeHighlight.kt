package com.skystormer.skysmapexposer

import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import org.joml.Matrix4f
import org.joml.Vector3f
import xaero.lib.XaeroLib
import xaero.map.MapProcessor
import xaero.map.graphics.CustomRenderTypes
import xaero.map.region.texture.RegionTexture

/**
 * Tints the world map wherever Xaero has recorded one of the biomes picked in the [gui.MapBar].
 *
 * Xaero keeps the biome of every pixel of its map alongside the picture (it is what names the
 * biome under the mouse), for each 64-block square of the regions it has in memory. That is read
 * here as it stands, so the tint covers what Xaero has loaded.
 *
 * Drawn into Xaero's colour-overlay buffer from just after the first of its two terrain passes
 * (`GuiMapMixin`), so it lies over all terrain, BlueMap's included, and under the borders, shapes
 * and markers that other code adds to that buffer or draws after it.
 *
 * Each 64-pixel square is turned into as few rectangles as it takes and kept until Xaero redraws
 * the square or the choice of biomes changes. Client thread only.
 *
 * Where Xaero has no biome — land you know only from BlueMap — biomes other players shared with
 * you ([SharedBiomes]) are tinted the same way, one 4×4-block cell at a time.
 */
object BiomeHighlight {

    /** The biomes picked, in the order they were picked, and the colour each is tinted. */
    private val picked = LinkedHashMap<ResourceKey<Biome>, Int>()

    /** Bumped whenever [picked] changes, so every square is worked out again. */
    private var version = 0

    private class Square(val texture: RegionTexture<*>, val textureVersion: Int, val version: Int, val step: Int, val rects: IntArray)

    /**
     * Remembered squares: one map per level of the world map, since each level has its own
     * squares, and one more for the minimap, which reads the finest level at its own zoom.
     */
    private val squares = Array(5) { HashMap<Long, Square>() }

    val any: Boolean get() = picked.isNotEmpty()

    fun isPicked(biome: ResourceKey<Biome>): Boolean = biome in picked

    /** The tint of [biome], or null if it is not picked. */
    fun colourOf(biome: ResourceKey<Biome>): Int? = picked[biome]

    fun toggle(biome: ResourceKey<Biome>) {
        if (picked.remove(biome) == null) picked[biome] = nextColour()
        changed()
    }

    fun clear() {
        if (picked.isEmpty()) return
        picked.clear()
        changed()
    }

    private fun changed() {
        version++
        squares.forEach { it.clear() }
    }

    /** The first colour of [COLOURS] not in use, or the least used once all are. */
    private fun nextColour(): Int {
        val used = picked.values.groupingBy { it }.eachCount()
        return COLOURS.minBy { used[it] ?: 0 }
    }

    /** The world map, from Xaero's `GuiMap`, with the matrix and origin its terrain was drawn with. */
    @JvmStatic
    fun draw(processor: MapProcessor, matrix: Matrix4f, originX: Int, originZ: Int, cameraX: Double, cameraZ: Double) {
        if (picked.isEmpty()) return
        try {
            val view = MapViewport.of(matrix, cameraX, cameraZ)
            // Zoomed out, Xaero draws from coarser copies of its regions (level 1 to 3, each pixel
            // 2, 4 or 8 blocks across), which keep biomes too. Its hover text reads the level on
            // screen, and so does this: the detailed regions are let go of as the map zooms out.
            val level = processor.mapSaveLoad.mainTextureLevel.coerceIn(0, 3)
            val buffer = XaeroLib.INSTANCE.client.bufferProvider.getBuffer(CustomRenderTypes.MAP_COLOR_OVERLAY)
            drawArea(processor, buffer, matrix, level, level, view.blocksPerUnit, originX, originZ, view.minX, view.maxX, view.minZ, view.maxZ)
        } catch (e: Throwable) {
            giveUp("world map", e)
        }
    }

    /**
     * Xaero's minimap, into its overlay buffer, which it draws over its terrain; the minimap draws
     * from the finest level. Called only from the minimap's own mixin, so none of this needs the
     * minimap installed.
     */
    fun drawMinimap(
        processor: MapProcessor, buffer: VertexConsumer, matrix: Matrix4f, blocksPerUnit: Float,
        originX: Int, originZ: Int, minX: Double, maxX: Double, minZ: Double, maxZ: Double,
    ) {
        if (picked.isEmpty() || !Config.minimapBiomes) return
        try {
            drawArea(processor, buffer, matrix, 0, MINIMAP, blocksPerUnit, originX, originZ, minX, maxX, minZ, maxZ)
        } catch (e: Throwable) {
            giveUp("minimap", e)
        }
    }

    private fun drawArea(
        processor: MapProcessor, buffer: VertexConsumer, matrix: Matrix4f, level: Int, slot: Int, blocksPerUnit: Float,
        originX: Int, originZ: Int, minX: Double, maxX: Double, minZ: Double, maxZ: Double,
    ) {
        val pixel = 1 shl level
        val square = SIDE shl level
        val region = REGION shl level
        // Far out, a pixel on screen covers many of the level's pixels, so they are read that coarsely.
        val step = Integer.highestOneBit((blocksPerUnit / pixel).toInt().coerceIn(1, MAX_STEP))
        val layer = processor.currentCaveLayer
        for (rx in Math.floorDiv(minX.toInt(), region)..Math.floorDiv(maxX.toInt(), region)) {
            for (rz in Math.floorDiv(minZ.toInt(), region)..Math.floorDiv(maxZ.toInt(), region)) {
                val leveled = processor.getLeveledRegion(layer, rx, rz, level) ?: continue
                if (!leveled.hasTextures()) continue
                for (cx in 0 until 8) {
                    for (cz in 0 until 8) {
                        val texture = leveled.getTexture(cx, cz) ?: continue
                        val squareX = rx * 8 + cx
                        val squareZ = rz * 8 + cz
                        val rects = rectsOf(slot, squareX, squareZ, texture, step)
                        drawRects(buffer, matrix, rects, pixel, squareX * square - originX, squareZ * square - originZ)
                    }
                }
            }
        }
        if (squares[slot].size > MAX_SQUARES) squares[slot].clear()
        drawShared(processor, buffer, matrix, level, originX, originZ, minX, maxX, minZ, maxZ)
    }

    /** The picked biomes among those shared with you, wherever Xaero has no biome of its own. */
    private fun drawShared(
        processor: MapProcessor, buffer: VertexConsumer, matrix: Matrix4f, level: Int,
        originX: Int, originZ: Int, minX: Double, maxX: Double, minZ: Double, maxZ: Double,
    ) {
        val dimensionId = processor.mapWorld?.currentDimension?.dimId?.identifier()?.toString() ?: return
        val cell = IntArray(5)
        SharedBiomes.forEachIn(dimensionId, minX, maxX, minZ, maxZ) { blockX, blockZ, cells ->
            if (cells.none { it != null && it in picked }) return@forEachIn
            if (xaeroHasBiome(processor, blockX + 8, blockZ + 8, level)) return@forEachIn
            for (i in 0 until 16) {
                val colour = cells[i]?.let(picked::get) ?: continue
                cell[0] = (i shr 2) * 4
                cell[1] = (i and 3) * 4
                cell[2] = cell[0] + 4
                cell[3] = cell[1] + 4
                cell[4] = colour
                drawRects(buffer, matrix, cell, 1, blockX - originX, blockZ - originZ)
            }
        }
    }

    /**
     * Whether Xaero has a biome for block ([x], [z]) at map level [level] (the one on screen by
     * default) of the cave layer it is showing.
     */
    fun xaeroHasBiome(processor: MapProcessor, x: Int, z: Int, level: Int = processor.mapSaveLoad.mainTextureLevel.coerceIn(0, 3)): Boolean {
        val square = SIDE shl level
        val squareX = Math.floorDiv(x, square)
        val squareZ = Math.floorDiv(z, square)
        val leveled = processor.getLeveledRegion(processor.currentCaveLayer, Math.floorDiv(squareX, 8), Math.floorDiv(squareZ, 8), level) ?: return false
        if (!leveled.hasTextures()) return false
        val texture = leveled.getTexture(Math.floorMod(squareX, 8), Math.floorMod(squareZ, 8)) ?: return false
        return texture.getBiome((x - squareX * square) shr level, (z - squareZ * square) shr level) != null
    }

    private fun giveUp(where: String, e: Throwable) {
        picked.clear()
        changed()
        Log.error("Could not highlight biomes on the $where; the highlight is turned off", e)
    }

    /**
     * [texture]'s picked biomes as rectangles `x0, z0, x1, z1, colour` within its square, in the
     * texture's own pixels, remembered.
     */
    private fun rectsOf(slot: Int, squareX: Int, squareZ: Int, texture: RegionTexture<*>, step: Int): IntArray {
        val key = (squareX.toLong() shl 32) or (squareZ.toLong() and 0xFFFFFFFFL)
        val known = squares[slot][key]
        val textureVersion = texture.textureVersion
        if (known != null && known.texture === texture && known.textureVersion == textureVersion &&
            known.version == version && known.step == step
        ) return known.rects
        val rects = trace(texture, step)
        squares[slot][key] = Square(texture, textureVersion, version, step, rects)
        return rects
    }

    /**
     * Runs of one picked biome along each row, every [step] blocks, each joined to the run straight
     * above it when the two match exactly: biomes come in patches, so this makes a few rectangles
     * where there would be thousands of pixels.
     */
    private fun trace(texture: RegionTexture<*>, step: Int): IntArray {
        val cells = SIDE / step
        val out = ArrayList<Int>()
        // The rectangles still open from the row above, by their first cell: index into out.
        var open = HashMap<Int, Int>()
        for (row in 0 until cells) {
            val next = HashMap<Int, Int>()
            var col = 0
            while (col < cells) {
                val colour = texture.getBiome(col * step, row * step)?.let(picked::get)
                if (colour == null) {
                    col++
                    continue
                }
                val start = col
                while (col < cells && texture.getBiome(col * step, row * step)?.let(picked::get) == colour) col++
                val above = open[start]
                if (above != null && out[above + 2] == col * step && out[above + 4] == colour) {
                    out[above + 3] = (row + 1) * step
                    next[start] = above
                } else {
                    next[start] = out.size
                    out.addAll(listOf(start * step, row * step, col * step, (row + 1) * step, colour))
                }
            }
            open = next
        }
        return out.toIntArray()
    }

    /** Draws [rects], in pixels [pixel] blocks across, with the square's corner at ([left], [top]). */
    private fun drawRects(buffer: VertexConsumer, matrix: Matrix4f, rects: IntArray, pixel: Int, left: Int, top: Int) {
        var i = 0
        while (i < rects.size) {
            val colour = rects[i + 4]
            val a = (colour ushr 24) / 255f
            val r = ((colour shr 16) and 0xFF) / 255f
            val g = ((colour shr 8) and 0xFF) / 255f
            val b = (colour and 0xFF) / 255f
            val x0 = (left + rects[i] * pixel).toFloat()
            val z0 = (top + rects[i + 1] * pixel).toFloat()
            val x1 = (left + rects[i + 2] * pixel).toFloat()
            val z1 = (top + rects[i + 3] * pixel).toFloat()
            buffer.addVertex(matrix, x0, z0, 0f).setColor(r, g, b, a)
            buffer.addVertex(matrix, x0, z1, 0f).setColor(r, g, b, a)
            buffer.addVertex(matrix, x1, z1, 0f).setColor(r, g, b, a)
            buffer.addVertex(matrix, x1, z0, 0f).setColor(r, g, b, a)
            i += 5
        }
    }

    private const val REGION = 512
    private const val SIDE = 64
    private const val MAX_STEP = 16
    private const val MAX_SQUARES = 50_000
    private const val MINIMAP = 4

    /** See-through tints that stand apart from each other and from terrain's greens and blues. */
    private val COLOURS = intArrayOf(
        0x90FF3B30.toInt(), 0x90FFCC00.toInt(), 0x90FF2DAA.toInt(), 0x90AF52DE.toInt(),
        0x90FF9500.toInt(), 0x9000E5FF.toInt(), 0x90FFFFFF.toInt(), 0x9034C759.toInt(),
    )
}

/**
 * How much of the world Xaero's map is showing, from its own matrix: its units are at most window
 * pixels, so measuring with the window's size never sees too little.
 */
class MapViewport(val blocksPerUnit: Float, val minX: Double, val maxX: Double, val minZ: Double, val maxZ: Double) {
    companion object {
        fun of(matrix: Matrix4f, cameraX: Double, cameraZ: Double): MapViewport {
            val inverse = Matrix4f(matrix).invert()
            val blocksPerUnit = inverse.transformDirection(Vector3f(1f, 0f, 0f)).length().coerceAtLeast(1e-4f)
            val window = Minecraft.getInstance().window
            val halfWidth = window.width * blocksPerUnit * 0.5 + 32
            val halfHeight = window.height * blocksPerUnit * 0.5 + 32
            return MapViewport(blocksPerUnit, cameraX - halfWidth, cameraX + halfWidth, cameraZ - halfHeight, cameraZ + halfHeight)
        }
    }
}
