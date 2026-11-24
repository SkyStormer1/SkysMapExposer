package com.skystormer.skysmapexposer.xaero

import com.skystormer.skysmapexposer.BlockGuesser
import com.skystormer.skysmapexposer.BlockPalette
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.material.Fluids
import xaero.lib.platform.ClientOnlyServices
import xaero.map.region.MapBlock

/**
 * The download guesser ([BlockGuesser]) with blocks coloured as Xaero colours them: its own texture
 * colours, times the biome's tint. And whether a pixel of your own map already shows what BlueMap
 * does ([stillMatches]), so a download leaves it alone.
 *
 * Built and used on the client thread only; it reads the level's registries and Xaero's block
 * colours.
 */
class XaeroPalette(level: ClientLevel, dimensionId: String) : BlockGuesser(level.registryAccess(), dimensionId, { state ->
    try {
        val colour = ClientOnlyServices.PLATFORM.blockUtils.textureColorUtils
            .getBlockTextureColor(state, true, level, level.registryAccess().lookupOrThrow(Registries.BLOCK), ORIGIN)
        if (colour and 0xFFFFFF == 0) state.getMapColor(level, ORIGIN).col else colour and 0xFFFFFF
    } catch (e: Throwable) {
        state.getMapColor(level, ORIGIN).col
    }
}) {

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
        val drawn = BlockPalette.toLab(drawnColour(state, biome))
        return BlockPalette.distance(drawn, BlockPalette.toLab(colour and 0xFFFFFF)) < KEEP
    }

    companion object {
        private val ORIGIN: BlockPos = BlockPos(0, 64, 0)

        /**
         * How close, in squared Oklab distance, your own pixel's colour has to be to BlueMap's to be
         * kept. About the difference between two neighbouring shades of the same grass.
         */
        private const val KEEP = 0.0025f
    }
}
