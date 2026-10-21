package com.skystormer.skysmapexposer.mixin.tooltip;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.skystormer.skysmapexposer.gui.PanelClicks;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * On Xaero's world map, Xaero's buttons under a panel show no highlight or tooltip while the mouse
 * is over the panel, since the panel has the click ({@link PanelClicks}). They are told the mouse
 * is far away; the panels, and every other screen, are left alone. Not required: if it does not
 * apply, the buttons under a panel only light up as before.
 */
@Mixin(Screen.class)
public class ScreenHoverMixin {

    @WrapOperation(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/Renderable;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V")
    )
    private void skysmapexposer$noHoverUnderPanels(Renderable renderable, GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, Operation<Void> original) {
        Screen screen = (Screen) (Object) this;
        if (screen.getClass().getName().equals("xaero.map.gui.GuiMap") && PanelClicks.hidesHover(screen, renderable, mouseX, mouseY)) {
            original.call(renderable, graphics, -10_000, -10_000, partialTick);
        } else {
            original.call(renderable, graphics, mouseX, mouseY, partialTick);
        }
    }
}
