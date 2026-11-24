package com.skystormer.skysmapexposer

import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome

/**
 * The biomes picked to tint on the map, for either map: each with its own see-through colour, in the
 * order they were picked. Client thread only.
 */
object BiomePicks {

    private val picked = LinkedHashMap<ResourceKey<Biome>, Int>()

    /** Bumped whenever the picks change, so whatever was worked out from them is worked out again. */
    var version = 0
        private set

    val any: Boolean get() = picked.isNotEmpty()

    /** The picks and their colours, in the order they were picked. */
    val all: Map<ResourceKey<Biome>, Int> get() = picked

    fun isPicked(biome: ResourceKey<Biome>): Boolean = biome in picked

    /** The tint of [biome], or null if it is not picked. */
    fun colourOf(biome: ResourceKey<Biome>): Int? = picked[biome]

    fun toggle(biome: ResourceKey<Biome>) {
        if (picked.remove(biome) == null) picked[biome] = nextColour()
        version++
    }

    fun clear() {
        if (picked.isEmpty()) return
        picked.clear()
        version++
    }

    /**
     * The biome's name in the game's language, as its own screens show it; one this version has no
     * name for is named from its id (`dappled_forest` is "Dappled Forest").
     */
    fun nameOf(key: ResourceKey<Biome>): String {
        val languageKey = key.identifier().toLanguageKey("biome")
        if (Language.getInstance().has(languageKey)) return Component.translatable(languageKey).string
        return key.identifier().path.split('_').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }

    /** The first colour of [COLOURS] not in use, or the least used once all are. */
    private fun nextColour(): Int {
        val used = picked.values.groupingBy { it }.eachCount()
        return COLOURS.minBy { used[it] ?: 0 }
    }

    /** See-through tints that stand apart from each other and from terrain's greens and blues. */
    private val COLOURS = intArrayOf(
        0x90FF3B30.toInt(), 0x90FFCC00.toInt(), 0x90FF2DAA.toInt(), 0x90AF52DE.toInt(),
        0x90FF9500.toInt(), 0x9000E5FF.toInt(), 0x90FFFFFF.toInt(), 0x9034C759.toInt(),
    )
}
