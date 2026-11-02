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

    // 26.2 hands the dropped files over as paths, with a count of the ones it could not read.
    // Optional, like the hooks into Xaero: a game version that changes onDrop loses dropping files
    // (/mapexposer says so, and /mapexposer terrain open still works), not the game.
    @Inject(method = "onDrop", at = @At("HEAD"), cancellable = true, require = 0)
    private void skysmapexposer$onDrop(long window, List<Path> paths, int failed, CallbackInfo ci) {
        if (TerrainFiles.INSTANCE.dropped(paths)) ci.cancel();
    }
}
