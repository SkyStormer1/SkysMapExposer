package com.skystormer.skysmapexposer.mixin.menu;

import com.skystormer.skysmapexposer.xaero.MapMenus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;
import xaero.map.mods.gui.Waypoint;

import java.util.ArrayList;

/**
 * A waypoint's right-click menu on Xaero's world map: its Share, which posts the waypoint in global
 * chat, becomes this mod's ({@link MapMenus#replaceWaypointShare}): to a player, or to everyone.
 * Optional (require = 0): without it Xaero's own Share stays.
 */
@Mixin(value = xaero.map.mods.gui.WaypointReader.class, remap = false)
public abstract class WaypointMenuMixin {

    @Inject(method = "getRightClickOptions(Lxaero/map/mods/gui/Waypoint;Lxaero/map/gui/IRightClickableElement;)Ljava/util/ArrayList;", at = @At("RETURN"))
    private void skysmapexposer$ourShare(Waypoint waypoint, IRightClickableElement target, CallbackInfoReturnable<ArrayList<RightClickOption>> info) {
        MapMenus.replaceWaypointShare(info.getReturnValue(), waypoint, target);
    }
}
