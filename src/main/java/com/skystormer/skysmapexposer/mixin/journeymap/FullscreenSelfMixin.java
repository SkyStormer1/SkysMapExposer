package com.skystormer.skysmapexposer.mixin.journeymap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.skystormer.skysmapexposer.journeymap.JourneyMapDimension;
import journeymap.client.render.map.MapRenderer;
import journeymap.client.ui.fullscreen.Fullscreen;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.awt.geom.Point2D;

/**
 * Your own arrow on JourneyMap's full-screen map while it shows another dimension than the one you
 * are in ({@link JourneyMapDimension}): between the Overworld and the Nether, where the setting lets
 * it ({@link JourneyMapDimension#arrowScale}), at your position in that dimension's terms; anywhere
 * else left off, as on Xaero's map. JourneyMap places it at your own coordinates whatever the map
 * shows, so they are scaled before it asks where on screen they are; the arrow and its outline are
 * the only pictures the map draws this way. Optional (require = 0): a JourneyMap that draws it
 * otherwise keeps showing it where it puts it, nothing worse.
 */
@Mixin(value = Fullscreen.class, remap = false)
public abstract class FullscreenSelfMixin {

    @WrapOperation(
        method = "drawMap",
        at = @At(value = "INVOKE", target = "Ljourneymap/client/render/map/MapRenderer;getPixel(DD)Ljava/awt/geom/Point2D$Double;")
    )
    private Point2D.Double skysmapexposer$selfInShownDimension(MapRenderer renderer, double x, double z, Operation<Point2D.Double> original) {
        Double scale = JourneyMapDimension.showingOther() ? JourneyMapDimension.arrowScale() : null;
        return scale == null ? original.call(renderer, x, z) : original.call(renderer, x * scale, z * scale);
    }

    @WrapOperation(
        method = "drawMap",
        at = @At(value = "INVOKE", target = "Ljourneymap/client/render/draw/DrawUtil;drawOnMapImageScaleCorrected(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/resources/Identifier;IFDDDDFDZ)V")
    )
    private void skysmapexposer$selfOnlyWhereItMeansSomething(
        GuiGraphicsExtractor graphics, Identifier texture, int colour, float alpha, double x, double y, double width, double height,
        float scale, double rotation, boolean flag, Operation<Void> original
    ) {
        if (JourneyMapDimension.showingOther() && JourneyMapDimension.arrowScale() == null) return;
        original.call(graphics, texture, colour, alpha, x, y, width, height, scale, rotation, flag);
    }
}
