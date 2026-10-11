package com.skystormer.skysmapexposer.terrain

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainerFactory
import kotlin.math.abs
import kotlin.math.floor

/** A chunk rebuilt from BlueMap: its sections, and the light of each (index 0 = the lowest section). */
class BuiltChunk(
    val pos: ChunkPos,
    val minSectionY: Int,
    val sections: Array<LevelChunkSection>,
    val skyLight: Array<DataLayer>,
    val blockLight: Array<DataLayer>,
)

/**
 * Rebuilds chunks from the faces BlueMap drew over them.
 *
 * Every face belongs to one block: the one just behind it, so the face's centre nudged against its
 * normal lands in that block. That gives each block BlueMap shows, with every texture seen on it,
 * which [TextureBlocks] turns back into a block. It gives the whole visible surface, not just the
 * top: a farm in the sky keeps its floor and walls, and the ground under it stays at its own height.
 *
 * What BlueMap leaves out is filled in by what must be there:
 * - a block whose underside BlueMap did not draw is resting on something solid, so the column is
 *   filled below it, down to the next block that was drawn;
 * - water is filled from its surface down to the floor (kelp and the like stand in it);
 * - light is the light BlueMap drew on each face, given to the space the face looks into, with
 *   open sky above each column's top;
 * - biomes come from the tints of grass, leaves and water ([BiomePicker]).
 *
 * Works on one small area at a time ([build]), so memory stays small however far the fill goes.
 */
