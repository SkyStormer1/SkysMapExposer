package com.skystormer.skysmapexposer.mixin.journeymap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.skystormer.skysmapexposer.journeymap.JourneyMapOtherWorld;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A chunk read from JourneyMap's cache of a dimension you are not in ({@link JourneyMapOtherWorld})
 * has that dimension's number of sections, not that of the world you are in (16 in the Nether, 24
 * in the Overworld). Optional (require = 0).
 */
@Mixin(targets = "journeymap.common.chunk.JMChunkSnapshot", remap = false)
public abstract class OtherWorldSnapshotMixin {

    @WrapOperation(method = "readBinary", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getSectionsCount()I"))
    private static int skysmapexposer$otherWorldSections(Level level, Operation<Integer> original) {
        DimensionType type = JourneyMapOtherWorld.type();
        return type == null ? original.call(level) : type.height() >> 4;
    }
}
