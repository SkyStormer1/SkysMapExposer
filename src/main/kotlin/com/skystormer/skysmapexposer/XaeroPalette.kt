package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import net.minecraft.client.color.block.BlockTintSources
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.Fluids
import xaero.lib.platform.ClientOnlyServices
import xaero.map.region.MapBlock

/**
 * Decides what a download writes for each of BlueMap's pixels: water or land, the biome, and the
 * block Xaero would draw closest to BlueMap's colour.
 *
 * Xaero colours a block from its texture, tinted by the biome for grass, leaves and water, so the
 * same green is a different block in a jungle than on a plain. Each biome therefore gets its own
 * palette: [BlockPalette.CANDIDATES], coloured the way Xaero colours them (its own texture colours,
 * times the biome's tint). Answers are remembered per biome, since BlueMap's colours repeat a lot.
 *
 * Built and used on the client thread only; it reads the level's registries and Xaero's block
 * colours.
 */
class XaeroPalette(private val level: ClientLevel, dimensionId: String) {

    /** What a download writes for one of BlueMap's pixels; see [decide]. */
    class Pixel {
        var water = false
        lateinit var biome: ResourceKey<Biome>
        /** The block, or under water the bed. */
        lateinit var state: BlockState
        /** Under water, how deep Xaero is told it is: the water layer's opacity. */
        var depth = 0
    }

    private class Candidate(val state: BlockState, val base: Int, val tint: Tint)

    /** How a block's texture is tinted: by a fixed colour, or by one of the biome's colours. */
    private sealed interface Tint {
        class Fixed(val rgb: Int) : Tint
        data object Grass : Tint
        data object Foliage : Tint
        data object DryFoliage : Tint
        data object Water : Tint
    }

    /** What a pixel may become in one biome: blocks, and the colour each is matched by. */
    private class Choices(val states: List<BlockState>, val labs: List<BlockPalette.Lab>) {
        val answers = HashMap<Int, BlockState>()
    }

    private val hasSea = dimensionId == Config.OVERWORLD
    private val placeholder = placeholderFor(dimensionId, water = false)
    private val waterPlaceholder = placeholderFor(dimensionId, water = true)

