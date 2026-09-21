package com.skystormer.skysmapexposer.mixin;

import com.skystormer.skysmapexposer.MapView;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.hud.minimap.element.render.MinimapElementRenderer;
import xaero.hud.minimap.waypoint.render.WaypointMapRenderer;
import xaero.map.element.render.ElementRenderLocation;

/**
 * Keeps everything Xaero's Minimap draws on the world map — the entity radar, tracked players, and
 * anything a later version adds — off it while it is showing a dimension you are not standing in.
 * Waypoints are left alone, because Xaero has its own setting for which dimension's waypoints to
 * show and this mod switches that on instead.
 *
 * <p>This is the one funnel they all pass through. Everything the minimap contributes to the world
 * map is registered as one of these wrappers, so refusing here covers renderers that have not been
 * looked for yet — which matters, because they are easy to miss: the radar names
 * {@code WORLD_MAP} in its own {@code shouldRender} and the player tracker does not, so searching
 * Xaero for that constant finds one and not the other.
 *
 * <p>Those two are also refused at their own renderers, which is tidier and cheaper when it works.
 * This is the gate that has to hold, and it needs both halves: {@code shouldRender} for the ordinary
 * pass and {@code shouldRenderHovered} for the separate one Xaero runs for the element under the
 * cursor.
 */
@Mixin(targets = "xaero.map.mods.minimap.element.MinimapElementRendererWrapper", remap = false)
public abstract class MinimapElementWrapperMixin {

    @Shadow @Final private MinimapElementRenderer renderer;

    /**
     * The hovered element is drawn by a second pass that asks only this, never
     * {@code shouldRender} — so without it exactly one dot survived every other gate: whichever
     * one the cursor was nearest. The wrapper does not declare this method, so mixin merging it
     * here overrides the base class's flat "yes".
     *
     * <p>There is no location argument, but there does not need to be: this wrapper exists only to
     * put minimap elements on the world map, so every call is the world map.
     */
    public boolean shouldRenderHovered(boolean pre) {
        if (renderer instanceof WaypointMapRenderer) return true;
        return !MapView.hideWrappedMinimapElements();
    }

    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true, require = 0)
    private void skysmapexposer$hideMinimapElementsInOtherDimension(
            ElementRenderLocation location, boolean pre, CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()) return;
        if (location != ElementRenderLocation.WORLD_MAP && location != ElementRenderLocation.WORLD_MAP_MENU) return;
        // Waypoints are Xaero's own to place: its "only display current map waypoints" option
        // already swaps them with the dimension, so hiding them here would undo that.
        if (renderer instanceof WaypointMapRenderer) return;
        if (MapView.hideWrappedMinimapElements()) cir.setReturnValue(false);
    }
}
