package com.skystormer.skysmapexposer.mixin.menu;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.skystormer.skysmapexposer.xaero.XaeroMenus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import xaero.map.gui.GuiMap;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;

/**
 * The world map's right-click menu put in your layout (XaeroMenus) as Xaero opens it: by then every
 * mod has added its rows to it, whichever order their hooks ran in, so another mod's rows (or a
 * newer Xaero's) can be ordered, grouped and hidden like the rest. Optional (require = 0): without
 * it the menu shows every row as the mods added them.
 */
@Mixin(targets = "xaero.map.gui.dropdown.rightclick.GuiRightClickMenu", remap = false)
public abstract class GuiRightClickMenuMixin {

    @ModifyExpressionValue(method = "getMenu", require = 0,
        at = @At(value = "INVOKE", target = "Lxaero/map/gui/IRightClickableElement;getRightClickOptions()Ljava/util/ArrayList;"))
    private static ArrayList<RightClickOption> skysmapexposer$layout(ArrayList<RightClickOption> options, IRightClickableElement target,
                                                                    GuiMap map, int x, int y, int width) {
        XaeroMenus.arrange(options, target);
        return options;
    }
}
