package com.skystormer.skysmapexposer.terrain

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.tags.BiomeTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome

/**
 * Guesses a biome from the tints BlueMap drew: grass first, then leaves, then water, each matched
 * to the nearest biome's colour. Only the biomes of the dimension being filled are candidates, and
 * water alone picks among the oceans and swamps (most biomes share one water colour).
 *
 * Built on the client thread from the level's own biomes, so it matches what the game will draw.
 */
class BiomePicker private constructor(
    private val candidates: List<Candidate>,
    private val waterOnly: List<Candidate>,
    /** For chunks with no tints at all: the biome where you stand. */
    val fallback: Holder<Biome>,
) {
    private class Candidate(val biome: Holder<Biome>, val grass: Int, val foliage: Int, val water: Int)

    /** Tints seen in one 4×4 column of a chunk. */
    class Tints {
        private val sums = LongArray(9)
        private val counts = IntArray(3)
        fun grass(rgb: Int) = add(0, rgb)
        fun foliage(rgb: Int) = add(1, rgb)
        fun water(rgb: Int) = add(2, rgb)
        private fun add(kind: Int, rgb: Int) {
            sums[kind * 3] += (rgb shr 16 and 0xFF).toLong()
            sums[kind * 3 + 1] += (rgb shr 8 and 0xFF).toLong()
            sums[kind * 3 + 2] += (rgb and 0xFF).toLong()
            counts[kind]++
        }
        fun count(kind: Int) = counts[kind]
        fun average(kind: Int): Int {
            val c = counts[kind]
            return ((sums[kind * 3] / c).toInt() shl 16) or ((sums[kind * 3 + 1] / c).toInt() shl 8) or (sums[kind * 3 + 2] / c).toInt()
        }
    }

    fun pick(tints: Tints): Holder<Biome>? {
        for (kind in 0..2) {
            if (tints.count(kind) == 0) continue
            val colour = tints.average(kind)
            val list = if (kind == 2) waterOnly.ifEmpty { candidates } else candidates
            return list.minByOrNull { distance(colour, when (kind) { 0 -> it.grass; 1 -> it.foliage; else -> it.water }) }?.biome
        }
        return null
    }

    private fun distance(a: Int, b: Int): Int {
        val r = (a shr 16 and 0xFF) - (b shr 16 and 0xFF)
        val g = (a shr 8 and 0xFF) - (b shr 8 and 0xFF)
        val bl = (a and 0xFF) - (b and 0xFF)
        return r * r + g * g + bl * bl
    }

    companion object {
        fun build(level: ClientLevel, standing: Holder<Biome>): BiomePicker {
            val registry = level.registryAccess().lookupOrThrow(Registries.BIOME)
            val tag = when (level.dimension()) {
                Level.NETHER -> BiomeTags.IS_NETHER
                Level.END -> BiomeTags.IS_END
                else -> BiomeTags.IS_OVERWORLD
            }
            val all = registry.listElements().toList()
            val inDimension = all.filter { it.`is`(tag) }.ifEmpty { all }
            val candidates = inDimension.map { holder ->
                val biome = holder.value()
                Candidate(holder, biome.getGrassColor(0.0, 0.0) and 0xFFFFFF, biome.foliageColor and 0xFFFFFF, biome.waterColor and 0xFFFFFF)
            }
            val watery = candidates.filter { c ->
                c.biome.`is`(BiomeTags.IS_OCEAN) || c.biome.unwrapKey().map { it.identifier().path.contains("swamp") }.orElse(false)
            }
            return BiomePicker(candidates, watery, standing)
        }
    }
}
