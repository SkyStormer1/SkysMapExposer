package com.skystormer.skysmapexposer.mixin.journeymap;

import com.skystormer.skysmapexposer.journeymap.JourneyMapDimension;
import journeymap.client.waypoint.ClientWaypointImpl;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * On JourneyMap's full-screen map showing another dimension ({@link JourneyMapDimension}), its
 * waypoints are that dimension's: JourneyMap picks the waypoints to draw by the dimension you are in.
 * Optional (require = 0): without it the map keeps showing your own dimension's waypoints.
 */
@Mixin(value = ClientWaypointImpl.class, remap = false)
public abstract class WaypointShownMixin {

    @Inject(method = "isInPlayerDimension", at = @At("HEAD"), cancellable = true)
    private void skysmapexposer$waypointsOfShown(CallbackInfoReturnable<Boolean> info) {
        ResourceKey<Level> shown = JourneyMapDimension.shownForWaypoints();
        if (shown == null) return;
        info.setReturnValue(((ClientWaypointImpl) (Object) this).getDimensions().contains(shown.identifier().toString()));
    }
}
