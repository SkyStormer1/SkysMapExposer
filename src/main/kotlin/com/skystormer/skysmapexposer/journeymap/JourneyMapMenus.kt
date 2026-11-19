package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.BiomePicks
import com.skystormer.skysmapexposer.ChatButton
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapPrep
import com.skystormer.skysmapexposer.OtherDimensionCoords
import com.skystormer.skysmapexposer.PlayerPings
import com.skystormer.skysmapexposer.Session
import com.skystormer.skysmapexposer.ShareFormat
import com.skystormer.skysmapexposer.Ui
import com.skystormer.skysmapexposer.WaypointRules
import com.skystormer.skysmapexposer.gui.MapBar
import com.skystormer.skysmapexposer.gui.PlayerListScreen
import com.skystormer.skysmapexposer.gui.WaypointShareScreen
import journeymap.api.v2.client.event.FullscreenRenderEvent
import journeymap.api.v2.client.event.PopupMenuEvent
import journeymap.api.v2.client.fullscreen.IFullscreen
import journeymap.api.v2.client.util.UIState
import journeymap.api.v2.common.event.FullscreenEventRegistry
import journeymap.client.ui.UIManager
import journeymap.client.ui.fullscreen.Fullscreen
import net.minecraft.core.BlockPos
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

/**
 * This mod's parts of JourneyMap's full-screen map, the same as on Xaero's: the right-click entries
 * (Copy coordinates, Ping here, Players…), Go to from the player list, and the line under the top
 * of the map with the other dimension's coordinates of the block under the mouse and how preparing
 * the map is going.
 *
 * It keeps only the full-screen map last seen open and a Go to waiting for it, let go of on leaving
 * a world ([forget]).
 */
internal object JourneyMapMenus {

    /** Starts a save of [area] (null: the whole map) of [dimension]; a big one asks first, then comes back to the map ([parent]). */
    private fun startSave(parent: Screen, dimension: String, area: ShareFormat.Area?) {
        val answer = JourneyMapTerrainSave.save(dimension, area, confirmed = false)
        if (!answer.confirm) return Ui.say(answer.text)
        Ui.open(ConfirmScreen(
            { yes ->
                Ui.open(parent)
                Ui.say(if (yes) JourneyMapTerrainSave.save(dimension, area, confirmed = true).text else "Not saved")
            },
            Component.literal("Save this much?"),
            Component.literal(answer.text),
            Component.literal("Save"),
            CommonComponents.GUI_CANCEL,
        ))
    }

    /** The full-screen map, while it is open: JourneyMap hands it over with each event. */
    private var fullscreen: IFullscreen? = null

    /** A Go to waiting for the full-screen map to open, and the dimension it is for. */
    private var pending: Pair<Int, Int>? = null
    private var pendingDimension: String? = null

    /** Switches [map] to [dimension] if it is showing another ([JourneyMapDimension]). */
    private fun showIfOther(map: IFullscreen, dimension: String) {
        if (map.uiState?.dimension?.identifier()?.toString() == dimension) return
        JourneyMapDimension.show(map, JourneyMapAdapter.levelKey(dimension))
    }

