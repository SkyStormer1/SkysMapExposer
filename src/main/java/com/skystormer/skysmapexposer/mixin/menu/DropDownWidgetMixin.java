package com.skystormer.skysmapexposer.mixin.menu;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.skystormer.skysmapexposer.xaero.MenuTooltips;
import com.skystormer.skysmapexposer.xaero.XaeroMenus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tooltips on this mod's lines of Xaero's right-click menu. Xaero's menu has no tooltips of its
 * own, and "Download" alone does not say from where. Runs after every dropdown draws; everything
 * that is not a right-click menu with one of this mod's options under the mouse is ignored at once.
 *
 * And no second click when this mod opens a page of the menu (More or a group) in the menu's place:
 * the click on the line that asked for it already made one ({@link XaeroMenus#getOpeningPage}).
 */
@Mixin(targets = "xaero.lib.client.gui.widget.dropdown.DropDownWidget", remap = false)
public abstract class DropDownWidgetMixin {

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIIZ)V", at = @At("TAIL"), require = 0)
    private void skysmapexposer$optionTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, int scaledHeight,
                                              boolean closedOnly, CallbackInfo ci) {
        MenuTooltips.draw(this, graphics, mouseX, mouseY, scaledHeight);
    }

    @WrapOperation(method = "setClosed(Z)V", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/sounds/SoundManager;play(Lnet/minecraft/client/resources/sounds/SoundInstance;)Lnet/minecraft/client/sounds/SoundEngine$PlayResult;"))
    private SoundEngine.PlayResult skysmapexposer$quietPage(SoundManager sounds, SoundInstance sound, Operation<SoundEngine.PlayResult> original) {
        return XaeroMenus.getOpeningPage() ? null : original.call(sounds, sound);
    }
}
