package com.skystormer.skysmapexposer.plugin;

import com.skystormer.skysmapexposer.MapMods;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies a mixin into a map mod's code only when that mod is installed, so the game still loads
 * with either map mod, both, or none. Mixins into Minecraft itself always apply.
 */
public final class MapMixinPlugin implements IMixinConfigPlugin {

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // The minimap's classes (xaero.hud, xaero.common); its hooks also lean on the world map.
        if (targetClassName.startsWith("xaero.hud.") || targetClassName.startsWith("xaero.common.")) {
            return MapMods.INSTANCE.getXaeroMinimap() && MapMods.INSTANCE.getXaeroWorldMap();
        }
        // The world map's classes, and Xaero's Lib, which every hook into it is for.
        if (targetClassName.startsWith("xaero.")) return MapMods.INSTANCE.getXaeroWorldMap();
        if (targetClassName.startsWith("journeymap.")) return MapMods.INSTANCE.getJourneyMap();
        return true;
    }

    @Override public void onLoad(String mixinPackage) {}
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
