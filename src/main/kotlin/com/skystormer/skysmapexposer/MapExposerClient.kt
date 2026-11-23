package com.skystormer.skysmapexposer

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

object MapExposerClient : ClientModInitializer {

    private var ticks = 0L

    private var hookChecked = false
    /** The one line saying there is no map mod: once a game, not every world. */
    private var noMapSaid = false
    /** The heads of locked players, as a HUD element of this mod's own. */
    private val BEACON: Identifier = Identifier.fromNamespaceAndPath("skysmapexposer", "locked_players")

    /** Pings the block you are looking at. Unbound until you pick a key in Controls. */
    private val pingKey = KeyMapping(
        "key.skysmapexposer.ping",
        //? if >=26.3 {
        InputConstants.Type.KEYBOARD,
        //?} else {
        /*InputConstants.Type.KEYSYM,
        *///?}
        InputConstants.UNKNOWN.value,
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath("skysmapexposer", "map_exposer")),
    )

    /**
     * Whether one of this mod's optional injections reached [className]. Every injection here is
     * `require = 0`, so a Xaero that has moved the code costs the feature and not the game — but
     * that also means a failed injection is silent. Loading the class and looking for the method
     * mixin would have added is the only way to tell, and it beats wondering why nothing happened.
     */
    fun injected(className: String, method: String): Boolean = try {
        Class.forName(className).declaredMethods.any { it.name.contains(method) }
    } catch (e: Throwable) {
        false
    }

    /** One step of leaving a world; if it fails, the rest still run and the error is logged. */
    private inline fun leaving(what: String, step: () -> Unit) {
        try {
            step()
        } catch (e: Throwable) {
            Log.error("Could not let go of $what on leaving the world", e)
        }
    }

    /** "Texture memory 180 / 256 MB", and why it is held back, if it is. */
    fun textureMemory(session: Session): String {
        val pacer = FramePacer.game
        val mb = 1024 * 1024
        val notes = listOfNotNull(
            "drawn ${session.tiles.coarser} step(s) coarser to fit".takeIf { session.tiles.coarser > 0 },
            "budget halved: Java memory is low".takeIf { pacer.heapLow },
            "uploading ${pacer.uploads} per frame: the game is busy".takeIf { pacer.slow },
        )
        return "Texture memory ${session.tiles.heldBytes / mb} / ${pacer.budgetBytes() / mb} MB" + if (notes.isEmpty()) "" else " (${notes.joinToString("; ")})"
    }

    /** Whether dropping map files onto the game window reaches [TerrainFiles] (`MouseHandlerMixin`). */
    fun dropHookInstalled() = injected("net.minecraft.client.MouseHandler", "skysmapexposer\$onDrop")

    override fun onInitializeClient() {
        Config.load()
        KeyMappingHelper.registerKeyMapping(pingKey)

        // Under the whole HUD, like anything else out in the world: the minimap (Xaero's draws
        // just after this point), crosshair, hotbar, health and XP bars and chat all go over it.
        HudElementRegistry.attachElementBefore(PlayerBeacon.DRAWN_BEFORE, BEACON, PlayerBeacon)

        MapMods.adapter?.init()
        ChatShare.register()

        // On the client thread, like leaving, so a quick rejoin never starts before the last leave ends.
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            client.execute {
                Session.start(addressOf(client))
                MapMods.adapter?.join()
                CaveNotice.forget()
            }
        }
        // In single player Fabric calls this on the network thread, where textures can't be freed,
        // so leaving is done on the client thread. And each step on its own, nothing thrown out of
        // here: Fabric only lets go of the connection once every leave handler has returned, so
        // one that throws keeps the whole world you left in memory (the game swallows the error).
        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            client.execute {
                leaving("the BlueMap session") { Session.end() }
                leaving("terrain fill") { com.skystormer.skysmapexposer.terrain.TerrainFill.forget() }
                leaving("chat sharing") { ChatShare.forget() }
                leaving("shared map files") { SharedTerrain.forget() }
                leaving("pings") { PlayerPings.forget() }
                leaving("the cave mode note") { CaveNotice.forget() }
                leaving("the map") { MapMods.adapter?.leave() }
                leaving("hook errors") { HookErrors.forget() }
            }
        }

        // A chunk arriving from the server is what makes Xaero redraw it, so this is the moment
        // your map of it becomes current.
        ClientChunkEvents.CHUNK_LOAD.register { level, chunk ->
            val session = Session.current ?: return@register
            val dimension = dimensionOf(level)
            session.visits.record(dimension, chunk.pos.x(), chunk.pos.z(), Clock.nowMinutes())
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            if (!hookChecked) {
                hookChecked = true
                MapMods.adapter?.checkHooks()
                if (!dropHookInstalled()) Log.warn("Could not hook dropping files onto the game; open map files with /mapexposer terrain open")
            }
            client.player?.let { player ->
                Config.takeLoadProblem()?.let { player.sendSystemMessage(Component.literal("§e$it")) }
                if (!noMapSaid && !MapMods.any) {
                    noMapSaid = true
                    player.sendSystemMessage(Component.literal("§eSky's Map Exposer has no map to show anything on: install Xaero's World Map or JourneyMap"))
                }
            }
            MapMods.adapter?.tick(client, Session.current)
            ChatShare.tick()
            ChatCoords.tick()
            PlayerPings.tick()
            while (pingKey.consumeClick()) Ui.say(PlayerPings.sendLookedAt())
            MapPrep.tick(Session.current)
            CaveNotice.tick(client)
            AutoTest.tick(client)
            val session = Session.current ?: return@register
            ticks++
            // Chunks you stay near are kept up to date by Xaero, so they stay current here too.
            if (ticks % 400 == 0L) recordLoadedChunks(client, session)
            if (ticks % 6000 == 0L) {
                session.visits.save()
                session.gaps.save()
                session.unshowable.save()
            }
        }

        Commands.register()
    }

    /**
     * The server's address, or `singleplayer` for a world on this computer: BlueMap also runs in
     * single player, on its own local web server, and can be listed in the config under that name.
     */
    fun addressOf(client: Minecraft): String? =
        client.currentServer?.ip ?: if (client.hasSingleplayerServer()) "singleplayer" else null

    fun dimensionOf(level: ClientLevel): String = level.dimension().identifier().toString()

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

}
