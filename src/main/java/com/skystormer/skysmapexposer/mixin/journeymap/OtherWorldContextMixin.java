package com.skystormer.skysmapexposer.mixin.journeymap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.skystormer.skysmapexposer.journeymap.JourneyMapOtherWorld;
import journeymap.client.data.DataCache;
import journeymap.client.model.chunk.ChunkMD;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While JourneyMap's renderers draw a dimension you are not in ({@link JourneyMapOtherWorld}), a
 * chunk's neighbours (for slopes and contour lines at its edges) come from that dimension's chunks,
 * not from the loaded chunks of yours at the same place. Optional (require = 0).
 */
@Mixin(targets = "journeymap.client.model.chunk.ChunkRenderContext", remap = false)
public abstract class OtherWorldContextMixin {

    @WrapOperation(method = "getChunkMD", at = @At(value = "INVOKE", target = "Ljourneymap/client/data/DataCache;getChunkMD(J)Ljourneymap/client/model/chunk/ChunkMD;"))
    private ChunkMD skysmapexposer$otherWorldNeighbour(DataCache cache, long packed, Operation<ChunkMD> original) {
        return JourneyMapOtherWorld.active() ? JourneyMapOtherWorld.neighbour(packed) : original.call(cache, packed);
    }
}
