package com.skystormer.skysmapexposer.mixin.journeymap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.skystormer.skysmapexposer.journeymap.JourneyMapDimension;
import journeymap.client.properties.InGameMapProperties;
import journeymap.client.render.draw.RadarDrawStepFactory;
import journeymap.client.render.map.Renderer;
import journeymap.client.ui.fullscreen.Fullscreen;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps JourneyMap's full-screen map on the dimension picked with this mod's toggle
 * ({@link JourneyMapDimension}). Each refresh JourneyMap compares the map's dimension with the one
 * you are in and, if they differ, switches back to yours; for the full-screen map's own state, and
 * only while a dimension is picked, this hands it the picked one instead. The minimap's state is
 * left alone. Optional (require = 0): a JourneyMap that has moved this costs the toggle, not the game.
 *
 * And while that map shows another dimension than yours, its radar (the animals, mobs, villagers and
 * players around you) is left off it, as on Xaero's map: they are not in the dimension shown.
 */
@Mixin(targets = "journeymap.client.model.map.MapState", remap = false)
public abstract class MapStateMixin {

    @WrapOperation(
        method = "refresh",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;dimension()Lnet/minecraft/resources/ResourceKey;")
    )
    private ResourceKey<Level> skysmapexposer$keepDimension(Level level, Operation<ResourceKey<Level>> original) {
        ResourceKey<Level> actual = original.call(level);
        return (Object) this == Fullscreen.state() ? JourneyMapDimension.keep(actual) : actual;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(
        method = "generateDrawSteps",
        at = @At(value = "INVOKE", target = "Ljourneymap/client/render/draw/RadarDrawStepFactory;prepareSteps(Ljava/util/List;Ljourneymap/client/render/map/Renderer;Ljourneymap/client/properties/InGameMapProperties;)Ljava/util/List;")
    )
    private List skysmapexposer$radarOnlyAtHome(RadarDrawStepFactory factory, List entities, Renderer renderer, InGameMapProperties properties, Operation<List> original) {
        if ((Object) this == Fullscreen.state() && JourneyMapDimension.showingOther()) return new ArrayList();
        return original.call(factory, entities, renderer, properties);
    }
}
