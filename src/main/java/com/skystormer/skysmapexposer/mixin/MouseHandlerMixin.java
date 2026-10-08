package com.skystormer.skysmapexposer.mixin;

import com.skystormer.skysmapexposer.TerrainFiles;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;
import java.util.List;

/**
 * Map files dropped onto the game window open wherever you are, with a screen up or not
 * ({@link TerrainFiles#dropped}). A drop of nothing but map files goes no further.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(method = "onDrop", at = @At("HEAD"), cancellable = true)
    private void skysmapexposer$onDrop(long window, List<Path> paths, int failed, CallbackInfo ci) {
        if (TerrainFiles.INSTANCE.dropped(paths)) ci.cancel();
    }
}
