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
     * each pixel is lit the way Xaero lights its own map ([xaeroLight]), so BlueMap's part of the
     * map has the same relief and brightness as the part you explored.
     */
    fun decode(bytes: ByteArray, tileSize: Int, blocksPerPixel: Int): IntArray {
        val image = BlueMap.readImage(bytes, BlueMap.MAX_TILE_IMAGE)
        val width = image.width
        val half = image.height / 2
        require(width == half && width == tileSize + 1) { "unexpected tile shape ${width}x${image.height}" }
        val source = image.getRGB(0, 0, width, image.height, null, 0, width)

        fun height(x: Int, z: Int): Int = source[(half + z) * width + x].toShort().toInt()

        // A neighbour off the tile's edge or where BlueMap has nothing counts as level with this pixel.
        fun heightOr(x: Int, z: Int, here: Int): Int =
            if (x < 0 || z < 0 || source[z * width + x] ushr 24 == 0) here else height(x, z)

        val pixels = IntArray(width * half)
        for (z in 0 until half) {
            for (x in 0 until width) {
                val colour = source[z * width + x]
                if (colour ushr 24 == 0) continue
                val here = height(x, z)
                val north = heightOr(x, z - 1, here)
                val northWest = heightOr(x - 1, z - 1, here)
                val shade = xaeroLight(here, here - Math.floorDiv(here - north, blocksPerPixel), here - Math.floorDiv(here - northWest, blocksPerPixel))
                pixels[z * width + x] = OPAQUE or
                    (channel(colour shr 16, shade) shl 16) or
                    (channel(colour shr 8, shade) shl 8) or
                    channel(colour, shade)
            }
        }
        return pixels
    }

    /**
     * How much Xaero's World Map brightens or darkens a block at [height] on the surface, with the
     * block north of it at [north] and north-west at [northWest]: a factor for its colour. Xaero's
     * own sums (1.47) at its usual settings, "terrain depth" on and "terrain slopes" 3D, in
     * daylight; read out of its `MapPixel.getPixelColours` and `MapBlock.fixHeightType`.
     *
     * Depth: ground below the sea is a little darker. Slopes: a light from the north-west, so flat
     * ground comes out about 1.17×, ground rising towards it up to 1.37× and ground falling away
     * from it 0.7×.
     */
    fun xaeroLight(height: Int, north: Int, northWest: Int): Float {
        val depth = (height / 63f).coerceIn(0.9f, 1f)
        val vertical = (height - north).coerceIn(-128, 127)
        val diagonal = (height - northWest).coerceIn(-128, 127)
        val facing = when {
            vertical < 0 -> 0f
            vertical == 1 && diagonal == 1 -> 1f
            else -> {
                val across = (vertical - diagonal).toFloat()
                (1f + vertical) / kotlin.math.sqrt(across * across + 1f + vertical.toFloat() * vertical) / SQRT2
            }
        }
        val direct = when {
            facing == 1f -> MAX_DIRECT
            facing > 0f -> kotlin.math.ceil(facing * 10f) / 10f * MAX_DIRECT * 0.88388f
            else -> 0f
        }
        return depth * (AMBIENT_COLOURED + AMBIENT_WHITE + direct)
    }

    private const val AMBIENT_COLOURED = 0.2f
    private const val AMBIENT_WHITE = 0.5f
    private const val MAX_DIRECT = 0.6666667f
    private val SQRT2 = kotlin.math.sqrt(2f)

    /** The height of the ground at each pixel BlueMap has anything at, from the height half. */
    fun heights(bytes: ByteArray, tileSize: Int): IntArray {
        val image = BlueMap.readImage(bytes, BlueMap.MAX_TILE_IMAGE)
        val width = image.width
        val half = image.height / 2
        require(width == half && width == tileSize + 1) { "unexpected tile shape ${width}x${image.height}" }
        val source = image.getRGB(0, 0, width, image.height, null, 0, width)
        val heights = IntArray(width * half)
        var count = 0
        for (i in 0 until width * half) {
            if (source[i] ushr 24 != 0) heights[count++] = source[half * width + i].toShort().toInt()
        }
        return heights.copyOf(count)
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
