package com.skystormer.skysmapexposer.mixin.minimap;

import com.skystormer.skysmapexposer.MapView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.hud.minimap.element.render.MinimapElementGraphics;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.player.tracker.PlayerTrackerMinimapElement;
import xaero.lib.client.graphics.XaeroBufferProvider;

/**
 * Keeps Xaero's tracked-player dots off the world map while it is showing a dimension you are not
 * in, for the same reason as the entity radar: they are placed by converting coordinates into the
 * dimension on screen, so they mark somewhere nobody is.
 *
 * <p>This one is easy to miss when looking for what draws on the world map. Unlike the radar, its
 * {@code shouldRender} never mentions {@code WORLD_MAP} — it answers every location that is not
 * {@code IN_WORLD} with Xaero's "tracked players on the minimap" setting, and so says yes to the
 * world map without naming it. Searching Xaero for that constant finds the radar and not this.
 *
 * <p>Gated in both places, like the radar: {@code shouldRender} to refuse tidily, and
 * {@code renderElement} as the last call before anything is drawn.
 */
@Mixin(targets = "xaero.hud.minimap.player.tracker.PlayerTrackerMinimapElementRenderer", remap = false)
public abstract class PlayerTrackerRendererMixin {

    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true, require = 0)
    private void skysmapexposer$hideTrackerInOtherDimension(
            MinimapElementRenderLocation location, CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()) return;
        if (!skysmapexposer$onAnotherDimensionsMap(location)) return;
        cir.setReturnValue(false);
    }

    /** Spelled out, because the generic superclass leaves a bridge method of the same name here. */
    @Inject(
            method = "renderElement(Lxaero/hud/minimap/player/tracker/PlayerTrackerMinimapElement;ZZDFDDLxaero/hud/minimap/element/render/MinimapElementRenderInfo;Lxaero/hud/minimap/element/render/MinimapElementGraphics;Lxaero/lib/client/graphics/XaeroBufferProvider;)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void skysmapexposer$hideTrackerDot(
            PlayerTrackerMinimapElement<?> element, boolean highlighted, boolean outOfBounds, double depth,
            float scale, double partialX, double partialY, MinimapElementRenderInfo info,
            MinimapElementGraphics graphics, XaeroBufferProvider buffers,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (!skysmapexposer$onAnotherDimensionsMap(info.location)) return;
        cir.setReturnValue(false);
    }

    private static boolean skysmapexposer$onAnotherDimensionsMap(MinimapElementRenderLocation location) {
        if (location != MinimapElementRenderLocation.WORLD_MAP
                && location != MinimapElementRenderLocation.WORLD_MAP_MENU) {
            return false;
        }
        return MapView.hideTracker();
    }
}
