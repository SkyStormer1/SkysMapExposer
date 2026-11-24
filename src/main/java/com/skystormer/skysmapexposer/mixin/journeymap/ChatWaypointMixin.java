package com.skystormer.skysmapexposer.mixin.journeymap;

import com.skystormer.skysmapexposer.journeymap.JourneyMapChat;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ctrl+click on a position JourneyMap made clickable in chat: its hover text says that shows it on
 * the map, but the click runs this command, which only adds it to the Pending Waypoints list. With
 * Ctrl held it is shown on the full-screen map instead ([JourneyMapChat]). Optional (require = 0):
 * without it Ctrl+click does what plain click does.
 */
@Mixin(targets = "journeymap.client.command.CmdShareWaypoint", remap = false)
public abstract class ChatWaypointMixin {

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true)
    private void skysmapexposer$ctrlShowsOnMap(LocalPlayer player, String[] args, CallbackInfoReturnable<Integer> cir) {
        if (JourneyMapChat.ctrlClicked(args)) cir.setReturnValue(0);
    }
}
