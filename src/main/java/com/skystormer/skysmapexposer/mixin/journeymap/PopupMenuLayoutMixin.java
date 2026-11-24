package com.skystormer.skysmapexposer.mixin.journeymap;

import com.skystormer.skysmapexposer.journeymap.JourneyMapMenuLayout;
import journeymap.api.v2.client.fullscreen.ModPopupMenu;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * JourneyMap's right-click menu on the full-screen map, put in your layout ({@link JourneyMapMenuLayout})
 * once every item is in it and before it is drawn; only the map's own menu, not a waypoint's.
 * Optional (require = 0): without it the menu is as JourneyMap and this mod add to it.
 */
@Mixin(targets = "journeymap.client.ui.fullscreen.menu.FullScreenPopupMenu", remap = false)
public abstract class PopupMenuLayoutMixin {

    @Inject(method = "displayBasicOptions", at = @At("HEAD"), require = 0)
    private void skysmapexposer$basicStart(BlockPos pos, CallbackInfo ci) {
        JourneyMapMenuLayout.basicStart();
    }

    @Inject(method = "displayBasicOptions", at = @At("RETURN"), require = 0)
    private void skysmapexposer$basicEnd(BlockPos pos, CallbackInfo ci) {
        JourneyMapMenuLayout.basicEnd();
    }

    @Inject(method = "displayOptions", at = @At("HEAD"), require = 0)
    private void skysmapexposer$layout(BlockPos pos, ModPopupMenu menu, CallbackInfo ci) {
        JourneyMapMenuLayout.arrange(menu);
    }
}
