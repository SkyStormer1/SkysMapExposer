package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.multiplyColour
import com.skystormer.skysmapexposer.SharedTerrain
import com.skystormer.skysmapexposer.journeymap.JourneyMapFiles.REGION
import journeymap.client.model.block.BlockFlag
import journeymap.client.model.block.BlockMD
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.state.BlockState

/**
 * A shared map file's region ([SharedTerrain]) as JourneyMap's day map would show it, so it sits in
 * JourneyMap's map looking like the rest: each column's top block in JourneyMap's own colour for it
 * ([BlockMD]), tinted by its biome where JourneyMap tints that block (grass, foliage, water), lit by
 * the slope to its north as JourneyMap shades the surface, with water and other see-through layers
 * laid over the ground beneath, deeper water darker.
 *
 * The file keeps every block as Xaero records it (block, height, biome, light, layers over it), so
 * the picture is made from those, not copied from another map's colours.
 */
internal object JourneyMapShared {

    /** [region]'s picture, `REGION` square ARGB, see-through where the file has nothing. Off the client thread. */
    fun picture(region: SharedTerrain.Region): IntArray {
        val out = IntArray(REGION * REGION)
        val biomes = HashMap<ResourceKey<Biome>, Biome?>()
        val registry = Minecraft.getInstance().level?.registryAccess()?.lookupOrThrow(Registries.BIOME)
        fun biome(key: ResourceKey<Biome>?): Biome? = key?.let { k -> biomes.getOrPut(k) { registry?.getValue(k) } }
        fun heightAt(px: Int, pz: Int): Int? {
            if (px !in 0 until REGION || pz !in 0 until REGION) return null
            val chunk = region.chunks[(pz shr 4) * 32 + (px shr 4)] ?: return null
            return chunk.height[(px and 15) * 16 + (pz and 15)].toInt()
        }
        for (chunkIndex in 0 until 32 * 32) {
            val chunk = region.chunks[chunkIndex] ?: continue
            val chunkX = chunkIndex and 31
            val chunkZ = chunkIndex shr 5
            for (x in 0 until 16) for (z in 0 until 16) {
                val i = x * 16 + z
                val px = chunkX * 16 + x
                val pz = chunkZ * 16 + z
                val state = chunk.state[i] ?: continue
                val biome = biome(chunk.biome[i])
                val ground = colourOf(state, biome)
                // A floor JourneyMap leaves off (an underwater plant, say) still has the water over it.
                if (ground == null && chunk.overlays[i].isNullOrEmpty()) continue
                var colour = ground ?: 0
                // The slope to the north and west, as JourneyMap's surface is shaded.
                val height = chunk.height[i].toInt()
                val north = heightAt(px, pz - 1) ?: height
                val west = heightAt(px - 1, pz) ?: height
                colour = shade(colour, 1f + ((height - north) + (height - west)).coerceIn(-6, 6) * SLOPE)
                chunk.overlays[i]?.let { layers ->
                    val depth = (chunk.top[i] - chunk.height[i]).coerceAtLeast(1)
                    // Kelp and seagrass reach up near the surface; JourneyMap shows the water over them.
                    val plantBelow = BlockMD.get(state).hasFlag(BlockFlag.Plant)
                    for (layer in layers) {
                        val md = BlockMD.get(layer.state)
                        // Kelp and seagrass stand in the water as layers of their own; JourneyMap shows the water.
                        if (md.hasFlag(BlockFlag.Plant)) continue
                        val over = colourOf(layer.state, biome) ?: continue
                        val alpha = when {
                            ground == null -> 1f
                            !md.isWater -> md.alpha.coerceIn(0.2f, 1f)
                            plantBelow -> WATER_MAX
                            else -> (WATER_ALPHA + depth * WATER_DEPTH).coerceAtMost(WATER_MAX)
                        }
                        colour = blend(colour, over, alpha)
                    }
                }
                out[pz * REGION + px] = colour or OPAQUE
            }
        }
        return out
    }

    /** [state]'s colour in JourneyMap before any biome tint, 0xRRGGBB; 0 for one it leaves off the map. */
    fun baseColour(state: BlockState): Int {
        if (state.isAir) return 0
        val md = BlockMD.get(state)
        if (md.isIgnore) return 0
        // JourneyMap works out a block's colour from its texture the first time it maps one; one it
        // has not met yet has none, and takes the game's own map colour for it instead.
        return (if (md.hasColor()) md.textureColor else 0).and(0xFFFFFF).takeIf { it != 0 }
            ?: state.block.defaultMapColor().col
    }

    /** [state]'s colour in JourneyMap, tinted by [biome] where JourneyMap tints it; null for a block it leaves off the map. */
    private fun colourOf(state: BlockState, biome: Biome?): Int? {
        val texture = baseColour(state).takeIf { it != 0 } ?: return null
        val md = BlockMD.get(state)
        val tint = when {
            md.isWater -> biome?.waterColor ?: DEFAULT_WATER
            md.isGrass -> biome?.getGrassColor(0.0, 0.0) ?: DEFAULT_GRASS
            md.isFoliage -> biome?.foliageColor ?: DEFAULT_FOLIAGE
            else -> return texture
        }
        return multiplyColour(texture, tint and 0xFFFFFF)
    }

    private fun shade(colour: Int, factor: Float): Int {
        fun channel(shift: Int) = (((colour shr shift) and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun blend(under: Int, over: Int, alpha: Float): Int {
        fun channel(shift: Int) = (((under shr shift) and 0xFF) * (1 - alpha) + ((over shr shift) and 0xFF) * alpha).toInt().coerceIn(0, 255)
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private const val OPAQUE = 0xFF000000.toInt()
    private const val SLOPE = 0.07f
    private const val WATER_ALPHA = 0.55f
    private const val WATER_DEPTH = 0.04f
    private const val WATER_MAX = 0.9f

    /** The plains' colours, for a column whose biome the game does not know. */
    private const val DEFAULT_GRASS = 0x91BD59
    private const val DEFAULT_FOLIAGE = 0x77AB2F
    private const val DEFAULT_WATER = 0x3F76E4
}
