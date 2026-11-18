package com.skystormer.skysmapexposer.journeymap

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapAdapter
import com.skystormer.skysmapexposer.MapLayers
import com.skystormer.skysmapexposer.SeaFloor
import com.skystormer.skysmapexposer.Session
import com.skystormer.skysmapexposer.ShareFormat
import com.skystormer.skysmapexposer.ShareableWaypoint
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.client.util.UIState
import journeymap.api.v2.common.Context
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component
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

    /** When you joined, until this mod's leftover temporary waypoints have been tidied away; 0 once they have. */
    private var tidyAfter = 0L

    override fun init() = JourneyMapMenus.register()

    override fun checkHooks() {}

    // JourneyMap starts its plugins with itself, so by the time a world opens it has, or never will.
    override fun join() {
        tidyAfter = System.currentTimeMillis() + TIDY_DELAY_MS
        if (api == null && !missingSaid) {
            missingSaid = true
            Log.warn("JourneyMap has not started this mod's plugin; nothing will be drawn on it")
        }
    }

    override fun leave() {
        JourneyMapDownload.forget()
        api?.let(JourneyMapWaypoints::forget)
        api?.removeAll(MOD_ID)
        JourneyMapPins.forget()
        JourneyMapTerrain.forget()
        JourneyMapMenus.forget()
    }

    override fun tick(client: Minecraft, session: Session?) {
        val api = api ?: return
        if (client.level == null) return
        if (tidyAfter != 0L && System.currentTimeMillis() >= tidyAfter) {
            tidyAfter = 0L
            JourneyMapWaypoints.tidy(api)
        }
        JourneyMapMenus.tick(client)
        JourneyMapDownload.tick()
        JourneyMapPins.tick(api, shownDimension())
        JourneyMapTerrain.tick(api, session)
    }

    override fun commands(root: LiteralArgumentBuilder<FabricClientCommandSource>) {
        root.then(
            ClientCommands.literal("download")
                .then(ClientCommands.literal("all").executes { it.source.sendFeedback(Component.literal(JourneyMapDownload.start(unexploredOnly = false))); 1 })
                .then(ClientCommands.literal("unexplored").executes { it.source.sendFeedback(Component.literal(JourneyMapDownload.start(unexploredOnly = true))); 1 })
                .then(ClientCommands.literal("region").then(
                    ClientCommands.argument("x", IntegerArgumentType.integer()).then(
                        ClientCommands.argument("z", IntegerArgumentType.integer())
                            .executes { regionDownload(it, unexploredOnly = true) }
                            .then(ClientCommands.literal("all").executes { regionDownload(it, unexploredOnly = false) })
                    )
                ))
                .then(ClientCommands.literal("cancel").executes {
                    it.source.sendFeedback(Component.literal(if (JourneyMapDownload.cancel()) "Stopping the download after the region it is on" else "No download into JourneyMap's map is running"))
                    1
                })
                .then(ClientCommands.literal("undo").executes { it.source.sendFeedback(Component.literal(JourneyMapDownload.undo())); 1 })
        )
    }

    /** `download region <x> <z>`: the region holding block ([x], [z]). */
    private fun regionDownload(context: CommandContext<FabricClientCommandSource>, unexploredOnly: Boolean): Int {
        val x = IntegerArgumentType.getInteger(context, "x")
        val z = IntegerArgumentType.getInteger(context, "z")
        val region = Math.floorDiv(x, JourneyMapFiles.REGION) to Math.floorDiv(z, JourneyMapFiles.REGION)
        context.source.sendFeedback(Component.literal(JourneyMapDownload.start(unexploredOnly, region)))
        return 1
    }

    override fun status(say: (String) -> Unit) {
        say("JourneyMap plugin: " + if (api != null) "§aloaded" else "§cnot started (needs JourneyMap's API 2)")
        if (api != null) say("On JourneyMap: ${JourneyMapPins.summary()}, ${JourneyMapTerrain.summary()}")
        say("JourneyMap dimension hook: " + if (JourneyMapDimension.hookRan) "§aworking" else "§enot seen yet (open the full-screen map)")
    }

    /** The full-screen map's dimension while it is open; otherwise yours, which it opens on. */
    override fun shownDimension(): String? {
        val shown = fullscreen()?.dimension ?: Minecraft.getInstance().level?.dimension()
        return shown?.identifier()?.toString()
    }

    /** The dimension shown, while JourneyMap shows a layer BlueMap's picture does not belong in. */
    override fun cavesShown(session: Session): String? {
        val state = fullscreen() ?: return null
        val dimension = state.dimension?.identifier()?.toString() ?: return null
        val map = session.server.mapFor(dimension) ?: return null
        return dimension.takeIf { !blueMapLayerShown(session, dimension, map, state) }
    }

    /**
     * Whether JourneyMap is showing the layer BlueMap's picture belongs in, by Xaero's rule
     * ([MapLayers.blueMapLayer]): its day map is the surface and its cave map a cave layer.
     */
    fun blueMapLayerShown(session: Session, dimension: String, map: String, state: UIState): Boolean {
        val shown = if (state.mapType == Context.MapType.Underground) state.chunkY ?: 0 else MapLayers.SURFACE
        return MapLayers.blueMapLayer(shown, { SeaFloor.hasCeiling(dimension) }) { session.view(map) } != null
    }

    override fun goTo(dimension: String, x: Int, z: Int, from: Screen?, player: UUID?): Boolean =
        JourneyMapMenus.goTo(dimension, x, z, from)

    override fun downloadStatus(): String? = JourneyMapDownload.status()?.takeIf { JourneyMapDownload.running }

    override fun cancelDownload(): Boolean = JourneyMapDownload.cancel()

    override fun myWaypoints(): Pair<String, List<ShareableWaypoint>>? = api?.let(JourneyMapWaypoints::mine)

    override fun addWaypoints(dimension: String, from: String, waypoints: List<ShareFormat.SharedWaypoint>): String =
        api?.let { JourneyMapWaypoints.addShared(it, dimension, from, waypoints) } ?: "JourneyMap has not started this mod's plugin"

    /** How long after joining before leftover temporary waypoints are looked for: a server sends its waypoints a moment after you join. */
    private const val TIDY_DELAY_MS = 5_000L

    private fun fullscreen() = api?.getUIState(Context.UI.Fullscreen)?.takeIf { it.active }
}