    private val candidates: List<Candidate> = BlockPalette.CANDIDATES.mapNotNull { id ->
        val block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id))
        if (block == Blocks.AIR) null else block.defaultBlockState().let { Candidate(it, baseColour(it), tintOf(it)) }
    }

    private val lookalikes: List<Pair<BlockPalette.Lookalike, BlockState>> = BlockPalette.LOOKALIKES.mapNotNull { look ->
        val block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(look.id))
        if (block == Blocks.AIR) null else look to block.defaultBlockState()
    }

    private val choices = HashMap<ResourceKey<Biome>, Choices>()
    private val stateColours = HashMap<BlockState, Pair<Int, Tint>>()
    private val keys = HashMap<String, ResourceKey<Biome>?>()
    private val biomes = HashMap<ResourceKey<Biome>, Biome?>()

    /**
     * What a download writes for BlueMap's [colour] at [height], into [into]: water or land, the
     * biome, and the block, or the bed and depth under water. [known] is the biome your map
     * recorded there or the guide's guess, if any.
     */
    fun decide(colour: Int, height: Int, known: ResourceKey<Biome>?, into: Pixel) {
        val water = isWater(colour, height)
        // Dark greenish water is swamp water only where the biome is known to be a swamp: deep
        // ocean and warm oceans are that colour too, and they are not two blocks deep over mud.
        val murky = water && isMurky(colour, height) && isSwampy(known)
        into.water = water
        into.biome = known ?: if (murky) Biomes.SWAMP else if (water) waterPlaceholder else placeholder
        if (!water) {
            into.depth = 0
            into.state = pick(colour, into.biome)
        } else if (murky) {
            into.depth = SWAMP_DEPTH
            into.state = Blocks.MUD.defaultBlockState()
        } else {
            // BlueMap gives only the surface, so the depth is read off how dark its water is:
            // sand in the shallows, gravel further out.
            into.depth = waterDepth(colour)
            into.state = (if (into.depth <= SHALLOW) Blocks.SAND else Blocks.GRAVEL).defaultBlockState()
        }
    }

    /**
     * The block for BlueMap's [colour] (0xRRGGBB, top byte ignored) in [biome]: the candidate Xaero
     * would draw nearest to it, or a [BlockPalette.Lookalike] of the biome that BlueMap draws so.
     */
    fun pick(colour: Int, biome: ResourceKey<Biome>): BlockState {
        val rgb = colour and 0xFFFFFF
        val here = choices.getOrPut(biome) {
            val id = biome.identifier().toString()
            val looks = lookalikes.filter { id in it.first.biomes }
            Choices(
                candidates.map { it.state } + looks.map { it.second },
                candidates.map { BlockPalette.toLab(tinted(it.base, it.tint, biome)) } + looks.map { BlockPalette.toLab(it.first.blueMapColour) },
            )
        }
        here.answers[rgb]?.let { return it }
        val index = BlockPalette.nearest(rgb, here.labs)
        val state = if (index >= 0) here.states[index] else Blocks.STONE.defaultBlockState()
        if (here.answers.size > MAX_ANSWERS) here.answers.clear()
        here.answers[rgb] = state
        return state
    }

    /**
     * Whether BlueMap's [colour] is water. BlueMap marks water no other way: its lowres tiles are a
     * colour and the height of the surface. Water is the one common thing on a map that is clearly
     * blue without being bright, which in Oklab is b below [WATER_BLUE] and lightness below
     * [WATER_LIGHT]; ice, packed ice and snow are lighter than that. Checked against a real
     * server's tiles: it finds oceans, rivers and ponds, and misses nothing but a few blue blocks
     * on buildings.
     *
     * Warm and lukewarm oceans are the exception: their water is tinted teal, and over sand BlueMap
     * draws it a light blue-green that is barely blue at all. So at the sea's surface ([height] at
     * most [SEA_TOP], and only in a dimension with a sea) anything greenish-blue counts too: a below
     * [SEA_GREEN] and b below [SEA_BLUE]. Land at sea level is sand, gravel or stone, which are not
     * green, or grass and leaves, which are yellower than that.
     */
    fun isWater(colour: Int, height: Int): Boolean {
        val lab = BlockPalette.toLab(colour and 0xFFFFFF)
        return isWater(lab.l, lab.a, lab.b, height, hasSea)
    }

    /**
     * Whether BlueMap's [colour] is swamp water: at the sea's surface, dark, faintly green and
     * greyish but not blue, from swamp water's olive tint over a mud bed. Swamp grass and the
     * leaves over it are yellower (b above [MURK_BLUE]); mud and stone are not green at all.
     */
    private fun isMurky(colour: Int, height: Int): Boolean {
        val lab = BlockPalette.toLab(colour and 0xFFFFFF)
        return isMurky(lab.l, lab.a, lab.b, height, hasSea)
    }

    /**
     * Whether water in [biome] is swamp water, drawn shallow over mud. Not where the biome is
     * unknown: far out at sea the guide often has no guess, and deep ocean is as dark and faintly
     * green as swamp water.
     */
    private fun isSwampy(biome: ResourceKey<Biome>?): Boolean = biome == Biomes.SWAMP || biome == Biomes.MANGROVE_SWAMP

    /**
     * How deep BlueMap's water [colour] looks, 1 to 15 blocks. BlueMap draws the bed through the
     * water, so shallows come out lighter and deep sea darker: on a real server from about 0.56
     * lightness at the shore to 0.42 in open ocean. 15 is as deep as Xaero's water layer counts.
     */
    private fun waterDepth(colour: Int): Int =
        ((SHORE_LIGHT - BlockPalette.toLab(colour and 0xFFFFFF).l) / LIGHT_PER_BLOCK).toInt().coerceIn(1, 15)

    /** The biome named [id], if this server has it; remembered, as the same few names repeat. */
    fun biomeKey(id: String): ResourceKey<Biome>? = keys.getOrPut(id) {
        val key = try {
            ResourceKey.create(Registries.BIOME, Identifier.parse(id))
        } catch (e: Exception) {
            null
        }
        key?.takeIf { biome(it) != null }
    }

    /**
     * Whether the pixel [old] from your own map already shows what BlueMap does, in which case it
     * is better left alone: it is a real block, where the one written would only be a lookalike.
     * Water matches water of any depth (yours knows the real bed and depth, BlueMap does not), and
     * water never matches land or the other way round. Land is compared by the colour Xaero would
     * draw.
     */
    fun stillMatches(old: MapBlock, water: Boolean, colour: Int, biome: ResourceKey<Biome>): Boolean {
        val state = old.state ?: return false
        val overlays = old.overlays
        val oldIsWater = state.fluidState.`is`(Fluids.WATER) ||
            (!overlays.isNullOrEmpty() && overlays[0].state?.fluidState?.`is`(Fluids.WATER) == true)
        if (water || oldIsWater) return water && oldIsWater
        if (state.isAir && old.numberOfOverlays == 0) return false
        if (!overlays.isNullOrEmpty()) return false
        val (base, tint) = stateColours.getOrPut(state) { baseColour(state) to tintOf(state) }
        val drawn = BlockPalette.toLab(tinted(base, tint, biome))
        return BlockPalette.distance(drawn, BlockPalette.toLab(colour and 0xFFFFFF)) < KEEP
    }

    private fun baseColour(state: BlockState): Int = try {
        val colour = ClientOnlyServices.PLATFORM.blockUtils.textureColorUtils
            .getBlockTextureColor(state, true, level, level.registryAccess().lookupOrThrow(Registries.BLOCK), ORIGIN)
        if (colour and 0xFFFFFF == 0) state.getMapColor(level, ORIGIN).col else colour and 0xFFFFFF
    } catch (e: Throwable) {
        state.getMapColor(level, ORIGIN).col
    }

    /**
     * How the game tints [state], read from its own block colour table, which is what Xaero draws
     * with: no tint, a fixed colour (spruce and birch leaves, lily pads), or the biome's grass,
     * foliage, dry foliage or water colour. Guessing from the model is not enough: cherry and pale
     * oak leaves have tinted models but no tint, so they would be counted green and drawn pink and
     * grey.
     */
    private fun tintOf(state: BlockState): Tint {
        val source = try {
            Minecraft.getInstance().blockColors.getTintSource(state, 0)
        } catch (e: Throwable) {
            null
        } ?: return NO_TINT
        return when (source.javaClass) {
            in GRASS_SOURCES -> Tint.Grass
            FOLIAGE_SOURCE -> Tint.Foliage
            DRY_FOLIAGE_SOURCE -> Tint.DryFoliage
            WATER_SOURCE -> Tint.Water
            else -> Tint.Fixed(source.color(state) and 0xFFFFFF)
        }
    }

    /** [base] times the biome's tint, channel by channel, as Xaero does it. */
    private fun tinted(base: Int, tint: Tint, key: ResourceKey<Biome>): Int {
        val by = when (tint) {
            is Tint.Fixed -> if (tint === NO_TINT) return base else tint.rgb
            Tint.Grass -> biome(key)?.getGrassColor(0.0, 0.0)
            Tint.Foliage -> biome(key)?.foliageColor
            Tint.DryFoliage -> biome(key)?.dryFoliageColor
            Tint.Water -> biome(key)?.waterColor
        } ?: return base
        val r = ((by shr 16) and 0xFF) * ((base shr 16) and 0xFF) / 255
        val g = ((by shr 8) and 0xFF) * ((base shr 8) and 0xFF) / 255
        val b = (by and 0xFF) * (base and 0xFF) / 255
        return (r shl 16) or (g shl 8) or b
    }

    private fun biome(key: ResourceKey<Biome>): Biome? = biomes.getOrPut(key) {
        try {
            level.registryAccess().lookupOrThrow(Registries.BIOME).getValue(key)
        } catch (e: Throwable) {
            null
        }
    }

    companion object {
        private val ORIGIN: BlockPos = BlockPos(0, 64, 0)

        private val NO_TINT = Tint.Fixed(0xFFFFFF)

        // The game makes a new tint source per call, so they are told apart by class.
        private val GRASS_SOURCES = listOf(BlockTintSources.grass(), BlockTintSources.grassBlock(), BlockTintSources.doubleTallGrass()).map { it.javaClass }
        private val FOLIAGE_SOURCE = BlockTintSources.foliage().javaClass
        private val DRY_FOLIAGE_SOURCE = BlockTintSources.dryFoliage().javaClass
        private val WATER_SOURCE = BlockTintSources.water().javaClass

        /** [isWater] for a colour already in Oklab; also used off the client thread by [BiomeGuide]. */
        fun isWater(l: Float, a: Float, b: Float, height: Int, hasSea: Boolean): Boolean {
            if (l >= WATER_LIGHT) return false
            if (b < WATER_BLUE) return true
            return hasSea && height <= SEA_TOP && a < SEA_GREEN && b < SEA_BLUE || isMurky(l, a, b, height, hasSea)
        }

        /**
         * [isMurky] for a colour already in Oklab. Clearly blue water is never murky: dark ocean
         * is as dark and as faintly green as swamp water, and only swamp water is not blue.
         */
        fun isMurky(l: Float, a: Float, b: Float, height: Int, hasSea: Boolean): Boolean =
            hasSea && height <= SEA_TOP && l < MURK_LIGHT && a < MURK_GREEN && b >= WATER_BLUE && b < MURK_BLUE

        /**
         * The biome written where your map never recorded one and [BiomeGuide] has no guess (the
         * nether and the end, for now). BlueMap publishes no biomes, so this is the last resort: the
         * plainest biome of each dimension, which tints grass and leaves the way most people picture
         * them, and ocean for overworld water.
         */
        private fun placeholderFor(dimensionId: String, water: Boolean): ResourceKey<Biome> = when (dimensionId) {
            Config.NETHER -> Biomes.NETHER_WASTES
            Config.END -> Biomes.THE_END
            else -> if (water) Biomes.OCEAN else Biomes.PLAINS
        }

        /** Colours remembered per biome before the memory is cleared and starts again. */
        private const val MAX_ANSWERS = 65_536

        /**
         * How close, in squared Oklab distance, your own pixel's colour has to be to BlueMap's to be
         * kept. About the difference between two neighbouring shades of the same grass.
         */
        private const val KEEP = 0.0025f

        private const val SWAMP_DEPTH = 2
        /** Water this deep or shallower is written over sand, deeper over gravel. */
        private const val SHALLOW = 3
        private const val SHORE_LIGHT = 0.56f
        private const val LIGHT_PER_BLOCK = 0.01f

        private const val WATER_BLUE = -0.035f
        private const val WATER_LIGHT = 0.74f
        /** The overworld's water surface block, one below sea level 63. */
        private const val SEA_TOP = 62
        private const val SEA_GREEN = -0.015f
        private const val SEA_BLUE = 0.03f
        private const val MURK_LIGHT = 0.6f
        private const val MURK_GREEN = -0.003f
        private const val MURK_BLUE = 0.045f
    }
}
