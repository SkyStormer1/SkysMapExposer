package com.skystormer.skysmapexposer.mixin.journeymap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.skystormer.skysmapexposer.journeymap.JourneyMapDimension;
import journeymap.common.waypoint.WaypointImpl;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * JourneyMap places a waypoint at Nether scale (an eighth) or Overworld scale by whether you are in
 * the Nether; on its full-screen map showing another dimension ({@link JourneyMapDimension}), by
 * whether that one is the Nether, so each waypoint lands where it is on the map shown. Optional
 * (require = 0): without it they are placed at the scale of where you are.
 */
@Mixin(value = WaypointImpl.class, remap = false)
public abstract class WaypointScaleMixin {

    @WrapOperation(
        method = {"neitherShiftRight", "netherShiftLeft"},
        at = @At(value = "INVOKE", target = "Ljourneymap/common/helper/DimensionHelper;isNetherWorld(Lnet/minecraft/world/level/Level;)Z")
    )
    private boolean skysmapexposer$scaleOfShown(Level level, Operation<Boolean> original) {
        ResourceKey<Level> shown = JourneyMapDimension.shownForWaypoints();
        return shown == null ? original.call(level) : shown.equals(Level.NETHER);
    }
}
