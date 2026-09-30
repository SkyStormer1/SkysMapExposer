package com.skystormer.skysmapexposer

/**
 * What a block of BlueMap's lowres map says about the biome it is in, as numbers [BiomeGuide]
 * can compare.
 *
 * BlueMap publishes no biomes, but it draws them: grass, leaves and water are tinted by the biome,
 * and the lie of the land (sea, river, mountain) comes with the height. So a block is described by
 * its own colour (Oklab), height and whether it is water, and then twice over by its surroundings,
 * close ([NEAR] blocks) and wide ([FAR]): the average colour of the land and of the water there,
 * how much of it is water, and the average and spread of the height. Read the same way when the
 * guide was trained and when it guesses, which is the whole point of keeping it in one place.
 */
object BiomeFeatures {

    /** Where the numbers come from: BlueMap's lowres tiles, block by block. */
    interface Ground {
        /** Writes L, a, b, height and 1 for water (else 0) into [out]; false if BlueMap has nothing there. */
        fun sample(x: Int, z: Int, out: FloatArray): Boolean
    }

    const val NEAR = 12
    const val FAR = 64

    /** Numbers per block: its own five, then nine for each of the two surroundings. */
    const val SIZE = 5 + 9 * 2

    /**
     * Fills [out] (at least [SIZE] long) for block ([x], [z]). False if BlueMap has nothing at the
     * block itself. [scratch] is any 5-float array, reused to save garbage.
     */
    fun describe(ground: Ground, x: Int, z: Int, out: FloatArray, scratch: FloatArray): Boolean {
        if (!ground.sample(x, z, scratch)) return false
        val l = scratch[0]
        val a = scratch[1]
        val b = scratch[2]
        out[0] = l
        out[1] = a
        out[2] = b
        out[3] = scratch[3]
        out[4] = scratch[4]
        surroundings(ground, x, z, NEAR, l, a, b, out, 5, scratch)
        surroundings(ground, x, z, FAR, l, a, b, out, 14, scratch)
        return true
    }

    /**
     * The ground within [radius] of a block, looked at on a grid of about 13 by 13 points: mean
     * land colour (the block's own if there is no land), share of water, mean and spread of the
     * height, mean water colour (0 if there is none).
     */
    private fun surroundings(
        ground: Ground, x: Int, z: Int, radius: Int,
        l: Float, a: Float, b: Float, out: FloatArray, at: Int, scratch: FloatArray,
    ) {
        var landL = 0.0
        var landA = 0.0
        var landB = 0.0
        var waterL = 0.0
        var waterA = 0.0
        var waterB = 0.0
        var heights = 0.0
        var heightsSquared = 0.0
        var land = 0
        var water = 0
        var n = 0
        val step = maxOf(1, radius / 6)
        var dz = -radius
        while (dz <= radius) {
            var dx = -radius
            while (dx <= radius) {
                if (ground.sample(x + dx, z + dz, scratch)) {
                    if (scratch[4] != 0f) {
                        waterL += scratch[0]; waterA += scratch[1]; waterB += scratch[2]; water++
                    } else {
                        landL += scratch[0]; landA += scratch[1]; landB += scratch[2]; land++
                    }
                    val h = scratch[3].toDouble()
                    heights += h
                    heightsSquared += h * h
                    n++
                }
                dx += step
            }
            dz += step
        }
        // n is at least 1: the block itself is on the grid.
        val mean = heights / n
        out[at] = if (land == 0) l else (landL / land).toFloat()
        out[at + 1] = if (land == 0) a else (landA / land).toFloat()
        out[at + 2] = if (land == 0) b else (landB / land).toFloat()
        out[at + 3] = water.toFloat() / n
        out[at + 4] = mean.toFloat()
        out[at + 5] = Math.sqrt(maxOf(0.0, heightsSquared / n - mean * mean)).toFloat()
        out[at + 6] = if (water == 0) 0f else (waterL / water).toFloat()
        out[at + 7] = if (water == 0) 0f else (waterA / water).toFloat()
        out[at + 8] = if (water == 0) 0f else (waterB / water).toFloat()
    }

    /**
     * How much each number counts when blocks are compared, once each is scaled to its spread.
     * The surroundings' land colour matters most (it is the biome's tint); the block's own height
     * least, since a biome spans hills and valleys.
     */
    val WEIGHTS = floatArrayOf(
        1f, 1f, 1f, 0.5f, 0.7f,
        1.5f, 1.5f, 1.5f, 1.2f, 1f, 0.7f, 0.7f, 0.7f, 0.7f,
        1.2f, 1.2f, 1.2f, 1.2f, 1f, 0.7f, 0.7f, 0.7f, 0.7f,
    )
}
