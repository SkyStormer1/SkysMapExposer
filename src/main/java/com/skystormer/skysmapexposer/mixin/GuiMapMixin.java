package com.skystormer.skysmapexposer.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.skystormer.skysmapexposer.MapCamera;
import com.skystormer.skysmapexposer.MapMenus;
import com.skystormer.skysmapexposer.MapView;
import com.skystormer.skysmapexposer.Overlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.MapProcessor;
import xaero.map.animation.SlowingAnimation;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;

/**
 * Three things on Xaero's world map screen.
 *
 * <p>Drawing: the backfill goes into Xaero's map framebuffer straight after Xaero has drawn its own
 * terrain and before anything that sits on top of terrain (highlights, waypoints, the player
 * arrow). The anchor is the second flush of Xaero's terrain renderers: the first draws textures
 * with baked light, the second those without, and nothing else touches the terrain after that.
 * Xaero's map pipeline replaces colour rather than blending it, so whatever is drawn here wins over
 * the terrain beneath it — which is why {@link Overlay} only ever draws the exact areas it means
 * to.
 *
 * <p>Right-click menu: "Copy coordinates" and "Players…" at the end of the menu Xaero shows when
 * you right-click the map itself. {@code rightClickDim} is Xaero's own reading of which
 * dimension that click landed in, so the copied coordinates can say where they are.
 *
 * <p>Camera: {@link MapCamera}, so the player list can move the open map to someone.
 *
 * <p>The locals are taken by name, which Xaero's jar keeps. If a future Xaero renames them or moves
 * the flush, the injection does not apply ({@code require = 0}) and {@code /mapexposer} says so,
 * rather than the game refusing to start.
 */
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class GuiMapMixin implements MapCamera {

    @Shadow private double cameraX;
    @Shadow private double cameraZ;
    @Shadow private MapProcessor mapProcessor;
    @Shadow private int rightClickX;
    @Shadow private int rightClickZ;
    @Shadow private ResourceKey<Level> rightClickDim;
    @Shadow private int[] cameraDestination;
    @Shadow private SlowingAnimation cameraDestinationAnimX;
    @Shadow private SlowingAnimation cameraDestinationAnimZ;
    @Shadow private boolean shouldResetCameraPos;
    @Shadow private static boolean attachedCamera;

    /**
     * Moves the camera the way Xaero's own "hop to coordinates" does: detached from the player
     * (while attached, Xaero puts it back on the player every frame), then gliding there. A map
     * that has not been drawn yet would put its camera on the player on its first frame, so that
     * one starts on the spot instead.
     */
    @Override
    public void skysmapexposerCentreOn(int x, int z) {
        attachedCamera = false;
        if (shouldResetCameraPos) {
            shouldResetCameraPos = false;
            cameraX = x + 0.5;
            cameraZ = z + 0.5;
            cameraDestination = null;
        } else {
            cameraDestination = new int[]{x, z};
        }
    }

    /**
     * Straight there, cancelling any glide already under way. Xaero puts the camera back on the
     * player every frame while it is attached, so that has to come off first.
     */
    @Override
    public void skysmapexposerJumpTo(int x, int z) {
        attachedCamera = false;
        shouldResetCameraPos = false;
        cameraX = x + 0.5;
        cameraZ = z + 0.5;
        cameraDestination = null;
        cameraDestinationAnimX = null;
        cameraDestinationAnimZ = null;
    }

    /**
     * Hands the camera back to Xaero, which puts it on the player on its next frame. Used when the
     * map returns to the dimension you are standing in: Xaero's own reset takes priority over the
     * shift it applies when the dimension scale changes, so this lands on you rather than at the
     * shifted position. Unlike a jump it leaves {@code attachedCamera} alone, so a camera that was
     * following you still is.
     */
    @Override
    public void skysmapexposerFollowPlayer() {
        shouldResetCameraPos = true;
        cameraDestination = null;
        cameraDestinationAnimX = null;
        cameraDestinationAnimZ = null;
    }

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

    /**
     * Hides the player arrow while the map is showing a dimension you are not in.
     *
     * Xaero draws it at your own position converted into the viewed dimension, so from the nether
     * it sits eight times further out, marking somewhere you are not. Its own "Render Player Arrow"
     * setting is all or nothing, so this takes the boolean Xaero just read out of that setting and
     * turns it off for this case only.
     *
     * The slice starts at Xaero's single read of the ARROW option and {@code ordinal = 0} takes the
     * first {@code booleanValue} after it, which is that option's. <b>Both are needed.</b> A slice
     * only says where to start looking: without the ordinal this matches every {@code booleanValue}
     * from there to the end of a method thousands of instructions long, which switched off much of
     * the rest of the map — the coordinate readout along with it.
     */
    @ModifyExpressionValue(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Ljava/lang/Boolean;booleanValue()Z", ordinal = 0),
            slice = @Slice(
                    from = @At(
                            value = "FIELD",
                            target = "Lxaero/map/common/config/option/WorldMapProfiledConfigOptions;ARROW:Lxaero/lib/common/config/option/BooleanConfigOption;"
                    )
            ),
            require = 0
    )
    private boolean skysmapexposer$hideArrowInOtherDimension(boolean original) {
        return original && !MapView.hideArrow();
    }

    @Inject(method = "getRightClickOptions", at = @At("RETURN"), require = 0)
    private void skysmapexposer$addMenuOptions(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        MapMenus.addMapOptions(cir.getReturnValue(), (IRightClickableElement) this, rightClickX, rightClickZ, rightClickDim);
    }
}
