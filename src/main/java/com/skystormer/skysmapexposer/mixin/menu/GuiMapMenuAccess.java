package com.skystormer.skysmapexposer.mixin.menu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import xaero.map.gui.IRightClickableElement;

/**
 * Opens a right-click menu on Xaero's world map at a spot, as a right-click does: for a group of
 * the menu laid out by XaeroMenus, opened in the menu's place.
 */
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public interface GuiMapMenuAccess {

    @Invoker("handleRightClick")
    void skysmapexposer$handleRightClick(IRightClickableElement element, int x, int y);
}
