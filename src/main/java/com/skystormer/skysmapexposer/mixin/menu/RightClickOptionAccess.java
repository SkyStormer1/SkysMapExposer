package com.skystormer.skysmapexposer.mixin.menu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

/** The name (a translation key) behind a line of Xaero's right-click menu, which it keeps to itself. */
@Mixin(value = RightClickOption.class, remap = false)
public interface RightClickOptionAccess {

    @Accessor("name")
    String skysmapexposer$name();
}
