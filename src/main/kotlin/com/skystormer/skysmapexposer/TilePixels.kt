package com.skystormer.skysmapexposer

import java.util.BitSet

/**
 * BlueMap's lowres pictures turned into what the maps draw: shaded colours, and the same shrunk
 * for each zoom factor. No Minecraft classes, so it runs anywhere.
 */
object TilePixels {

    const val OPAQUE = 0xFF shl 24

    private val FACTORS = listOf(1, 2, 4, 5, 8, 10, 16, 20, 25, 32, 50, 100)

    /** Divisors of the tile size among the factors the maps zoom by, finest first. */
    fun factorsFor(tileSize: Int): List<Int> = FACTORS.filter { tileSize % it == 0 }

    /**
     * Turns BlueMap's colour and height halves into one shaded picture, `tileSize + 1` square, 0
     * wherever BlueMap has nothing.
     *
     * BlueMap's colours are flat; its web viewer shades them in a shader from the height half. Here
     * each pixel is lightened where the ground rises towards the north-west and darkened where it
     * falls, so hills read the way they do on the rest of the map.
     */
    fun decode(bytes: ByteArray, tileSize: Int, blocksPerPixel: Int): IntArray {
        val image = BlueMap.readImage(bytes, BlueMap.MAX_TILE_IMAGE)
        val width = image.width
        val half = image.height / 2
        require(width == half && width == tileSize + 1) { "unexpected tile shape ${width}x${image.height}" }
        val source = image.getRGB(0, 0, width, image.height, null, 0, width)
        val perPixel = blocksPerPixel.toFloat()

        fun height(x: Int, z: Int): Int = source[(half + z) * width + x].toShort().toInt()

        val pixels = IntArray(width * half)
        for (z in 0 until half) {
            for (x in 0 until width) {
                val colour = source[z * width + x]
                if (colour ushr 24 == 0) continue
                val here = height(x, z)
                val rise = (here - height(x, maxOf(z - 1, 0))) + (here - height(maxOf(x - 1, 0), z))
                val shade = 1f + (rise / perPixel).coerceIn(-6f, 6f) * 0.05f
                pixels[z * width + x] = OPAQUE or
                    (channel(colour shr 16, shade) shl 16) or
                    (channel(colour shr 8, shade) shl 8) or
                    channel(colour, shade)
            }
        }
        return pixels
    }

    /** Which pixels of [colours] have anything. */
    fun presence(colours: IntArray): BitSet {
        val present = BitSet(colours.size)
        for (i in colours.indices) if (colours[i] != 0) present.set(i)
        return present
    }

    /**
     * Averages each [factor]×[factor] square of the tile (leaving out BlueMap's overlap column and
     * row) into one pixel, ignoring pixels BlueMap has nothing at.
     */
    fun shrink(colours: IntArray, imageSize: Int, tileSize: Int, factor: Int): IntArray {
        val side = tileSize / factor
        val out = IntArray(side * side)
        for (outZ in 0 until side) {
            for (outX in 0 until side) {
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                for (z in outZ * factor until (outZ + 1) * factor) {
                    for (x in outX * factor until (outX + 1) * factor) {
                        val colour = colours[z * imageSize + x]
                        if (colour == 0) continue
                        red += (colour shr 16) and 0xFF
                        green += (colour shr 8) and 0xFF
                        blue += colour and 0xFF
                        count++
                    }
                }
                if (count > 0) {
                    out[outZ * side + outX] = OPAQUE or ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
                }
            }
        }
        return out
    }

    private fun channel(value: Int, shade: Float): Int = ((value and 0xFF) * shade).toInt().coerceIn(0, 255)
}
