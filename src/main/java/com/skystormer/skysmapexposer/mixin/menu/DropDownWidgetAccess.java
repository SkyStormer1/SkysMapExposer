package com.skystormer.skysmapexposer.mixin.menu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The three private sums Xaero's Lib uses to work out which line of a dropdown the mouse is on, so
 * {@link com.skystormer.skysmapexposer.MenuTooltips} can ask the same question the same way rather
 * than guessing at line heights and scrolling.
 */
@Mixin(targets = "xaero.lib.client.gui.widget.dropdown.DropDownWidget", remap = false)
public interface DropDownWidgetAccess {

    @Invoker("getHoveredId")
    int skysmapexposer$hoveredId(int mouseX, int mouseY, boolean scrolling, int optionLimit);

    @Invoker("scrolling")
    boolean skysmapexposer$scrolling(int optionLimit);

    @Invoker("optionLimit")
    int skysmapexposer$optionLimit(int scaledHeight);
}
