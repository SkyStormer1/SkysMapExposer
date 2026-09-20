package com.skystormer.skysmapexposer.mixin.minimap;

import com.skystormer.skysmapexposer.MapView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;

/**
 * Keeps Xaero's entity radar off the world map while it is showing a dimension you are not in.
 *
 * The radar draws the entities loaded around <em>you</em>, and the world map places them by
 * converting your coordinates into whatever dimension it is showing. Viewing the overworld from the
 * nether therefore scatters the mobs standing next to you across somewhere eight times further out,
 * where they are not and where nothing has been mapped. Xaero's own radar setting is all or
 * nothing, so this turns it off for this case only, exactly as {@code GuiMapMixin} does with the
 * player arrow.
 *
 * The minimap itself and the world map of your own dimension are untouched: only the two world map
 * locations are answered differently, and only while the dimensions differ.
 *
 * In the minimap's mixin config, which is not required, so this costs nothing without Xaero's
 * Minimap installed or on a version that has moved this class.
 */
@Mixin(targets = "xaero.hud.minimap.radar.render.element.RadarRenderer", remap = false)
public abstract class RadarRendererMixin {

    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true, require = 0)
    private void skysmapexposer$hideRadarInOtherDimension(
            MinimapElementRenderLocation location, CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()) return;
        if (location != MinimapElementRenderLocation.WORLD_MAP
                && location != MinimapElementRenderLocation.WORLD_MAP_MENU) {
            return;
        }
        if (MapView.hideRadar()) cir.setReturnValue(false);
    }
}
