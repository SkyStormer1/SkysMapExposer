package com.skystormer.skysmapexposer.mixin.journeymap;

import com.skystormer.skysmapexposer.journeymap.JourneyMapImportedBiomes;
import journeymap.client.model.map.MapType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * JourneyMap's line under the mouse names the biome from its chunk cache, and none where it has no
 * chunk. Where a shared map file was added there, its biome ({@link JourneyMapImportedBiomes}) is
 * named instead, on the surface maps. Optional (require = 0): without it the line names none there.
 */
@Mixin(targets = "journeymap.client.data.SnapshotBlockDataAccessor", remap = false)
public abstract class SnapshotBiomeMixin {

    @Inject(method = "getBiome(Lnet/minecraft/core/BlockPos;Ljourneymap/client/model/map/MapType;)Lnet/minecraft/world/level/biome/Biome;",
        at = @At("RETURN"), cancellable = true)
    private void skysmapexposer$sharedBiome(BlockPos pos, MapType type, CallbackInfoReturnable<Biome> info) {
        if (info.getReturnValue() != null || type == null || type.isUnderground() || pos == null) return;
        try {
            Biome shared = JourneyMapImportedBiomes.biomeFor(type.dimension, pos);
            if (shared != null) info.setReturnValue(shared);
        } catch (RuntimeException | LinkageError e) {
            // A lookup that fails leaves JourneyMap's answer: no biome.
        }
    }
}
