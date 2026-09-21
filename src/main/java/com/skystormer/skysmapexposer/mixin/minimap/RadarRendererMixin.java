package com.skystormer.skysmapexposer.mixin.minimap;

import com.skystormer.skysmapexposer.MapView;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.hud.minimap.element.render.MinimapElementGraphics;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.lib.client.graphics.XaeroBufferProvider;

/**
 * Keeps Xaero's entity radar off the world map while it is showing a dimension you are not in.
 *
 * The radar draws the entities loaded around <em>you</em>, and the world map places them by
 * converting your coordinates into the dimension it is showing. Viewing the overworld from the
 * nether therefore scatters the mobs and dropped items beside you across somewhere eight times
 * further out, where they are not and where nothing has been mapped. Xaero's own radar setting is
 * all or nothing, so this turns it off for this case only.
 *
 * <p>Two gates, because the first one alone did not hold: {@code shouldRender} is the tidy place to
 * refuse, and {@code renderElement} is the last thing called before anything is drawn, so nothing
 * that reaches it can slip past. Whichever runs first, the answer is the same, and the second costs
 * a comparison per entity.
 *
 * <p>The minimap itself and the world map of your own dimension are untouched: only the two world
 * map locations are answered differently, and only while the dimensions differ.
 *
 * <p>In the minimap's mixin config, which is not required, so this costs nothing without Xaero's
 * Minimap installed or on a version that has moved this class.
 */
@Mixin(targets = "xaero.hud.minimap.radar.render.element.RadarRenderer", remap = false)
public abstract class RadarRendererMixin {

    @Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true, require = 0)
    private void skysmapexposer$hideRadarInOtherDimension(
            MinimapElementRenderLocation location, CallbackInfoReturnable<Boolean> cir
    ) {
        if (!cir.getReturnValueZ()) return;
        if (!skysmapexposer$onAnotherDimensionsMap(location)) return;
        cir.setReturnValue(false);
    }

    /**
     * The descriptor is spelled out because this class also carries the bridge method the generic
     * superclass needs, and {@code renderElement} alone would be ambiguous between the two.
     */
    @Inject(
            method = "renderElement(Lnet/minecraft/world/entity/Entity;ZZDFDDLxaero/hud/minimap/element/render/MinimapElementRenderInfo;Lxaero/hud/minimap/element/render/MinimapElementGraphics;Lxaero/lib/client/graphics/XaeroBufferProvider;)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void skysmapexposer$hideRadarDot(
            Entity entity, boolean highlighted, boolean outOfBounds, double depth, float scale,
            double partialX, double partialY, MinimapElementRenderInfo info,
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
        return MapView.hideRadar();
    }
}
