package com.skystormer.skysmapexposer.mixin.journeymap;

import com.skystormer.skysmapexposer.gui.MapBar;
import journeymap.client.ui.fullscreen.menu.FullScreenPopupMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * JourneyMap's right-click menu is a screen of its own, drawn over the map: the map bar is drawn
 * here, after the map and before the menu, so the menu is always on top of it
 * ({@link MapBar#drawUnder}). Optional (require = 0): without it the bar is not shown while the menu is open.
 */
@Mixin(targets = "journeymap.client.ui.component.dropdown.PopupMenu", remap = false)
public abstract class PopupMenuMixin {

    @Inject(method = "renderPopupScreen", at = @At("HEAD"), require = 0)
    private void skysmapexposer$barUnderMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo info) {
        if (!((Object) this instanceof FullScreenPopupMenu)) return;
        try {
            MapBar.drawUnder(this, graphics, partialTick);
        } catch (RuntimeException | LinkageError e) {
            // Without the bar under the menu, the menu shows as it would anyway.
        }
    }
}
