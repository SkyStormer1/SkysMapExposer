package com.skystormer.skysmapexposer

import net.fabricmc.loader.api.FabricLoader

/** Which map mods are installed, asked in one place. Loaded mods can't change while the game runs. */
object MapMods {

    /** Xaero's Minimap, or its Fair-Play build: the same code with a different mod id. */
    val xaeroMinimap: Boolean by lazy { listOf("xaerominimap", "xaerominimapfair").any(FabricLoader.getInstance()::isModLoaded) }
}
