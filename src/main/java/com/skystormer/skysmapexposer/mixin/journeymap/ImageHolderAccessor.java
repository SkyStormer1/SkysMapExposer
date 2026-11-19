package com.skystormer.skysmapexposer.mixin.journeymap;

import com.mojang.blaze3d.platform.NativeImage;
import journeymap.client.model.region.ImageHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Hands a region JourneyMap holds in memory a new picture, as JourneyMap itself does when it maps:
 * shown at once, and saved by JourneyMap at its next save. Lets a download or a shared map file go
 * into the regions on screen while the map is open, rather than waiting for JourneyMap to let go
 * ({@link com.skystormer.skysmapexposer.journeymap.JourneyMapFiles#replaceHeld}). Optional: without
 * it those regions wait, as before.
 */
@Mixin(value = ImageHolder.class, remap = false)
public interface ImageHolderAccessor {

    @Invoker("setImage")
    void skysmapexposer$setImage(NativeImage image);
}
