package com.skystormer.skysmapexposer.mixin.journeymap;

import com.skystormer.skysmapexposer.journeymap.JourneyMapTerrain;
import journeymap.client.render.GuiRenderToTexture;
import journeymap.client.render.map.Renderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.geom.Point2D;
import java.util.List;

/**
 * BlueMap's terrain on JourneyMap's minimap. JourneyMap draws the minimap's tiles and outlines into
 * a picture of its own, which it then cuts to the minimap's shape and turns with it; picture
 * overlays it draws afterwards, straight onto the screen, uncut and unturned. So the terrain
 * ({@link JourneyMapTerrain#drawOnMinimap}) is drawn into that picture instead, over JourneyMap's
 * tiles and under its outlines, in the same coordinates as the outlines. Only for the minimap;
 * optional (require = 0): a JourneyMap that has moved this costs the minimap's terrain, not the game.
 */
@Mixin(targets = "journeymap.client.render.map.MapRenderer", remap = false)
public abstract class MapRendererMixin {

    @Inject(
        method = "lambda$render$0",
        at = @At(value = "INVOKE", target = "Ljourneymap/client/render/map/PolygonGeometryDrawPass;draw(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lorg/joml/Matrix3x2fStack;Ljava/util/List;DDLjourneymap/client/render/map/Renderer;DD)V")
    )
    private void skysmapexposer$drawTerrain(
        Point2D.Double center, double xOffset, double yOffset, GuiGraphicsExtractor graphics, float alpha, boolean showGrid,
        List<?> polygons, double fontScale, double rotation, GuiRenderToTexture.RenderContext context, CallbackInfo ci
    ) {
        JourneyMapTerrain.drawOnMinimap(context.graphics(), context.pose(), xOffset, yOffset, (Renderer) (Object) this);
    }
}
