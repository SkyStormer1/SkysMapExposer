package com.skystormer.skysmapexposer.mixin.journeymap;

import com.skystormer.skysmapexposer.journeymap.JourneyMapToolbar;
import journeymap.client.ui.fullscreen.Fullscreen;
import journeymap.client.ui.theme.ThemeToolbar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts this mod's dimension button ({@link JourneyMapToolbar}) at the end of the full-screen map's
 * top row: added to JourneyMap's own options toolbar (mobs, animals, pets, villagers, players,
 * grid, keys) once JourneyMap has made its buttons, so JourneyMap lays it out with the rest and
 * anything it adds later comes before it. JourneyMap's API only reaches the side column. Optional
 * (require = 0): a JourneyMap that has moved this puts the button back in the side column.
 */
@Mixin(value = Fullscreen.class, remap = false)
public abstract class FullscreenToolbarMixin {

    @Shadow
    private ThemeToolbar optionsToolbar;

    @Inject(method = "initButtons", at = @At("TAIL"))
    private void skysmapexposer$dimensionButton(CallbackInfo ci) {
        JourneyMapToolbar.append((Fullscreen) (Object) this, optionsToolbar);
    }
}
