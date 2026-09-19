package com.skystormer.skysmapexposer.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.skystormer.skysmapexposer.Overlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.MapProcessor;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

/**
 * Draws the backfill into Xaero's map framebuffer, straight after Xaero has drawn its own terrain
 * and before anything that sits on top of terrain (highlights, waypoints, the player arrow).
 *
 * The anchor is the second flush of Xaero's terrain renderers: the first draws textures with
 * baked light, the second those without, and nothing else touches the terrain after that. Xaero's
 * map pipeline replaces colour rather than blending it, so whatever is drawn here wins over the
 * terrain beneath it — which is why {@link Overlay} only ever draws the exact areas it means to.
 *
 * The locals are taken by name, which Xaero's jar keeps. If a future Xaero renames them or moves
 * the flush, the injection does not apply ({@code require = 0}) and {@code /mapexposer} says so,
 * rather than the game refusing to start.
 */
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class GuiMapMixin {

    @Shadow private double cameraX;
    @Shadow private double cameraZ;
    @Shadow private MapProcessor mapProcessor;

    @Inject(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;draw(Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRenderer;)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void skysmapexposer$drawBackfill(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci,
            @Local(name = "matrix") Matrix4f matrix,
            @Local(name = "flooredCameraX") int flooredCameraX,
            @Local(name = "flooredCameraZ") int flooredCameraZ,
            @Local(name = "rendererProvider") MultiTextureRenderTypeRendererProvider rendererProvider
    ) {
        Overlay.draw(mapProcessor, matrix, flooredCameraX, flooredCameraZ, cameraX, cameraZ, rendererProvider);
    }
}
