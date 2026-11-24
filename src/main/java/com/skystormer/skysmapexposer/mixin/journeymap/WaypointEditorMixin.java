package com.skystormer.skysmapexposer.mixin.journeymap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.skystormer.skysmapexposer.Config;
import com.skystormer.skysmapexposer.journeymap.JourneyMapDimension;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A waypoint made in JourneyMap's waypoint editor from a map showing another dimension
 * ({@link JourneyMapDimension}), with Create Waypoint or its key, belongs to that dimension: the
 * editor gives a new one the dimension you are in. Its place is read and saved at that dimension's
 * scale too (`WaypointScaleMixin`, while the editor is open). Optional (require = 0): without it,
 * it lands in your dimension, as before.
 */
@Mixin(targets = "journeymap.client.ui.waypointmanager.waypoint.WaypointEditor", remap = false)
public abstract class WaypointEditorMixin {

    @WrapOperation(
        method = "buildNewWaypoint",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;dimension()Lnet/minecraft/resources/ResourceKey;")
    )
    private ResourceKey<Level> skysmapexposer$madeInShown(Level level, Operation<ResourceKey<Level>> original) {
        ResourceKey<Level> shown = JourneyMapDimension.shownOther();
        return shown == null ? original.call(level) : shown;
    }

    /** Made where you stand (no block picked): your place in the dimension shown, an eighth or eight times it to or from the Nether. */
    @WrapOperation(
        method = "buildNewWaypoint",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;blockPosition()Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos skysmapexposer$placeInShown(LocalPlayer player, Operation<BlockPos> original) {
        BlockPos here = original.call(player);
        ResourceKey<Level> shown = JourneyMapDimension.shownOther();
        if (shown == null) return here;
        double scale = scaleOf(player.level().dimension()) / scaleOf(shown);
        return BlockPos.containing(here.getX() * scale, here.getY(), here.getZ() * scale);
    }

    private static double scaleOf(ResourceKey<Level> dimension) {
        return Level.NETHER.equals(dimension) ? Config.NETHER_SCALE : 1.0;
    }
}
