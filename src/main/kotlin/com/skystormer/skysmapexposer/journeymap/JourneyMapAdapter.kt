package com.skystormer.skysmapexposer.journeymap

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapAdapter
import com.skystormer.skysmapexposer.Session
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.common.Context
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import java.util.UUID

/**
 * JourneyMap for the rest of the mod ([MapAdapter]), through its API. Only reached through
 * [com.skystormer.skysmapexposer.MapMods.adapter], so its classes load only with JourneyMap
 * installed. It keeps nothing but the API handle: everything it shows on JourneyMap is removed on
 * leaving a world.
 */
object JourneyMapAdapter : MapAdapter {

    const val MOD_ID = "skysmapexposer"

    /** Set by [MapExposerPlugin] once JourneyMap has started it; null until then. */
    @Volatile
    var api: IClientAPI? = null

    private var missingSaid = false

    override fun init() {}

    override fun checkHooks() {}

    // JourneyMap starts its plugins with itself, so by the time a world opens it has, or never will.
    override fun join() {
        if (api == null && !missingSaid) {
            missingSaid = true
            Log.warn("JourneyMap has not started this mod's plugin; nothing will be drawn on it")
        }
    }

    override fun leave() {
        api?.removeAll(MOD_ID)
    }

    override fun tick(client: Minecraft, session: Session?) {}

    override fun commands(root: LiteralArgumentBuilder<FabricClientCommandSource>) {}

    override fun status(say: (String) -> Unit) {
        say("JourneyMap plugin: " + if (api != null) "§aloaded" else "§cnot started (needs JourneyMap's API 2)")
    }

    /** The full-screen map's dimension while it is open; otherwise yours, which it opens on. */
    override fun shownDimension(): String? {
        val shown = fullscreen()?.dimension ?: Minecraft.getInstance().level?.dimension()
        return shown?.identifier()?.toString()
    }

    override fun cavesShown(session: Session): String? {
        val state = fullscreen() ?: return null
        return state.dimension?.identifier()?.toString()?.takeIf { state.mapType == Context.MapType.Underground }
    }

    // Moving JourneyMap's full-screen map comes with its menus (plan S6).
    override fun goTo(dimension: String, x: Int, z: Int, from: Screen?, player: UUID?): Boolean = false

    private fun fullscreen() = api?.getUIState(Context.UI.Fullscreen)?.takeIf { it.active }
}
