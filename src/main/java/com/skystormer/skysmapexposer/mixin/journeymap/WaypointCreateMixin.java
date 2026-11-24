package com.skystormer.skysmapexposer.mixin.journeymap;

import com.skystormer.skysmapexposer.journeymap.JourneyMapDimension;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * A temporary waypoint made from JourneyMap's right-click menu on a map showing another dimension
 * ({@link JourneyMapDimension}) goes into that dimension, where its place is: JourneyMap puts it in
 * the one you are in. Optional (require = 0): without it, it lands in your dimension, as before.
 */
@Mixin(targets = "journeymap.client.event.handlers.PopupMenuEventHandler", remap = false)
public abstract class WaypointCreateMixin {

    @ModifyArg(
        method = "lambda$createWaypoint$0",
        at = @At(value = "INVOKE", target = "Ljourneymap/api/client/waypoint/ClientWaypointFactoryImpl;at(Lnet/minecraft/core/BlockPos;ZLjava/lang/String;)Ljourneymap/client/waypoint/ClientWaypointImpl;"),
        index = 2
    )
    private static String skysmapexposer$madeInShown(String dimension) {
        ResourceKey<Level> shown = JourneyMapDimension.shownOther();
        return shown == null ? dimension : shown.identifier().toString();
    }
}