    fun register() {
        FullscreenEventRegistry.FULLSCREEN_POPUP_MENU_EVENT.subscribe(JourneyMapAdapter.MOD_ID) { guard { menu(it) } }
        FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(JourneyMapAdapter.MOD_ID) { guard { render(it) } }
        // The map bar, as on Xaero's world map.
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is Fullscreen) guard { MapBar.attachTo(screen) }
        }
        FullscreenEventRegistry.ADDON_BUTTON_DISPLAY_EVENT.subscribe(JourneyMapAdapter.MOD_ID) { event ->
            guard {
                // Xaero's map has a dimension button of its own; this is JourneyMap's.
                event.themeButtonDisplay.addThemeButton("Dimension", DIMENSION_ICON) { event.fullscreen?.let(JourneyMapDimension::showNext) }
                    .setTooltip("Dimension", "Shows the next of the Overworld, the Nether and the End; back to yours when you leave the map")
            }
        }
    }

    fun forget() {
        fullscreen = null
        pending = null
        JourneyMapDimension.forget()
    }

    /**
     * Client thread, every tick: once you leave the map, it goes back to your own dimension, as
     * Xaero's does. The player list closes the map for a moment and counts as still on it.
     */
    fun tick(client: Minecraft) {
        if (JourneyMapDimension.chosen == null) return
        val screen = client.gui.screen()
        val onTheMap = screen != null && (screen === fullscreen?.screen || screen is PlayerListScreen)
        if (!onTheMap && pending == null) {
            JourneyMapDimension.forget()
            // Coming back to your own dimension goes to you, as on Xaero's map.
            JourneyMapDimension.backToYou = true
        }
    }

    /** Right-click on the map. Entries that can't do anything just now are left out: JourneyMap's menu can't grey them. */
    private fun menu(event: PopupMenuEvent) {
        if (event.layer != PopupMenuEvent.Layer.FULLSCREEN) return
        val map = event.fullscreen ?: return
        fullscreen = map
        val dimension = map.uiState?.dimension?.identifier()?.toString()
        val menu = event.popupMenu
        menu.addMenuItem("Copy coordinates") { block -> Ui.copy(block.x, null, block.z, dimension) }
        if (dimension != null && PlayerPings.recipients().isNotEmpty()) {
            menu.addMenuItem("Ping here") { block -> Ui.say(PlayerPings.sendFromMap(dimension, block.x, block.z, null)) }
        }
        val api = JourneyMapAdapter.api
        if (api != null) {
            menu.addMenuItem("Share waypoints…") {
                val mine = JourneyMapWaypoints.mine(api)
                if (mine == null || mine.second.isEmpty()) Ui.say("You have no saved waypoints here to share")
                else Ui.open(WaypointShareScreen(map.screen, mine.first, mine.second))
            }
            // As Xaero's after a plain right-click: the whole map. JourneyMap's has no dragging to pick
            // an area; `/mapexposer terrain save` takes one.
            if (dimension != null && JourneyMapTerrainSave.busy) {
                menu.addMenuItem("Stop saving") { Ui.say(if (JourneyMapTerrainSave.cancel()) "Stopping the terrain file save…" else "No terrain file is being saved") }
            } else if (dimension != null) {
                menu.addMenuItem("Save terrain file") { startSave(map.screen, dimension, null) }
            }
            val other = dimension?.let(WaypointRules::counterpartName)
            if (other != null) {
                menu.addMenuItem("$other temp waypoint") { block -> Ui.say(JourneyMapWaypoints.counterpart(api, block.x, block.z, dimension)) }
            }
        }
        // Biome highlight, as Xaero's map bar picks them: the biome JourneyMap knows under the click.
        val biome = blockAtMouse(map)?.let { JourneyMapBiomes.biomeAt(map.uiState, it.x, it.z) }
        if (biome != null) {
            val name = BiomePicks.nameOf(biome)
            menu.addMenuItem(if (BiomePicks.isPicked(biome)) "Stop highlighting $name" else "Highlight $name") { BiomePicks.toggle(biome) }
        }
        if (BiomePicks.any) menu.addMenuItem("Clear biome highlights") { BiomePicks.clear() }
        // The list and downloads read BlueMap, so they are only worth offering on a server that has one.
        if (Session.current != null) {
            menu.addMenuItem("Players…") { Ui.open(PlayerListScreen(map.screen)) }
            if (!JourneyMapEdits.running) {
                menu.addMenuItem("Download BlueMap…") { block -> offerDownload(block.x, block.z) }
                // As on Xaero's map: what would go is said in chat, with the button to go ahead.
                menu.addMenuItem("Clear old season…") { offerClear(JourneyMapEdits.Clear.OLD_SEASON) }
                menu.addMenuItem("Clear past border…") { offerClear(JourneyMapEdits.Clear.BORDER) }
            }
        }
    }

    private fun render(event: FullscreenRenderEvent) {
        val map = event.fullscreen ?: return
        fullscreen = map
        if (JourneyMapDimension.backToYou) {
            JourneyMapDimension.backToYou = false
            Minecraft.getInstance().player?.let { map.centerOn(it.x, it.z) }
        }
        pending?.let { (x, z) ->
            pending = null
            pendingDimension?.let { showIfOther(map, it) }
            pendingDimension = null
            map.centerOn(x.toDouble(), z.toDouble())
        }
        val lines = listOfNotNull(
            blockAtMouse(map)?.let { OtherDimensionCoords.forWorldMap(it.x, it.z) },
            JourneyMapEdits.status() ?: MapPrep.status(),
            BiomePicks.all.keys.takeIf { it.isNotEmpty() }?.joinToString(", ", "Highlighted: ") { BiomePicks.nameOf(it) },
        ).filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        val graphics = event.graphics
        val font = Minecraft.getInstance().font
        val middle = map.screen.width / 2
        var top = LINES_TOP
        for (line in lines) {
            val half = font.width(line) / 2 + 4
            graphics.fill(middle - half, top, middle + half, top + 12, 0xA0000000.toInt())
            graphics.centeredText(font, line, middle, top + 2, 0xFFFFFFFF.toInt())
            top += 13
        }
    }

    /**
     * "Download BlueMap…": closes the map and asks in chat, where the buttons to go ahead are, as on
     * Xaero's map; nothing is written from here. Into the dimension you are in, which is the map
     * JourneyMap keeps for this world.
     */
    private fun offerDownload(x: Int, z: Int) {
        Ui.open(null)
        val message = Component.literal("Download BlueMap into JourneyMap's map, backing up each region first: ")
            .append(ChatButton.of("[This region, unexplored]", "/mapexposer download region $x $z", "Only where JourneyMap has nothing, in the 512-block region you clicked."))
            .append(" ")
            .append(ChatButton.of("[Everything, unexplored]", "/mapexposer download unexplored", "Only where JourneyMap has nothing, everywhere BlueMap has the map."))
            .append(" ")
            .append(ChatButton.of("[Everything]", "/mapexposer download all", "Everywhere BlueMap has the map, over your own map too."))
        Minecraft.getInstance().player?.sendSystemMessage(message)
    }

    /** "Clear old season…", "Clear past border…": closes the map and says in chat what would go, with the button to clear it. */
    private fun offerClear(kind: JourneyMapEdits.Clear) {
        Ui.open(null)
        Minecraft.getInstance().player?.sendSystemMessage(JourneyMapEdits.clearPreview(kind, null))
    }

    /**
     * The blocks JourneyMap's full-screen map shows now, as left, top, right, bottom: its live centre
     * and, at [state]'s zoom ([UIState.blockSize], window pixels a block), the window's size. The
     * view's own block area lags a moved map. Null until the map has been seen open.
     */
    fun shownArea(state: UIState): DoubleArray? {
        val map = fullscreen ?: return null
        val pixelsPerBlock = state.blockSize.takeIf { it > 0 } ?: return null
        val window = Minecraft.getInstance().window
        val halfWide = window.width / 2.0 / pixelsPerBlock
        val halfHigh = window.height / 2.0 / pixelsPerBlock
        val x = map.getCenterBlockX(false)
        val z = map.getCenterBlockZ(false)
        return doubleArrayOf(x - halfWide, z - halfHigh, x + halfWide, z + halfHigh)
    }

    /** The block under the mouse on JourneyMap's full-screen map, as JourneyMap itself works it out. */
    private fun blockAtMouse(map: IFullscreen): BlockPos? = try {
        (map as? Fullscreen)?.blockAtMouse
    } catch (e: LinkageError) {
        null
    }

    /**
     * Moves the full-screen map to ([x], [z]) of [dimension]: back to the open map [from] if that
     * is it, otherwise opened (JourneyMap's own way of opening it) and moved once it is up.
     */
    fun goTo(dimension: String, x: Int, z: Int, from: Screen?): Boolean {
        val map = fullscreen
        if (map != null && from != null && from === map.screen) {
            Ui.open(from)
            showIfOther(map, dimension)
            map.centerOn(x.toDouble(), z.toDouble())
            return true
        }
        pending = x to z
        pendingDimension = dimension
        return try {
            UIManager.INSTANCE.getOrOpenFullscreenMap()
            true
        } catch (e: LinkageError) {
            pending = null
            false
        }
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.error("Something of this mod's on JourneyMap's map failed", e)
        } catch (e: LinkageError) {
            Log.error("Something of this mod's on JourneyMap's map failed", e)
        }
    }

    /** The dimension button's picture: an eye of ender, from the game's own items. */
    private val DIMENSION_ICON = net.minecraft.resources.Identifier.withDefaultNamespace("textures/item/ender_eye.png")

    /** Under JourneyMap's own line of where you are, at the top of the map. */
    private const val LINES_TOP = 70
}
