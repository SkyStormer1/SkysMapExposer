package com.skystormer.skysmapexposer.mixin.menu;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;

/** The options behind the lines of Xaero's right-click menu, which it keeps to itself. */
@Mixin(targets = "xaero.map.gui.dropdown.rightclick.GuiRightClickMenu", remap = false)
public interface RightClickMenuAccess {

    @Accessor("actionOptions")
    ArrayList<RightClickOption> skysmapexposer$options();
}
