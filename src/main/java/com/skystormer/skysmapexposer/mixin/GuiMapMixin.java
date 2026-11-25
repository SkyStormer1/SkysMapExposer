package com.skystormer.skysmapexposer.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.skystormer.skysmapexposer.xaero.BiomeHighlight;
import com.skystormer.skysmapexposer.HookErrors;
import com.skystormer.skysmapexposer.MapCamera;
import com.skystormer.skysmapexposer.xaero.MapMenus;
import com.skystormer.skysmapexposer.xaero.MapView;
import com.skystormer.skysmapexposer.OtherDimensionCoords;
import com.skystormer.skysmapexposer.xaero.Overlay;
import com.skystormer.skysmapexposer.gui.MapBar;
import com.skystormer.skysmapexposer.gui.PanelClicks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.skystormer.skysmapexposer.xaero.XaeroMenus;
import xaero.map.MapProcessor;
import xaero.map.animation.SlowingAnimation;
import xaero.map.graphics.MapRenderHelper;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.MapTileSelection;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;

/**
 * What this mod adds to Xaero's world map screen.
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
 * <p>Right-click menu: this mod's lines ("Copy coordinates", "Players…", "Download" and the rest) on
 * the menu Xaero shows when you right-click the map itself, laid out with everyone's by XaeroMenus,
 * which also needs to know where a menu opens. {@code rightClickDim} is Xaero's own reading of which
 * dimension that click landed in, so the copied coordinates can say where they are.
 *
 * <p>Player arrow: left off while the map shows a dimension you are not in ({@link MapView}).
 *
 * <p>Other dimension: under Xaero's readout of the block under the mouse, the same block's
 * coordinates in the other dimension ({@link OtherDimensionCoords}).
 *
 * <p>Layers: the widgets, panels included, are drawn in a layer above everything Xaero drew on the
 * map, and the right-click menu in one above them, so a panel always covers the map's text.
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

    // Xaero's own fields. A Xaero that renames one of these stops the game starting.
    @Shadow private double cameraX;
    @Shadow private double cameraZ;
    @Shadow private MapProcessor mapProcessor;
    @Shadow private int rightClickX;
    @Shadow private int rightClickY;
    @Shadow private int rightClickZ;
    @Shadow private ResourceKey<Level> rightClickDim;
    @Shadow private MapTileSelection mapTileSelection;
    @Shadow private int[] cameraDestination;
    @Shadow private SlowingAnimation cameraDestinationAnimX;
    @Shadow private SlowingAnimation cameraDestinationAnimZ;
    @Shadow private boolean shouldResetCameraPos;
    @Shadow private static boolean attachedCamera;
    @Shadow private static double destScale;
    @Shadow private int mouseBlockPosX;
    @Shadow private int mouseBlockPosZ;
    @Shadow private double prevPlayerDimDiv;
    @Shadow private Entity player;

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
     *
     * Xaero also moves the camera when the map's dimension changes, by your own position times the
     * change in scale, so that you keep looking at the matching spot. It does that on the first
     * frame it draws with a scale different from the one it last used ({@code prevPlayerDimDiv}),
     * which on a big map can be well after the switch. A camera put on a spot asked for would then
     * be carried off with it: with you standing near that spot, to about twice its coordinates. So
     * the scale it last used is set to the one of the dimension now shown, and it has nothing to
     * shift.
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
        try {
            var registry = mapProcessor.getWorldDimensionTypeRegistry();
            var world = mapProcessor.getMapWorld();
            if (registry != null && world != null && world.getCurrentDimension() != null && player != null) {
                prevPlayerDimDiv = world.getCurrentDimension().calculateDimDiv(registry, player.level().dimensionType());
            }
        } catch (Throwable ignored) {
            // Without it the jump still lands; Xaero may just shift it once afterwards.
        }
    }

    /**
     * Xaero eases its zoom toward {@code destScale} every frame, and clamps it to its own limits, so
     * setting that is the same smooth zoom the mouse wheel gives.
     */
    @Override
    public void skysmapexposerZoomIn(double scale) {
        if (destScale < scale) destScale = scale;
    }

    @Override
    public void skysmapexposerSetZoom(double scale) {
        destScale = scale;
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
        Overlay.draw(mapProcessor, matrix, flooredCameraX, flooredCameraZ,
                cameraX, cameraZ, rendererProvider);
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
        BiomeHighlight.draw(mapProcessor, matrix, flooredCameraX, flooredCameraZ,
                cameraX, cameraZ);
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
     * Just before the screen draws its widgets: the other dimension's coordinates for the block under
     * the mouse, one line under Xaero's own, in its style (Xaero puts its lines 10 apart), only while
     * Xaero shows the mouse's coordinates; then a new layer, so the widgets (the panels among them)
     * cover everything Xaero drew on the map. Within one layer the game draws all text after all
     * shapes, so Xaero's readout and this line used to show through a panel over them.
     */
    @Inject(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lxaero/lib/client/gui/ScreenBase;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"),
            require = 0
    )
    private void skysmapexposer$beforeWidgets(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (skysmapexposer$topLineY >= 0 && !HookErrors.isOff(HookErrors.OTHER_DIMENSION)) {
            try {
                Screen screen = (Screen) (Object) this;
                int y = skysmapexposer$topLineY + 10;
                String line = OtherDimensionCoords.forWorldMap(mouseBlockPosX, mouseBlockPosZ);
                if (line != null) {
                    MapRenderHelper.drawCenteredStringWithBackground(graphics, Minecraft.getInstance().font, line,
                            screen.width / 2, y, -1, 0f, 0f, 0f, 0.4f);
                }
            } catch (Throwable e) {
                HookErrors.failed(HookErrors.OTHER_DIMENSION, e);
            }
        }
        graphics.nextStratum();
    }

    /** Xaero's right-click menu in a layer of its own, over the panels, as it opens over everything else. */
    @Inject(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lxaero/map/gui/dropdown/rightclick/GuiRightClickMenu;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"),
            require = 0
    )
    private void skysmapexposer$beforeMenu(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        graphics.nextStratum();
    }

    /** Where a right-click menu opens, so a group of it can open in its place (XaeroMenus). */
    @Inject(method = "handleRightClick", at = @At("HEAD"), require = 0)
    private void skysmapexposer$menuOpened(IRightClickableElement element, int x, int y, CallbackInfo ci) {
        XaeroMenus.opened(element, x, y);
    }

    @Inject(method = "getRightClickOptions", at = @At("RETURN"), require = 0)
    private void skysmapexposer$addMenuOptions(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        MapMenus.addMapOptions(cir.getReturnValue(), (IRightClickableElement) this,
                rightClickX, rightClickZ, rightClickY, rightClickDim, mapTileSelection);
    }
}
