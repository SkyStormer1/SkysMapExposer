package com.skystormer.skysmapexposer.mixin.journeymap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.skystormer.skysmapexposer.journeymap.JourneyMapDimension;
import journeymap.client.ui.fullscreen.Fullscreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Leaves your own arrow off JourneyMap's full-screen map while it shows another dimension than the
 * one you are in ({@link JourneyMapDimension}), as on Xaero's map: you are not there. The arrow and
 * its outline are the only pictures the map draws this way. Optional (require = 0): a JourneyMap that
 * draws it otherwise keeps showing it, nothing worse.
 */
@Mixin(value = Fullscreen.class, remap = false)
public abstract class FullscreenSelfMixin {

    @WrapOperation(
        method = "drawMap",
        at = @At(value = "INVOKE", target = "Ljourneymap/client/render/draw/DrawUtil;drawOnMapImageScaleCorrected(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/resources/Identifier;IFDDDDFDZ)V")
    )
    private void skysmapexposer$selfOnlyAtHome(
        GuiGraphicsExtractor graphics, Identifier texture, int colour, float alpha, double x, double y, double width, double height,
        float scale, double rotation, boolean flag, Operation<Void> original
    ) {
        if (JourneyMapDimension.showingOther()) return;
        original.call(graphics, texture, colour, alpha, x, y, width, height, scale, rotation, flag);
    }
}
