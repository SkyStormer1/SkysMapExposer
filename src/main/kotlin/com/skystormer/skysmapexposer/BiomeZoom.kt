package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import net.minecraft.world.level.biome.BiomeManager

/**
 * The game's own rule for which biome each block shows. The game keeps one biome per 4 × 4 × 4
 * blocks, and each block takes the biome of one of the eight nearest of those cells, picked by a
 * jitter from the world's zoom seed ([BiomeManager.getBiome]), so borders wander block by block
 * instead of running in 4-block steps. Xaero's map records each block's biome that way; a map that
 * has only the 4-block cells (JourneyMap's chunk cache) gets the same borders through this.
 *
 * The rule is the game's, copied rather than called, as its interface differs between versions;
 * it is plain arithmetic, safe off the client thread.
 */
object BiomeZoom {

    /** The world's zoom seed, as the server sent it; null outside a world, or if this game keeps it elsewhere. */
    fun seed(): Long? = try {
        Minecraft.getInstance().level?.biomeManager?.let { seedField?.getLong(it) }
    } catch (e: Exception) {
        null
    }

    private val seedField by lazy {
        try {
            BiomeManager::class.java.getDeclaredField("biomeZoomSeed").apply { isAccessible = true }
        } catch (e: Exception) {
            Log.warn("This game keeps its biome zoom seed elsewhere; biome borders on JourneyMap stay in 4-block steps: {}", e.toString())
            null
        }
    }

    /** The cell (quarter-block coordinates, x then z, packed by [packXZ]) whose biome block ([x], [y], [z]) shows. */
    fun cellOf(seed: Long, x: Int, y: Int, z: Int): Long {
        val i = x - 2
        val j = y - 2
        val k = z - 2
        val cellX = i shr 2
        val cellY = j shr 2
        val cellZ = k shr 2
        val fracX = (i and 3) / 4.0
        val fracY = (j and 3) / 4.0
        val fracZ = (k and 3) / 4.0
        var best = 0
        var bestDistance = Double.POSITIVE_INFINITY
        for (corner in 0 until 8) {
            val lowX = corner and 4 == 0
            val lowY = corner and 2 == 0
            val lowZ = corner and 1 == 0
            val distance = fiddledDistance(
                seed,
                if (lowX) cellX else cellX + 1, if (lowY) cellY else cellY + 1, if (lowZ) cellZ else cellZ + 1,
                if (lowX) fracX else fracX - 1.0, if (lowY) fracY else fracY - 1.0, if (lowZ) fracZ else fracZ - 1.0,
            )
            if (bestDistance > distance) {
                best = corner
                bestDistance = distance
            }
        }
        return packXZ(if (best and 4 == 0) cellX else cellX + 1, if (best and 1 == 0) cellZ else cellZ + 1)
    }

    private fun fiddledDistance(seed: Long, x: Int, y: Int, z: Int, dx: Double, dy: Double, dz: Double): Double {
        var m = next(seed, x.toLong())
        m = next(m, y.toLong())
        m = next(m, z.toLong())
        m = next(m, x.toLong())
        m = next(m, y.toLong())
        m = next(m, z.toLong())
        val fx = fiddle(m)
        m = next(m, seed)
        val fy = fiddle(m)
        m = next(m, seed)
        val fz = fiddle(m)
        return square(dz + fz) + square(dy + fy) + square(dx + fx)
    }

    private fun fiddle(value: Long): Double = (Math.floorMod(value shr 24, 1024) / 1024.0 - 0.5) * 0.9

    private fun next(value: Long, add: Long): Long = value * (value * 6364136223846793005L + 1442695040888963407L) + add

    private fun square(value: Double) = value * value
}
