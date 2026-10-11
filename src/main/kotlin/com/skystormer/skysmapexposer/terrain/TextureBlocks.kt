package com.skystormer.skysmapexposer.terrain

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.util.RandomSource
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf

/**
 * Which block a face's texture came from, worked out from the game's own block models: every
 * block's model is asked which textures it uses, so a set of textures seen on one block's faces
 * points back at the block. Built once per fill, on the client thread (it reads the loaded models).
 *
 * Ties go to the block named like the texture (`oak_planks` → oak planks, not oak stairs), and a
 * block only half a block high prefers a slab.
 */
class TextureBlocks private constructor(
    private val textures: Map<Block, Set<String>>,
    private val byTexture: Map<String, List<Block>>,
) {
    private val names = HashMap<Block, String>()
    private val picked = HashMap<String, Block?>()

    /** The block whose textures best match [faces] (texture names such as `minecraft:block/stone`), or null. */
    fun pick(faces: Set<String>, half: Boolean): Block? {
        var seen = faces
        // Something standing in water: the water is not the block.
        if (seen.size > 1 && WATER in seen) seen = seen - WATER - WATER_FLOW
        val key = seen.sorted().joinToString(",") + if (half) "|h" else ""
        picked[key]?.let { return it }
        if (picked.containsKey(key)) return null
        val candidates = HashSet<Block>()
        for (texture in seen) byTexture[texture]?.let(candidates::addAll)
        val stems = seen.map(::stem).toSet() + seen.map(::short)
        val best = candidates.maxWithOrNull(
            compareBy<Block> { block -> textures[block]!!.count { it in seen } }
                .thenBy { block -> half && name(block).endsWith("_slab") }
                .thenBy { block -> name(block) in stems }
                .thenBy { block -> seen.maxOf { commonPrefix(name(block), short(it)) } }
                .thenBy { block -> -textures[block]!!.size }
                .thenBy { block -> name(block) }
        )
        picked[key] = best
        return best
    }

    private fun name(block: Block): String = names.getOrPut(block) { BuiltInRegistries.BLOCK.getKey(block).path }

    companion object {
        const val WATER = "minecraft:block/water_still"
        const val WATER_FLOW = "minecraft:block/water_flow"
        private val SUFFIXES = listOf("_still", "_flow", "_top", "_bottom", "_side", "_front", "_end", "_overlay", "_inner", "_outer")

        /** `minecraft:block/oak_log_top` → `oak_log_top`. */
        fun short(texture: String): String = texture.substringAfter(':').removePrefix("block/")

        fun stem(texture: String): String {
            val name = short(texture)
            for (suffix in SUFFIXES) if (name.endsWith(suffix)) return name.removeSuffix(suffix)
            return name
        }

        private fun commonPrefix(a: String, b: String): Int {
            var n = 0
            while (n < a.length && n < b.length && a[n] == b[n]) n++
            return n
        }

        /** Reads every block's model. Call on the client thread. */
        fun build(minecraft: Minecraft): TextureBlocks {
            val models = minecraft.modelManager.blockStateModelSet
            val random = RandomSource.create(42L)
            val parts = ArrayList<BlockStateModelPart>()
            val textures = HashMap<Block, Set<String>>()
            for (block in BuiltInRegistries.BLOCK) {
                if (block == Blocks.AIR || block == Blocks.CAVE_AIR || block == Blocks.VOID_AIR) continue
                val states = mutableListOf(block.defaultBlockState())
                if (block.defaultBlockState().hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
                    states += block.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
                }
                val found = HashSet<String>()
                for (state in states) {
                    val model = models.get(state)
                    parts.clear()
                    model.collectParts(random, parts)
                    var quads = 0
                    for (part in parts) {
                        for (direction in DIRECTIONS) {
                            for (quad in part.getQuads(direction)) {
                                found += quad.materialInfo().sprite().contents().name().toString()
                                quads++
                            }
                        }
                    }
                    // Water, lava and the like draw no quads of their own; their particle is their texture.
                    if (quads == 0) found += model.particleMaterial().sprite().contents().name().toString()
                }
                found.removeIf { it.endsWith("missingno") }
                if (found.isNotEmpty()) textures[block] = found
            }
            val byTexture = HashMap<String, MutableList<Block>>()
            for ((block, set) in textures) for (texture in set) byTexture.getOrPut(texture) { ArrayList() } += block
            return TextureBlocks(textures, byTexture)
        }

        private val DIRECTIONS: Array<Direction?> = arrayOf(*Direction.entries.toTypedArray(), null)
    }
}
