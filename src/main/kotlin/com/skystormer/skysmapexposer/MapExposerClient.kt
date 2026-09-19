package com.skystormer.skysmapexposer

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component

object MapExposerClient : ClientModInitializer {

    private var ticks = 0L
    private var hookChecked = false
    private var worldMapPinsAdded = false
    private var minimapPinsAdded = false

    /**
     * Whether the mixin made it into Xaero's map screen. It is optional, so that an unsupported
     * Xaero version costs the backfill and not the game; this is how that case gets noticed.
     * Loading the class is harmless: Xaero's own screen code does nothing until it is opened.
     */
    fun hookInstalled(): Boolean = try {
        Class.forName("xaero.map.gui.GuiMap").declaredMethods.any { it.name.contains("drawBackfill") }
    } catch (e: Throwable) {
        Log.error("Could not look for the Xaero hook", e)
        false
    }

    fun minimapHookInstalled(): Boolean = try {
        Class.forName("xaero.common.mods.SupportXaeroWorldmap").declaredMethods.any { it.name.contains("drawBackfill") }
    } catch (e: Throwable) {
        false
    }

    override fun onInitializeClient() {
        Config.load()

        ClientPlayConnectionEvents.JOIN.register { _, _, client -> Session.start(addressOf(client)) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> Session.end() }

        // A chunk arriving from the server is what makes Xaero redraw it, so this is the moment
        // your map of it becomes current.
        ClientChunkEvents.CHUNK_LOAD.register { level, chunk ->
            val session = Session.current ?: return@register
            val dimension = dimensionOf(level)
            session.visits.record(dimension, chunk.pos.x(), chunk.pos.z(), Clock.nowMinutes())
            session.forgetGap(dimension, chunk.pos.x(), chunk.pos.z())
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!hookChecked) {
                hookChecked = true
                if (!hookInstalled()) Log.warn("This version of Xaero's World Map is not supported; nothing will be drawn on it")
                if (FabricLoader.getInstance().isModLoaded("xaerominimap") && !minimapHookInstalled()) {
                    Log.warn("This version of Xaero's Minimap is not supported; nothing will be drawn on it")
                }
            }
            addPins()
            val session = Session.current ?: return@register
            ticks++
            // Chunks you stay near are kept up to date by Xaero, so they stay current here too.
            if (ticks % 400 == 0L) recordLoadedChunks(client, session)
            if (ticks % 6000 == 0L) session.visits.save()
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("mapexposer")
                    .executes(::status)
                    .then(ClientCommands.literal("on").executes { setEnabled(it, true) })
                    .then(ClientCommands.literal("off").executes { setEnabled(it, false) })
                    .then(ClientCommands.literal("reload").executes(::reload))
                    .then(ClientCommands.literal("refresh").executes(::refresh))
                    .then(
                        ClientCommands.literal("stale").then(
                            ClientCommands.argument("days", DoubleArgumentType.doubleArg(0.0, 3650.0)).executes(::setStale)
                        )
                    )
            )
        }
    }

    /**
     * Registers the marker pins with Xaero's world map and minimap, once each has started. A
     * failure here costs the pins, not the game.
     */
    private fun addPins() {
        if (!worldMapPinsAdded) {
            worldMapPinsAdded = try {
                MarkerElements.register()
            } catch (e: Throwable) {
                Log.error("Could not add BlueMap markers to Xaero's world map", e)
                true
            }
        }
        if (!minimapPinsAdded) {
            minimapPinsAdded = if (!FabricLoader.getInstance().isModLoaded("xaerominimap")) {
                true
            } else {
                try {
                    MinimapMarkerElements.register()
                } catch (e: Throwable) {
                    Log.error("Could not add BlueMap markers to Xaero's minimap", e)
                    true
                }
            }
        }
    }

    /**
     * The server's address, or `singleplayer` for a world on this computer: BlueMap also runs in
     * single player, on its own local web server, and can be listed in the config under that name.
     */
    fun addressOf(client: Minecraft): String? =
        client.currentServer?.ip ?: if (client.hasSingleplayerServer()) "singleplayer" else null

    private fun dimensionOf(level: ClientLevel): String = level.dimension().identifier().toString()

    private fun recordLoadedChunks(client: Minecraft, session: Session) {
        val level = client.level ?: return
        val player = client.player ?: return
        val dimension = dimensionOf(level)
        val now = Clock.nowMinutes()
        val radius = client.options.effectiveRenderDistance
        val centreX = player.chunkPosition().x()
        val centreZ = player.chunkPosition().z()
        for (chunkX in centreX - radius..centreX + radius) {
            for (chunkZ in centreZ - radius..centreZ + radius) {
                if (level.chunkSource.hasChunk(chunkX, chunkZ)) session.visits.record(dimension, chunkX, chunkZ, now)
            }
        }
    }

    private fun status(context: CommandContext<FabricClientCommandSource>): Int {
        val source = context.source
        val session = Session.current
        source.sendFeedback(Component.literal("§bSky's Map Exposer§r ${if (Config.enabled) "§aon" else "§coff"}§r, stale after ${Config.staleDays} day(s)"))
        val hook = when {
            Overlay.hookRan -> "§aworking"
            hookInstalled() -> "§einstalled; open the world map"
            else -> "§cmissing: this Xaero's World Map version is not supported"
        }
        source.sendFeedback(Component.literal("Xaero hook: $hook"))
        if (session == null) {
            source.sendFeedback(Component.literal("§eThis server has no BlueMap in config/skysmapexposer.json"))
        } else {
            val level = Minecraft.getInstance().level
            val visited = level?.let { session.visits.count(dimensionOf(it)) } ?: 0
            source.sendFeedback(Component.literal("BlueMap: ${session.server.url}  maps: ${session.server.maps}"))
            source.sendFeedback(
                Component.literal(
                    "Chunks recorded here: $visited, Xaero region files: ${session.regionAges.count()}, " +
                        "tiles in memory: ${session.tiles.size} (${session.tiles.countIn(TileStore.State.FAILED)} failed)"
                )
            )
        }
        source.sendFeedback(Component.literal("§7${Overlay.lastSummary}"))
        return 1
    }

    private fun setEnabled(context: CommandContext<FabricClientCommandSource>, enabled: Boolean): Int {
        Config.enabled = enabled
        Config.save()
        context.source.sendFeedback(Component.literal("Sky's Map Exposer ${if (enabled) "§aon" else "§coff"}"))
        return 1
    }

    private fun setStale(context: CommandContext<FabricClientCommandSource>): Int {
        Config.staleDays = DoubleArgumentType.getDouble(context, "days")
        Config.save()
        context.source.sendFeedback(Component.literal("Your map now counts as out of date after ${Config.staleDays} day(s)"))
        return 1
    }

    private fun reload(context: CommandContext<FabricClientCommandSource>): Int {
        Config.load()
        Session.start(addressOf(Minecraft.getInstance()))
        context.source.sendFeedback(Component.literal("Reloaded config/skysmapexposer.json"))
        return 1
    }

    private fun refresh(context: CommandContext<FabricClientCommandSource>): Int {
        val session = Session.current
        if (session == null) {
            context.source.sendError(Component.literal("This server has no BlueMap in config/skysmapexposer.json"))
            return 0
        }
        session.tiles.refetchBefore = System.currentTimeMillis()
        session.tiles.clear()
        context.source.sendFeedback(Component.literal("Fetching BlueMap's tiles again"))
        return 1
    }
}
