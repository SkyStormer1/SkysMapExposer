package com.skystormer.skysmapexposer.mixin.tooltip;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.skystormer.skysmapexposer.gui.SafeTooltip;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.Optional;

/**
 * A widget's tooltip made by {@link SafeTooltip} is placed by it, kept wholly on screen, instead
 * of by vanilla. Vanilla still decides when it shows (hover, keyboard focus, delay); only the last
 * step, handing it to the screen, is swapped. Everyone else's tooltips are left alone. In its own
 * mixin config, marked not required: if it does not apply, those tooltips are vanilla's.
 */
@Mixin(WidgetTooltipHolder.class)
public class WidgetTooltipHolderMixin {

    @Shadow private Tooltip tooltip;

    @WrapOperation(
            method = "refreshTooltipForNextRenderPass",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;IIZLnet/minecraft/resources/Identifier;)V"
            ),
            require = 0
    )
    private void skysmapexposer$keepOnScreen(
            GuiGraphicsExtractor graphics, Font font, List<FormattedCharSequence> lines, Optional<TooltipComponent> component,
            ClientTooltipPositioner positioner, int mouseX, int mouseY, boolean focused, Identifier style,
            Operation<Void> original,
            @Local(argsOnly = true, ordinal = 0) boolean hovered,
            @Local(argsOnly = true) ScreenRectangle rectangle
    ) {
        String text = SafeTooltip.textOf(tooltip);
        if (text == null) {
            original.call(graphics, font, lines, component, positioner, mouseX, mouseY, focused, style);
        } else if (hovered) {
            SafeTooltip.draw(graphics, text, mouseX, mouseY);
        } else {
            // Reached by keyboard: just below the widget (the helper puts a tooltip 12 above the point).
            SafeTooltip.draw(graphics, text, rectangle.left(), rectangle.bottom() + 14);
        }
    }
}
