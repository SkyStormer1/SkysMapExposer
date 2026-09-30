package com.skystormer.skysmapexposer.mixin.menu;

import com.skystormer.skysmapexposer.MenuTooltips;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tooltips on this mod's lines of Xaero's right-click menu. Xaero's menu has no tooltips of its
 * own, and "Download" alone does not say from where. Runs after every dropdown draws; everything
 * that is not a right-click menu with one of this mod's options under the mouse is ignored at once.
 */
@Mixin(targets = "xaero.lib.client.gui.widget.dropdown.DropDownWidget", remap = false)
public abstract class DropDownWidgetMixin {

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIZ)V", at = @At("TAIL"), require = 0)
    private void skysmapexposer$optionTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int scaledHeight,
                                              boolean closedOnly, CallbackInfo ci) {
        MenuTooltips.draw(this, graphics, mouseX, mouseY, scaledHeight);
    }
}