class ChunkRebuilder(
    private val blocks: TextureBlocks,
    private val biomes: BiomePicker,
    private val factory: PalettedContainerFactory,
    private val minY: Int,
    private val height: Int,
) {
    private class Voxel {
        val textures = HashSet<String>(4)
        var down = false
        var edge = false
        var yMin = 1f
        var yMax = 0f
        var grass = 0
        var foliage = 0
        var water = 0
    }

    /** One hires tile: its faces, and where its corner is in the world. */
    class Tile(val faces: HiresTile, val originX: Int, val originZ: Int)

    /**
     * Rebuilds [chunks] (all within a small area) from [tiles], whose texture numbers index
     * [textureNames]. Chunks BlueMap drew nothing in are left out.
     */
    fun build(chunks: List<ChunkPos>, tiles: List<Tile>, textureNames: List<String>): List<BuiltChunk> {
        if (chunks.isEmpty()) return emptyList()
        val minX = chunks.minOf { it.minBlockX }
        val maxX = chunks.maxOf { it.maxBlockX }
        val minZ = chunks.minOf { it.minBlockZ }
        val maxZ = chunks.maxOf { it.maxBlockZ }
        val voxels = Long2ObjectOpenHashMap<Voxel>()
        val light = Long2IntOpenHashMap()
        for (tile in tiles) read(tile, textureNames, minX, maxX, minZ, maxZ, voxels, light)
        return chunks.mapNotNull { chunk(it, voxels, light) }
    }

    private fun read(
        tile: Tile, names: List<String>, minX: Int, maxX: Int, minZ: Int, maxZ: Int,
        voxels: Long2ObjectOpenHashMap<Voxel>, light: Long2IntOpenHashMap,
    ) {
        val faces = tile.faces
        val n = FloatArray(3)
        val centre = FloatArray(3)
        for (t in 0 until faces.triangles) {
            val textureNumber = faces.texture(t)
            if (textureNumber !in names.indices) continue
            val texture = names[textureNumber]
            if (!texture.contains(":block/")) continue
            for (axis in 0..2) n[axis] = faces.normal(t, axis)
            centre[0] = (faces.x(t, 0) + faces.x(t, 1) + faces.x(t, 2)) / 3f + tile.originX
            centre[1] = (faces.y(t, 0) + faces.y(t, 1) + faces.y(t, 2)) / 3f
            centre[2] = (faces.z(t, 0) + faces.z(t, 1) + faces.z(t, 2)) / 3f + tile.originZ
            val bx = floor(centre[0] - n[0] * 0.01f).toInt()
            val by = floor(centre[1] - n[1] * 0.01f).toInt()
            val bz = floor(centre[2] - n[2] * 0.01f).toInt()
            if (bx < minX || bx > maxX || bz < minZ || bz > maxZ || by < minY || by >= minY + height) continue
            val key = pack(bx, by, bz)
            val voxel = voxels.get(key) ?: Voxel().also {
                // A real piece has tens of thousands; this many means tiles built to use up memory.
                if (voxels.size >= MAX_VOXELS) throw java.io.IOException("BlueMap's 3D tiles here have far more blocks than any real map")
                voxels.put(key, it)
            }
            voxel.textures += texture
            for (corner in 0..2) {
                val y = faces.y(t, corner) - by
                if (y < voxel.yMin) voxel.yMin = y
                if (y > voxel.yMax) voxel.yMax = y
            }
            // Faces square to an axis and lying on the block's edge tell what touches the block.
            var lightKey = key
            val axis = (0..2).maxBy { abs(n[it]) }
            if (abs(n[axis]) > 0.99f) {
                val origin = when (axis) { 0 -> bx; 1 -> by; else -> bz }
                val along = centre[axis] - origin
                if (abs(along) < 0.01f || abs(along - 1f) < 0.01f) {
                    voxel.edge = true
                    val step = if (n[axis] > 0) 1 else -1
                    lightKey = pack(bx + if (axis == 0) step else 0, by + if (axis == 1) step else 0, bz + if (axis == 2) step else 0)
                }
                if (axis == 1 && n[1] < 0) voxel.down = true
            }
            val tint = faces.color(t)
            val short = TextureBlocks.short(texture)
            when {
                short in GRASS && voxel.grass == 0 -> voxel.grass = tint
                short in FOLIAGE && voxel.foliage == 0 -> voxel.foliage = tint
                texture == TextureBlocks.WATER && voxel.water == 0 -> voxel.water = tint
            }
            // The space the face looks into: the next block for a face on the edge, the block's
            // own space for faces inside it (a water surface, a plant).
            val value = (faces.sunlight(t) shl 4) or faces.blocklight(t)
            val old = if (light.containsKey(lightKey)) light.get(lightKey) else -1
            light.put(lightKey, if (old < 0) value else (maxOf(old shr 4, value shr 4) shl 4) or maxOf(old and 15, value and 15))
        }
    }

    private fun chunk(pos: ChunkPos, voxels: Long2ObjectOpenHashMap<Voxel>, light: Long2IntOpenHashMap): BuiltChunk? {
        val states = arrayOfNulls<BlockState>(16 * 16 * height)
        fun index(x: Int, y: Int, z: Int) = ((y - minY) * 16 + z) * 16 + x
        val tints = arrayOfNulls<BiomePicker.Tints>(16)
        var anything = false
        for (x in 0 until 16) for (z in 0 until 16) {
            val wx = pos.minBlockX + x
            val wz = pos.minBlockZ + z
            var fill: BlockState? = null
            var last = minY + height
            for (y in minY + height - 1 downTo minY) {
                val voxel = voxels.get(pack(wx, y, wz)) ?: continue
                if (fill != null) for (fy in y + 1 until last) states[index(x, fy, z)] = fill
                last = y
                val half = voxel.edge && (voxel.yMax <= 0.51f || voxel.yMin >= 0.49f)
                val block = blocks.pick(voxel.textures, half) ?: continue
                anything = true
                val state = stateFor(block, voxel)
                states[index(x, y, z)] = state
                val cell = (z / 4) * 4 + x / 4
                val tint = tints[cell] ?: BiomePicker.Tints().also { tints[cell] = it }
                if (voxel.grass != 0) tint.grass(voxel.grass)
                if (voxel.foliage != 0) tint.foliage(voxel.foliage)
                if (voxel.water != 0) tint.water(voxel.water)
                fill = when {
                    block == Blocks.WATER || block == Blocks.LAVA -> block.defaultBlockState()
                    block in IN_WATER || state.getValueOrElse(BlockStateProperties.WATERLOGGED, false) -> Blocks.WATER.defaultBlockState()
                    voxel.edge && !voxel.down -> fillerFor(block, voxel)
                    else -> null
                }
            }
            if (fill != null) for (fy in minY until last) states[index(x, fy, z)] = fill
        }
        if (!anything) return null

        val sectionCount = height / 16
        val minSectionY = Math.floorDiv(minY, 16)
        val cellBiomes = arrayOfNulls<Holder<Biome>>(16)
        for (cell in 0 until 16) cellBiomes[cell] = tints[cell]?.let(biomes::pick)
        val fallback = cellBiomes.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: biomes.fallback
        val sections = Array(sectionCount) { s ->
            val blockStates = factory.createForBlockStates()
            val biomeStates = factory.createForBiomes()
            for (y in 0 until 16) for (z in 0 until 16) for (x in 0 until 16) {
                val state = states[index(x, minY + s * 16 + y, z)] ?: continue
                blockStates.getAndSetUnchecked(x, y, z, state)
            }
            for (y in 0 until 4) for (z in 0 until 4) for (x in 0 until 4) {
                biomeStates.getAndSetUnchecked(x, y, z, cellBiomes[z * 4 + x] ?: fallback)
            }
            LevelChunkSection(blockStates, biomeStates).also { it.recalcBlockCounts() }
        }

        // Light: open sky above each column's top, then what BlueMap drew.
        val sky = Array(sectionCount) { DataLayer() }
        val blockLight = Array(sectionCount) { DataLayer() }
        for (x in 0 until 16) for (z in 0 until 16) {
            var top = minY - 1
            for (y in minY + height - 1 downTo minY) if (states[index(x, y, z)] != null) { top = y; break }
            for (y in top + 1 until minY + height) sky[(y - minY) shr 4].set(x, (y - minY) and 15, z, 15)
            for (y in minY until minY + height) {
                val key = pack(pos.minBlockX + x, y, pos.minBlockZ + z)
                if (!light.containsKey(key)) continue
                val value = light.get(key)
                val s = (y - minY) shr 4
                val ly = (y - minY) and 15
                sky[s].set(x, ly, z, maxOf(sky[s].get(x, ly, z), value shr 4))
                blockLight[s].set(x, ly, z, value and 15)
            }
        }
        return BuiltChunk(pos, minSectionY, sections, sky, blockLight)
    }

    private fun stateFor(block: Block, voxel: Voxel): BlockState {
        var state = block.defaultBlockState()
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            val upper = voxel.textures.any { TextureBlocks.short(it).endsWith("_top") }
            state = state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, if (upper) DoubleBlockHalf.UPPER else DoubleBlockHalf.LOWER)
        }
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            val type = when {
                voxel.yMax <= 0.51f -> SlabType.BOTTOM
                voxel.yMin >= 0.49f -> SlabType.TOP
                else -> SlabType.DOUBLE
            }
            state = state.setValue(BlockStateProperties.SLAB_TYPE, type)
        }
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && TextureBlocks.WATER in voxel.textures && block != Blocks.WATER) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, true)
        }
        return state
    }

    /** What the hidden part of the column under [block] is most likely made of. */
    private fun fillerFor(block: Block, voxel: Voxel): BlockState {
        val name = BuiltInRegistries.BLOCK.getKey(block).path
        if (block in DIRT_TOPS || name.endsWith("_carpet")) return Blocks.DIRT.defaultBlockState()
        val whole = voxel.yMin <= 0.01f && voxel.yMax >= 0.99f
        if (whole && !name.endsWith("_stairs") && !name.endsWith("_slab") && !name.endsWith("_leaves")) return block.defaultBlockState()
        return Blocks.STONE.defaultBlockState()
    }

    private fun pack(x: Int, y: Int, z: Int): Long =
        ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

    companion object {
        private const val MAX_VOXELS = 400_000
        private val GRASS = setOf("grass_block_top", "short_grass", "tall_grass_top", "tall_grass_bottom", "fern", "large_fern_top")
        private val FOLIAGE = setOf("oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "vine")
        private val DIRT_TOPS = setOf(Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.DIRT_PATH, Blocks.FARMLAND, Blocks.SNOW, Blocks.MOSS_CARPET, Blocks.ROOTED_DIRT)
        private val IN_WATER = setOf(Blocks.KELP, Blocks.KELP_PLANT, Blocks.SEAGRASS, Blocks.TALL_SEAGRASS, Blocks.BUBBLE_COLUMN)
    }
}
