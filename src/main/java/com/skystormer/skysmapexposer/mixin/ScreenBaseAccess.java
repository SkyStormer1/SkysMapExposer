package com.skystormer.skysmapexposer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.lib.client.gui.widget.dropdown.DropDownWidget;

/** The dropdown or right-click menu open on one of Xaero's screens, which keeps the mouse while it is. */
@Mixin(targets = "xaero.lib.client.gui.ScreenBase", remap = false)
public interface ScreenBaseAccess {

    @Accessor("openDropdown")
    DropDownWidget skysmapexposer$openDropdown();
}
