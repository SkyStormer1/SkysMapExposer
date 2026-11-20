package com.skystormer.skysmapexposer.mixin.journeymap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.skystormer.skysmapexposer.journeymap.JourneyMapMinimap;
import journeymap.client.model.region.RegionCoord;
import journeymap.client.render.map.GridLines;
import journeymap.client.render.map.MapRenderer;
import journeymap.client.render.map.Renderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

/**
 * This mod's pictures on JourneyMap's maps (BlueMap's terrain, the biome tint), drawn into the map's
 * own picture just before its grid, so the grid shows over them as over JourneyMap's own tiles
 * ({@link JourneyMapMinimap#draw}); the grid lines are moved by the same offset as the outlines.
 * Optional (require = 0): without it they are drawn before the outlines instead
 * ({@link OutlinePassMixin}), over the grid.
 */
@Mixin(value = MapRenderer.class, remap = false)
public abstract class GridPassMixin {

    @WrapOperation(
        method = "lambda$render$0",
        at = @At(value = "INVOKE", target = "Ljourneymap/client/render/map/GridLines;draw(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lorg/joml/Matrix3x2fStack;Ljourneymap/client/model/region/RegionCoord;Ljava/awt/geom/Point2D$Double;Ljava/awt/geom/Rectangle2D$Double;DDDDIFZ)V")
    )
    private void skysmapexposer$underGrid(
        GridLines lines, GuiGraphicsExtractor graphics, Matrix3x2fStack pose, RegionCoord centre, Point2D.Double origin,
        Rectangle2D.Double bounds, double centreX, double centreY, double xOffset, double yOffset, int zoom, float alpha,
        boolean flag, Operation<Void> original
    ) {
        JourneyMapMinimap.draw(graphics, pose, xOffset, yOffset, (Renderer) (Object) this, true);
        original.call(lines, graphics, pose, centre, origin, bounds, centreX, centreY, xOffset, yOffset, zoom, alpha, flag);
    }
}
