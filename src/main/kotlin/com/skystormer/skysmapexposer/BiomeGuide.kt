package com.skystormer.skysmapexposer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Guesses the biome of blocks nobody has mapped, from BlueMap's lowres picture alone.
 *
 * BlueMap publishes no biomes, and the world's seed is not to be had. But BlueMap draws biomes —
 * grass, leaves and water tinted by the biome, the lie of the land — so the guide compares a
 * block's [BiomeFeatures] with blocks whose biome is known, and takes the biome most of the
 * nearest [K] of them are in: nearest-neighbour matching, no more. The known blocks come with the
 * mod (`/skysmapexposer/biomes/`), 12,000 blocks sampled from a real survival map where Xaero had
 * recorded every biome — colours, heights and a biome name each, no places — so a new player who
 * has mapped nothing gets guesses too. Tested on regions left out of that set, the guess is the exact
 * biome about 75% of the time; most misses are look-alikes (plains or sunflower plains) that Xaero
 * tints the same.
 *
 * Guesses are made every [CELL] blocks and each is smoothed with its neighbours', as biomes come
 * in patches.
 */
class BiomeGuide private constructor(
    private val names: List<String>,
    private val mean: FloatArray,
    /** Per number: its weight over its spread, so scaled numbers compare fairly. */
    private val scale: FloatArray,
    private val labels: ShortArray,
    /** The known blocks' scaled numbers, one row of [BiomeFeatures.SIZE] each. */
    private val points: FloatArray,
) {

    /**
     * A region's guesses, block by block, `z * 512 + x` from its north-west corner, null where
     * BlueMap has nothing. Kept as one index per block, half a megabyte a region.
     */
    class Guess(private val names: List<String>, private val blocks: ShortArray) {
        fun at(x: Int, z: Int): String? = blocks[z * BlueMapPicture.REGION + x].let { if (it < 0) null else names[it.toInt()] }
    }

    /** Guesses region ([regionX], [regionZ]) from [ground]. */
    fun guess(ground: BiomeFeatures.Ground, regionX: Int, regionZ: Int): Guess {
        val originX = regionX * BlueMapPicture.REGION
        val originZ = regionZ * BlueMapPicture.REGION
        val shares = arrayOfNulls<DoubleArray>(CELLS * CELLS)
        val features = FloatArray(BiomeFeatures.SIZE)
        val scratch = FloatArray(5)
        for (cz in 0 until CELLS) {
            for (cx in 0 until CELLS) {
                val x = originX + cx * CELL + CELL / 2
                val z = originZ + cz * CELL + CELL / 2
                val found = OFFSETS.any { (dx, dz) -> BiomeFeatures.describe(ground, x + dx, z + dz, features, scratch) }
                if (found) shares[cz * CELLS + cx] = vote(features)
            }
        }

        val smoothed = arrayOfNulls<FloatArray>(CELLS * CELLS)
        val top = IntArray(CELLS * CELLS) { -1 }
        val sum = DoubleArray(names.size)
        for (cz in 0 until CELLS) {
            for (cx in 0 until CELLS) {
                if (shares[cz * CELLS + cx] == null) continue
                sum.fill(0.0)
                for (dz in -SMOOTH..SMOOTH) {
                    for (dx in -SMOOTH..SMOOTH) {
                        val nx = cx + dx
                        val nz = cz + dz
                        if (nx !in 0 until CELLS || nz !in 0 until CELLS) continue
                        val near = shares[nz * CELLS + nx] ?: continue
                        val weight = 1.0 / (1.0 + Math.hypot(dx.toDouble(), dz.toDouble()))
                        for (j in sum.indices) sum[j] += weight * near[j]
                    }
                }
                var first = 0
                for (j in 1 until sum.size) if (sum[j] > sum[first]) first = j
                val cell = cz * CELLS + cx
                val total = sum.sum()
                smoothed[cell] = FloatArray(sum.size) { (sum[it] / total).toFloat() }
                top[cell] = first
            }
        }
        return Guess(names, blend(smoothed, top))
    }

    /**
     * Each block's biome from the four cells whose middles surround it, each counting the more the
     * nearer the block is to its middle. Taking only a block's own cell would draw every edge
     * between biomes as a staircase of 8-block steps.
     */
    private fun blend(smoothed: Array<FloatArray?>, top: IntArray): ShortArray {
        val size = BlueMapPicture.REGION
        val blocks = ShortArray(size * size) { -1 }
        val mix = FloatArray(names.size)
        for (z in 0 until size) {
            val gz = (z - CELL / 2 + 0.5f) / CELL
            val z0 = Math.floor(gz.toDouble()).toInt()
            val tz = gz - z0
            for (x in 0 until size) {
                val gx = (x - CELL / 2 + 0.5f) / CELL
                val x0 = Math.floor(gx.toDouble()).toInt()
                val tx = gx - x0
                var only = -2
                var mixed = false
                for (corner in 0 until 4) {
                    val cx = (x0 + (corner and 1)).coerceIn(0, CELLS - 1)
                    val cz = (z0 + (corner shr 1)).coerceIn(0, CELLS - 1)
                    val t = top[cz * CELLS + cx]
                    if (t < 0) continue
                    if (only == -2) only = t else if (only != t) mixed = true
                }
                if (only < 0) continue
                if (!mixed) {
                    blocks[z * size + x] = only.toShort()
                    continue
                }
                mix.fill(0f)
                for (corner in 0 until 4) {
                    val cx = (x0 + (corner and 1)).coerceIn(0, CELLS - 1)
                    val cz = (z0 + (corner shr 1)).coerceIn(0, CELLS - 1)
                    val shares = smoothed[cz * CELLS + cx] ?: continue
                    val weight = (if (corner and 1 == 0) 1 - tx else tx) * (if (corner shr 1 == 0) 1 - tz else tz)
                    for (j in mix.indices) mix[j] += weight * shares[j]
                }
                var best = 0
                for (j in 1 until mix.size) if (mix[j] > mix[best]) best = j
                blocks[z * size + x] = best.toShort()
            }
        }
        return blocks
    }

    /** The biomes of the [K] known blocks nearest [raw] numbers, weighted by nearness, as shares summing to 1. */
    private fun vote(raw: FloatArray): DoubleArray {
        val d = BiomeFeatures.SIZE
        val query = FloatArray(d) { (raw[it] - mean[it]) * scale[it] }
        val best = IntArray(K)
        val bestDistance = FloatArray(K) { Float.MAX_VALUE }
        val n = labels.size
        for (row in 0 until n) {
            val base = row * d
            var distance = 0f
            var j = 0
            val limit = bestDistance[K - 1]
            while (j < d) {
                val diff = points[base + j] - query[j]
                distance += diff * diff
                if (distance >= limit) break
                j++
            }
            if (distance >= limit) continue
            var p = K - 1
            while (p > 0 && bestDistance[p - 1] > distance) {
                bestDistance[p] = bestDistance[p - 1]
                best[p] = best[p - 1]
                p--
            }
            bestDistance[p] = distance
            best[p] = row
        }
        val votes = DoubleArray(names.size)
        var total = 0.0
        for (i in 0 until K) {
            if (bestDistance[i] == Float.MAX_VALUE) continue
            val weight = 1.0 / (1e-3 + Math.sqrt(bestDistance[i].toDouble()))
            votes[labels[best[i]].toInt()] += weight
            total += weight
        }
        if (total > 0) for (j in votes.indices) votes[j] /= total
        return votes
    }

    /**
     * Writes the guide in the form [read] reads: each number of each known block as one byte, in
     * 1/32nds of its spread, column by column, gzipped. About 200 KB for 12,000 blocks; tested,
     * rounding this way made no difference to the guesses.
     */
    fun write(output: OutputStream) {
        val d = BiomeFeatures.SIZE
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(output))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(d)
            out.writeInt(names.size)
            names.forEach(out::writeUTF)
            mean.forEach(out::writeFloat)
            for (j in 0 until d) out.writeFloat(BiomeFeatures.WEIGHTS[j] / scale[j]) // the spread
            out.writeInt(labels.size)
            labels.forEach { out.writeByte(it.toInt()) }
            for (j in 0 until d) {
                for (row in labels.indices) out.writeByte(quantise(points[row * d + j] / BiomeFeatures.WEIGHTS[j]))
            }
        }
    }

    val known: Int get() = labels.size
    val biomeCount: Int get() = names.size

    companion object {
        /** Blocks per guess, along each side. */
        const val CELL = 8
        const val CELLS = BlueMapPicture.REGION / CELL

        /** How many known blocks vote. */
        private const val K = 15

        /** Guesses this many cells away count towards a cell's own, less the further they are. */
        private const val SMOOTH = 2

        private const val MAGIC = 0x534D4231 // "SMB1"

        /** Where in a cell to look if its middle has nothing. */
        private val OFFSETS = listOf(0 to 0, -3 to -3, 3 to 3, -3 to 3, 3 to -3)

        private val loaded = ConcurrentHashMap<String, java.util.Optional<BiomeGuide>>()

        /** The guide that came with the mod for [dimensionId], or null if there is none for it. */
        fun forDimension(dimensionId: String): BiomeGuide? = loaded.computeIfAbsent(dimensionId) {
            // Only the overworld so far; the nether and the end keep their placeholder biome.
            val file = if (dimensionId == Config.OVERWORLD) "overworld" else null
            val guide = file?.let { name ->
                try {
                    BiomeGuide::class.java.getResourceAsStream("/skysmapexposer/biomes/$name.bin")?.use(::read)
                } catch (e: Exception) {
                    Log.error("Could not read the biome guide for $dimensionId", e)
                    null
                }
            }
            java.util.Optional.ofNullable(guide)
        }.orElse(null)

        fun read(input: InputStream): BiomeGuide =
            DataInputStream(BufferedInputStream(GZIPInputStream(input))).use { inp ->
                check(inp.readInt() == MAGIC) { "not a biome guide" }
                check(inp.readInt() == VERSION) { "a biome guide of another version" }
                val d = inp.readInt()
                check(d == BiomeFeatures.SIZE) { "biome guide made for $d numbers per block, not ${BiomeFeatures.SIZE}" }
                val names = List(inp.readInt()) { inp.readUTF() }
                val mean = FloatArray(d) { inp.readFloat() }
                val spread = FloatArray(d) { inp.readFloat() }
                val scale = FloatArray(d) { BiomeFeatures.WEIGHTS[it] / spread[it] }
                val n = inp.readInt()
                val labels = ShortArray(n) { inp.readUnsignedByte().toShort() }
                val points = FloatArray(n * d)
                for (j in 0 until d) {
                    for (row in 0 until n) points[row * d + j] = inp.readByte() / STEPS * BiomeFeatures.WEIGHTS[j]
                }
                BiomeGuide(names, mean, scale, labels, points)
            }

        /** A number already scaled to its spread, as one byte: 1/32nds of the spread, up to about 4 either way. */
        private fun quantise(z: Float): Int = Math.round(z * STEPS).coerceIn(-127, 127)

        private const val STEPS = 32f
        private const val VERSION = 1

        /**
         * A guide from known blocks: [samples] are raw [BiomeFeatures] and [biomes] their biomes.
         * Each number is scaled by its spread over the samples and its [BiomeFeatures.WEIGHTS].
         */
        fun build(samples: List<FloatArray>, biomes: List<String>): BiomeGuide {
            val d = BiomeFeatures.SIZE
            val names = biomes.distinct().sorted()
            check(names.size <= 255) { "too many biomes for one byte each" }
            val index = names.withIndex().associate { (i, name) -> name to i }
            val mean = FloatArray(d)
            val spread = DoubleArray(d)
            for (s in samples) for (j in 0 until d) mean[j] += s[j] / samples.size
            for (s in samples) for (j in 0 until d) spread[j] += ((s[j] - mean[j]) * (s[j] - mean[j])).toDouble() / samples.size
            val scale = FloatArray(d) { (BiomeFeatures.WEIGHTS[it] / (Math.sqrt(spread[it]) + 1e-9)).toFloat() }
            val points = FloatArray(samples.size * d)
            for ((row, s) in samples.withIndex()) {
                for (j in 0 until d) {
                    val z = (s[j] - mean[j]) * scale[j] / BiomeFeatures.WEIGHTS[j]
                    points[row * d + j] = quantise(z) / STEPS * BiomeFeatures.WEIGHTS[j]
                }
            }
            val labels = ShortArray(samples.size) { index.getValue(biomes[it]).toShort() }
            return BiomeGuide(names, mean, scale, labels, points)
        }
    }
}
