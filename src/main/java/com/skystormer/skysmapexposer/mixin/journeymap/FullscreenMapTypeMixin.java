package com.skystormer.skysmapexposer.mixin.journeymap;

import com.skystormer.skysmapexposer.journeymap.JourneyMapDimension;
import journeymap.client.model.map.MapType;
import journeymap.client.ui.fullscreen.Fullscreen;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps the dimension picked with this mod's toggle ({@link JourneyMapDimension}) when a button of
 * JourneyMap's changes the full-screen map's type: the caves, day, night and topo buttons build the
 * new type from where you are, your dimension included, which would take the map back to yours.
 * Every one of them goes through this method, so the picked dimension is put in here. Optional
 * (require = 0): a JourneyMap that has moved this sends the map home on a type change, nothing worse.
 */
@Mixin(value = Fullscreen.class, remap = false)
public abstract class FullscreenMapTypeMixin {

    @ModifyVariable(method = "updateMapType(Ljourneymap/client/model/map/MapType;)V", at = @At("HEAD"), argsOnly = true)
    private MapType skysmapexposer$keepPickedDimension(MapType type) {
        if (type == null) return type;
        ResourceKey<Level> keep = JourneyMapDimension.keep(type.dimension);
        return keep.equals(type.dimension) ? type : MapType.from(type.name, type.vSlice, keep);
    }
}
