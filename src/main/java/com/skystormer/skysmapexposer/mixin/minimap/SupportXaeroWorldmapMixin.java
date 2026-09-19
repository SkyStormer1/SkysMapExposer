package com.skystormer.skysmapexposer.mixin.minimap;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.skystormer.skysmapexposer.MinimapOverlay;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.common.minimap.render.MinimapRendererHelper;
import xaero.hud.minimap.module.MinimapSession;
import xaero.map.MapProcessor;

import java.util.function.Consumer;

/**
 * Draws the backfill into Xaero's minimap, straight after the minimap has drawn its terrain from
 * the world map's data — the same moment, in the same way, as {@code GuiMapMixin} does for the
 * world map: after the second flush of the terrain renderers, the one without baked light.
 *
 * In its own mixin config, marked not required, so that the mod still loads without Xaero's
 * Minimap. Locals are taken by name, which the minimap's jar keeps.
 */
@Mixin(targets = "xaero.common.mods.SupportXaeroWorldmap", remap = false)
public abstract class SupportXaeroWorldmapMixin {

    @Inject(
            method = "drawMinimap",
            at = @At(
                    value = "INVOKE",
                    target = "Lxaero/common/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;draw(Lxaero/common/graphics/renderer/multitexture/MultiTextureRenderTypeRenderer;)V",
                    ordinal = 1,
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void skysmapexposer$drawBackfill(
            MinimapSession minimapSession, PoseStack matrixStack, MinimapRendererHelper helper,
            int xFloored, int zFloored, int minViewX, int minViewZ, int maxViewX, int maxViewZ,
            boolean zooming, double zoom, double mapDimensionScale,
            VertexConsumer overlayBufferBuilder, MultiTextureRenderTypeRendererProvider multiTextureRenderTypeRenderers,
            CallbackInfo ci,
            @Local(name = "mapProcessor") MapProcessor mapProcessor,
            @Local(name = "mapRenderType") RenderType mapRenderType,
            @Local(name = "finalizer") Consumer<GpuTextureView> finalizer
    ) {
        MinimapOverlay.draw(mapProcessor, matrixStack, xFloored, zFloored, minViewX, minViewZ, maxViewX, maxViewZ,
                overlayBufferBuilder, multiTextureRenderTypeRenderers, mapRenderType, finalizer);
    }
}
