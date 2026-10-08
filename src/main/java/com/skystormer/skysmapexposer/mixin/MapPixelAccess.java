package com.skystormer.skysmapexposer.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.map.region.MapPixel;

/** The light and glow of one of Xaero's map pixels, which it keeps to itself; for saving terrain to a file. */
@Mixin(value = MapPixel.class, remap = false)
public interface MapPixelAccess {

    @Accessor("light")
    byte skysmapexposer$light();

    @Accessor("glowing")
    boolean skysmapexposer$glowing();
}
