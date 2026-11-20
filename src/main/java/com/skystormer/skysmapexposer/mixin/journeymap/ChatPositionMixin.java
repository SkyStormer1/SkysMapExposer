package com.skystormer.skysmapexposer.mixin.journeymap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import journeymap.api.v2.client.fullscreen.ModPopupMenu;
import journeymap.client.Constants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Leaves JourneyMap's Chat Position off its right-click menu on the full-screen map: this mod's
 * Copy coordinates there does the same, and gives the dimension shown, where JourneyMap's named the
 * one you are in. Optional (require = 0): without it both are on the menu.
 */
@Mixin(targets = "journeymap.client.event.handlers.PopupMenuEventHandler", remap = false)
public abstract class ChatPositionMixin {

    @WrapOperation(
        method = "onFullscreenPopupMenu",
        at = @At(value = "INVOKE", target = "Ljourneymap/api/v2/client/fullscreen/ModPopupMenu;addMenuItem(Ljava/lang/String;Ljourneymap/api/v2/client/fullscreen/ModPopupMenu$Action;)Ljourneymap/api/v2/client/fullscreen/ModPopupMenu;")
    )
    private ModPopupMenu skysmapexposer$noChatPosition(ModPopupMenu menu, String label, ModPopupMenu.Action action, Operation<ModPopupMenu> original) {
        if (label != null && label.equals(Constants.getString("key.journeymap.fullscreen.menu.chat_position"))) return menu;
        return original.call(menu, label, action);
    }
}
