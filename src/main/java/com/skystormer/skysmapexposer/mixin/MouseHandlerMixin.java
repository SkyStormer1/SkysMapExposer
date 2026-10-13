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

    // 26.3 hands the dropped files over as path strings.
    @Inject(method = "onDrop", at = @At("HEAD"), cancellable = true)
    private void skysmapexposer$onDrop(long window, List<String> paths, CallbackInfo ci) {
        if (TerrainFiles.INSTANCE.dropped(paths.stream().map(Path::of).toList())) ci.cancel();
    }
}
