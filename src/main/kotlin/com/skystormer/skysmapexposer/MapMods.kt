package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.journeymap.JourneyMapAdapter
import com.skystormer.skysmapexposer.xaero.XaeroAdapter
import net.fabricmc.loader.api.FabricLoader

/** Which map mods are installed, asked in one place. Loaded mods can't change while the game runs. */
object MapMods {

    private fun loaded(vararg ids: String) = ids.any(FabricLoader.getInstance()::isModLoaded)

    val xaeroWorldMap: Boolean by lazy { loaded("xaeroworldmap") }

    /** Xaero's Minimap, or its Fair-Play build: the same code with a different mod id. */
    val xaeroMinimap: Boolean by lazy { loaded("xaerominimap", "xaerominimapfair") }

    val journeyMap: Boolean by lazy { loaded("journeymap") }

    /** Whether any map this mod draws on is installed; without one it only says which to get. */
    val any: Boolean get() = xaeroWorldMap || journeyMap

    /**
     * The installed map, for everything that needs one; null with none. With both installed,
     * Xaero's for now: drawing on both at once comes with JourneyMap's layers (plan S4).
     */
    val adapter: MapAdapter? by lazy {
        when {
            xaeroWorldMap -> XaeroAdapter
            journeyMap -> JourneyMapAdapter
            else -> null
        }
    }
}
