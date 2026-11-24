package com.skystormer.skysmapexposer.mixin.journeymap;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.skystormer.skysmapexposer.journeymap.JourneyMapDimension;
import journeymap.client.ui.fullscreen.Fullscreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marks JourneyMap's work for its full-screen map, drawing it and its Chat Position key (which
 * already names the map's dimension, so this gives it that dimension's scale), so that
 * waypoints in it go by the dimension the map shows ({@link JourneyMapDimension#shownForWaypoints}).
 * Optional (require = 0): without it the map shows your own dimension's waypoints, as before.
 */
@Mixin(value = Fullscreen.class, remap = false)
public abstract class FullscreenShownMixin {

    @WrapMethod(method = "drawMap")
    private void skysmapexposer$waypointsOfShown(GuiGraphicsExtractor graphics, int width, int height, Operation<Void> original) {
        JourneyMapDimension.enterShown();
        try {
            original.call(graphics, width, height);
        } finally {
            JourneyMapDimension.leaveShown();
        }
    }

    @WrapMethod(method = "chatPositionAtMouse")
    private void skysmapexposer$chatOfShown(Operation<Void> original) {
        JourneyMapDimension.enterShown();
        try {
            original.call();
        } finally {
            JourneyMapDimension.leaveShown();
        }
    }
}
