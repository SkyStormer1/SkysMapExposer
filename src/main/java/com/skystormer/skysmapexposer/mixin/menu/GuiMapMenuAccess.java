package com.skystormer.skysmapexposer.mixin.menu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.MapTileSelection;

/**
 * Opens a right-click menu on Xaero's world map at a spot, as a right-click does: for a group of
 * the menu laid out by XaeroMenus, opened in the menu's place. Xaero clears its chunk selection
 * whenever a menu closes, so it is given back first: the chunk line, Download and an area's Save
 * terrain file depend on it.
 */
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public interface GuiMapMenuAccess {

    @Invoker("handleRightClick")
    void skysmapexposer$handleRightClick(IRightClickableElement element, int x, int y);

    @Accessor("mapTileSelection")
    void skysmapexposer$setSelection(MapTileSelection selection);
}
