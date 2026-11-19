package com.skystormer.skysmapexposer.mixin.journeymap;

import com.llamalad7.mixinextras.sugar.Local;
import com.skystormer.skysmapexposer.journeymap.JourneyMapImportedBiomes;
import journeymap.client.model.map.MapType;
import journeymap.client.render.map.Renderer;
import journeymap.common.helper.BiomeHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * JourneyMap's line under the mouse on the full-screen map names the biome only where it knows the
 * block too, which it does not for a downloaded or shared map's area: there {@link SnapshotBiomeMixin}
 * is never asked, and the name is left empty. Where it is empty, the biome this mod kept
 * ({@link JourneyMapImportedBiomes}) is named instead, on the surface maps. Optional (require = 0).
 */
@Mixin(targets = "journeymap.client.ui.fullscreen.layer.BlockInfoLayer", remap = false)
public abstract class BlockInfoBiomeMixin {

    @ModifyArg(
        method = "buildBlockInfo",
        at = @At(value = "INVOKE",
            target = "Ljourneymap/client/ui/fullscreen/layer/BlockInfoLayer;getBlockInfo(Lnet/minecraft/core/BlockPos;Ljava/lang/String;Ljourneymap/client/model/region/RegionCoord;)Ljava/lang/String;"),
        index = 1, require = 0)
    private String skysmapexposer$keptBiome(String biomeName, @Local(argsOnly = true) BlockPos pos, @Local(argsOnly = true) Renderer renderer) {
        if (biomeName != null && !biomeName.isEmpty() || pos == null || renderer == null) return biomeName;
        try {
            MapType type = renderer.getMapType();
            if (type == null || type.isUnderground()) return biomeName;
            Biome kept = JourneyMapImportedBiomes.biomeFor(type.dimension, pos);
            return kept != null ? BiomeHelper.getTranslatedBiomeName(kept) : biomeName;
        } catch (RuntimeException | LinkageError e) {
            // A lookup that fails leaves JourneyMap's answer: no biome.
            return biomeName;
        }
    }
}
