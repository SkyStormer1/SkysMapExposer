package com.skystormer.skysmapexposer.mixin.journeymap;

import com.skystormer.skysmapexposer.journeymap.JourneyMapMinimap;
import journeymap.client.render.map.Renderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * This mod's pictures on JourneyMap's maps (BlueMap's terrain, the biome tint) where it draws no grid.
 * JourneyMap draws a map's tiles, its grid and then its outlines (this pass) into a picture of its
 * own, which on the minimap it then cuts to shape and turns; picture overlays it draws afterwards,
 * straight onto the screen, uncut, unturned and over the grid. So {@link JourneyMapMinimap#draw}
 * draws before its grid ({@link GridPassMixin}), or with no grid at the start of this pass: over
 * JourneyMap's tiles, under its outlines, in the same coordinates. Checked when the game starts, as
 * the Xaero hooks are; a JourneyMap that has moved this costs these pictures (and says so), not the game.
 */
@Mixin(targets = "journeymap.client.render.map.PolygonGeometryDrawPass", remap = false)
public abstract class OutlinePassMixin {

    @Inject(method = "draw", at = @At("HEAD"))
    private void skysmapexposer$drawTerrain(
        GuiGraphicsExtractor graphics, Matrix3x2fStack pose, List<?> polygons, double xOffset, double yOffset,
        Renderer renderer, double fontScale, double rotation, CallbackInfo ci
    ) {
        JourneyMapMinimap.draw(graphics, pose, xOffset, yOffset, renderer, false);
    }
}
