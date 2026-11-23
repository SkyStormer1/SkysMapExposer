package com.skystormer.skysmapexposer.mixin.journeymap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.skystormer.skysmapexposer.journeymap.JourneyMapOtherWorld;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * JourneyMap's topo map spreads its colours over the world's height: while it draws a dimension
 * you are not in ({@link JourneyMapOtherWorld}), that dimension's height. Optional (require = 0).
 */
@Mixin(targets = "journeymap.client.cartography.render.TopoRenderer", remap = false)
public abstract class OtherWorldTopoMixin {

    @WrapOperation(method = "updateOptions", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getMaxY()I"))
    private int skysmapexposer$otherWorldTop(ClientLevel level, Operation<Integer> original) {
        DimensionType type = JourneyMapOtherWorld.type();
        return type == null ? original.call(level) : type.minY() + type.height() - 1;
    }
}
