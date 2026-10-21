package com.skystormer.skysmapexposer.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.skystormer.skysmapexposer.BiomeHighlight;
import com.skystormer.skysmapexposer.GuiMapFields;
import com.skystormer.skysmapexposer.HookErrors;
import com.skystormer.skysmapexposer.XaeroField;
import com.skystormer.skysmapexposer.MapCamera;
import com.skystormer.skysmapexposer.MapMenus;
import com.skystormer.skysmapexposer.MapView;
import com.skystormer.skysmapexposer.OtherDimensionCoords;
import com.skystormer.skysmapexposer.Overlay;
import com.skystormer.skysmapexposer.gui.MapBar;
import com.skystormer.skysmapexposer.gui.PanelClicks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.MapProcessor;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.MapTileSelection;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;

/**
 * Six things on Xaero's world map screen.
 *
 * <p>Drawing: the backfill goes into Xaero's map framebuffer straight after Xaero has drawn its own
 * terrain and before anything that sits on top of terrain (highlights, waypoints, the player
 * arrow). The anchor is the second flush of Xaero's terrain renderers: the first draws textures
 * with baked light, the second those without, and nothing else touches the terrain after that.
 * Xaero's map pipeline replaces colour rather than blending it, so whatever is drawn here wins over
 * the terrain beneath it — which is why {@link Overlay} only ever draws the exact areas it means
 * to.
 *
 * <p>Biome highlight: {@link BiomeHighlight} adds see-through rectangles to Xaero's colour-overlay
 * buffer straight after the <em>first</em> terrain flush. That buffer is drawn later, over all
 * terrain, in the order things were added to it, so the tint lands under the world border (added at
 * the second flush) and under any other mod's shapes added there.
 *
 * <p>Right-click menu: "Copy coordinates", "Players…" and "Download" at the end of the menu Xaero shows when
 * you right-click the map itself. {@code rightClickDim} is Xaero's own reading of which
 * dimension that click landed in, so the copied coordinates can say where they are.
 *
 * <p>Other dimension: under Xaero's readout of the block under the mouse, the same block's
 * coordinates in the other dimension ({@link OtherDimensionCoords}).
 *
 * <p>Camera: {@link MapCamera}, so the player list can move the open map to someone. Clicks over
 * a panel go to it before Xaero's buttons ({@link PanelClicks}). And the
 * mouse wheel goes to the {@link MapBar} first when it is over it, as Xaero would otherwise zoom.
 *
 * <p>The locals are taken by name, which Xaero's jar keeps. If a future Xaero renames them or moves
 * the flush, the injection does not apply ({@code require = 0}) and {@code /mapexposer} says so,
 * rather than the game refusing to start. An error while drawing switches that one feature off
 * until you rejoin ({@link HookErrors}) instead of crashing the game.
 */
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class GuiMapMixin implements MapCamera {

    // Xaero's fields are looked up by name (GuiMapFields) rather than shadowed: a @Shadow field a
    // newer Xaero has renamed stops the game starting, where a lookup only switches off its feature.

    /**
     * Moves the camera the way Xaero's own "hop to coordinates" does: detached from the player
     * (while attached, Xaero puts it back on the player every frame), then gliding there. A map
     * that has not been drawn yet would put its camera on the player on its first frame, so that
     * one starts on the spot instead.
     */
    @Override
    public void skysmapexposerCentreOn(int x, int z) {
        if (!GuiMapFields.camera()) return;
        GuiMapFields.attachedCamera.set(this, false);
        if (GuiMapFields.shouldResetCameraPos.getBoolean(this)) {
            GuiMapFields.shouldResetCameraPos.set(this, false);
            GuiMapFields.cameraX.set(this, x + 0.5);
            GuiMapFields.cameraZ.set(this, z + 0.5);
            GuiMapFields.cameraDestination.set(this, null);
        } else {
            GuiMapFields.cameraDestination.set(this, new int[]{x, z});
        }
    }

    /**
     * Straight there, cancelling any glide already under way. Xaero puts the camera back on the
     * player every frame while it is attached, so that has to come off first.
     */
    @Override
    public void skysmapexposerJumpTo(int x, int z) {
        if (!GuiMapFields.camera()) return;
        GuiMapFields.attachedCamera.set(this, false);
        GuiMapFields.shouldResetCameraPos.set(this, false);
        GuiMapFields.cameraX.set(this, x + 0.5);
        GuiMapFields.cameraZ.set(this, z + 0.5);
        GuiMapFields.cameraDestination.set(this, null);
        GuiMapFields.cameraDestinationAnimX.set(this, null);
        GuiMapFields.cameraDestinationAnimZ.set(this, null);
    }

    /**
     * Xaero eases its zoom toward {@code destScale} every frame, and clamps it to its own limits, so
     * setting that is the same smooth zoom the mouse wheel gives.
     */
    @Override
    public void skysmapexposerZoomIn(double scale) {
        if (!GuiMapFields.destScale.getFound()) return;
        if (GuiMapFields.destScale.getDouble(this) < scale) GuiMapFields.destScale.set(this, scale);
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
        if (!GuiMapFields.camera()) return;
        GuiMapFields.shouldResetCameraPos.set(this, true);
        GuiMapFields.cameraDestination.set(this, null);
        GuiMapFields.cameraDestinationAnimX.set(this, null);
        GuiMapFields.cameraDestinationAnimZ.set(this, null);
    }

    /** What drawing on the map needs of Xaero's screen. */
    @Unique
    private static boolean skysmapexposer$canDraw() {
        return XaeroField.all(GuiMapFields.mapProcessor, GuiMapFields.cameraX, GuiMapFields.cameraZ);
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
        if (!skysmapexposer$canDraw()) return;
        Overlay.draw((MapProcessor) GuiMapFields.mapProcessor.get(this), matrix, flooredCameraX, flooredCameraZ,
                GuiMapFields.cameraX.getDouble(this), GuiMapFields.cameraZ.getDouble(this), rendererProvider);
    }

    @Inject(
            method = "extractRenderState",
            at = @At(
                    value = "INVOKE",
                    target = "Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRendererProvider;draw(Lxaero/map/graphics/renderer/multitexture/MultiTextureRenderTypeRenderer;)V",
                    ordinal = 0,
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void skysmapexposer$drawBiomeHighlight(
            GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci,
            @Local(name = "matrix") Matrix4f matrix,
            @Local(name = "flooredCameraX") int flooredCameraX,
            @Local(name = "flooredCameraZ") int flooredCameraZ
    ) {
        if (!skysmapexposer$canDraw()) return;
        BiomeHighlight.draw((MapProcessor) GuiMapFields.mapProcessor.get(this), matrix, flooredCameraX, flooredCameraZ,
                GuiMapFields.cameraX.getDouble(this), GuiMapFields.cameraZ.getDouble(this));
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true, require = 0)
    private void skysmapexposer$scrollBar(double mouseX, double mouseY, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
        if (MapBar.scrolled(mouseX, mouseY, scrollY)) cir.setReturnValue(true);
    }

    /** Panels before Xaero's buttons ({@link PanelClicks}): a click over one is its alone. */
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, require = 0)
    private void skysmapexposer$panelsFirst(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        if (PanelClicks.clicked((Screen) (Object) this, event, doubleClick)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true, require = 0)
    private void skysmapexposer$panelsLetGo(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (PanelClicks.released((Screen) (Object) this, event)) cir.setReturnValue(true);
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

    /** Where the last of Xaero's lines at the top of the map went this frame, or -1 for none. */
    @Unique private int skysmapexposer$topLineY = -1;

    @Inject(method = "extractRenderState", at = @At("HEAD"), require = 0)
    private void skysmapexposer$resetTopLines(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        skysmapexposer$topLineY = -1;
    }

    /**
     * Notes where Xaero puts its first two lines at the top of the map: the block under the mouse,
     * then its biome. Either can be switched off in Xaero, so the other dimension's coordinates go
     * under whichever was drawn last.
     */
    @ModifyArg(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lxaero/map/graphics/MapRenderHelper;drawCenteredStringWithBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIFFFF)V", ordinal = 0),
            index = 4,
            require = 0
    )
    private int skysmapexposer$coordinatesLine(int y) {
        skysmapexposer$topLineY = Math.max(skysmapexposer$topLineY, y);
        return y;
    }

    @ModifyArg(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lxaero/map/graphics/MapRenderHelper;drawCenteredStringWithBackground(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIFFFF)V", ordinal = 1),
            index = 4,
            require = 0
    )
    private int skysmapexposer$biomeLine(int y) {
        skysmapexposer$topLineY = Math.max(skysmapexposer$topLineY, y);
        return y;
    }

    /**
     * The other dimension's coordinates for the block under the mouse, one line under Xaero's own,
     * in its style (Xaero puts its lines 10 apart). Only while Xaero shows the mouse's coordinates.
     */
    @Inject(method = "extractRenderState", at = @At("TAIL"), require = 0)
    private void skysmapexposer$otherDimensionLine(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (skysmapexposer$topLineY < 0 || HookErrors.isOff(HookErrors.OTHER_DIMENSION)) return;
        if (!XaeroField.all(GuiMapFields.mouseBlockPosX, GuiMapFields.mouseBlockPosZ)) return;
        try {
            Screen screen = (Screen) (Object) this;
            int y = skysmapexposer$topLineY + 10;
            String line = OtherDimensionCoords.forWorldMap(GuiMapFields.mouseBlockPosX.getInt(this), GuiMapFields.mouseBlockPosZ.getInt(this));
            if (line != null) {
                MapRenderHelper.drawCenteredStringWithBackground(graphics, Minecraft.getInstance().font, line,
                        screen.width / 2, y, -1, 0f, 0f, 0f, 0.4f);
            }
        } catch (Throwable e) {
            HookErrors.failed(HookErrors.OTHER_DIMENSION, e);
        }
    }

    @Inject(method = "getRightClickOptions", at = @At("RETURN"), require = 0)
    private void skysmapexposer$addMenuOptions(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        if (!XaeroField.all(GuiMapFields.rightClickX, GuiMapFields.rightClickZ, GuiMapFields.rightClickDim, GuiMapFields.mapTileSelection)) return;
        @SuppressWarnings("unchecked")
        ResourceKey<Level> dimension = (ResourceKey<Level>) GuiMapFields.rightClickDim.get(this);
        MapMenus.addMapOptions(cir.getReturnValue(), (IRightClickableElement) this,
                GuiMapFields.rightClickX.getInt(this), GuiMapFields.rightClickZ.getInt(this), dimension,
                (MapTileSelection) GuiMapFields.mapTileSelection.get(this));
    }
}
