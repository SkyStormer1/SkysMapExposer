package com.skystormer.skysmapexposer.mixin.journeymap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.skystormer.skysmapexposer.journeymap.JourneyMapDimension;
import journeymap.client.ui.fullscreen.Fullscreen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps JourneyMap's full-screen map on the dimension picked with this mod's toggle
 * ({@link JourneyMapDimension}). Each refresh JourneyMap compares the map's dimension with the one
 * you are in and, if they differ, switches back to yours; for the full-screen map's own state, and
 * only while a dimension is picked, this hands it the picked one instead. The minimap's state is
 * left alone. Optional (require = 0): a JourneyMap that has moved this costs the toggle, not the game.
 */
@Mixin(targets = "journeymap.client.model.map.MapState", remap = false)
public abstract class MapStateMixin {

    @WrapOperation(
        method = "refresh",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;dimension()Lnet/minecraft/resources/ResourceKey;")
    )
    private ResourceKey<Level> skysmapexposer$keepDimension(Level level, Operation<ResourceKey<Level>> original) {
        ResourceKey<Level> actual = original.call(level);
        return (Object) this == Fullscreen.state() ? JourneyMapDimension.keep(actual) : actual;
    }
}
