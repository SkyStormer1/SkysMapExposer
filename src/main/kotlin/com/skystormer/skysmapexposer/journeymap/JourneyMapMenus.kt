package com.skystormer.skysmapexposer.journeymap

import com.mojang.blaze3d.platform.InputConstants
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapPrep
import com.skystormer.skysmapexposer.OtherDimensionCoords
import com.skystormer.skysmapexposer.PlayerPings
import com.skystormer.skysmapexposer.Session
import com.skystormer.skysmapexposer.Ui
import com.skystormer.skysmapexposer.WaypointRules
import com.skystormer.skysmapexposer.gui.PlayerListScreen
import com.skystormer.skysmapexposer.gui.WaypointShareScreen
import journeymap.api.v2.client.event.FullscreenRenderEvent
import journeymap.api.v2.client.event.PopupMenuEvent
import journeymap.api.v2.client.fullscreen.IFullscreen
import journeymap.api.v2.client.util.UIState
import journeymap.api.v2.common.event.FullscreenEventRegistry
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/**
 * This mod's parts of JourneyMap's full-screen map, the same as on Xaero's: the right-click entries
 * (Copy coordinates, Ping here, Players…), Go to from the player list, and the line under the top
 * of the map with the other dimension's coordinates of the block under the mouse and how preparing
 * the map is going.
 *
 * It keeps only the full-screen map last seen open and the block under the mouse, both let go of
 * on leaving a world ([forget]).
 */
internal object JourneyMapMenus {

    /** The full-screen map, while it is open: JourneyMap hands it over with each event. */
    private var fullscreen: IFullscreen? = null

    /** A Go to waiting for the full-screen map to open, and the dimension it is for. */
    private var pending: Pair<Int, Int>? = null
    private var pendingDimension: String? = null

    /** Switches [map] to [dimension] if it is showing another ([JourneyMapDimension]). */
    private fun showIfOther(map: IFullscreen, dimension: String) {
        if (map.uiState?.dimension?.identifier()?.toString() == dimension) return
        JourneyMapDimension.show(map, net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.Identifier.parse(dimension)))
    }

    fun register() {
        FullscreenEventRegistry.FULLSCREEN_POPUP_MENU_EVENT.subscribe(JourneyMapAdapter.MOD_ID) { guard { menu(it) } }
        FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(JourneyMapAdapter.MOD_ID) { guard { render(it) } }
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
            val other = dimension?.let(WaypointRules::counterpartName)
            if (other != null) {
                menu.addMenuItem("$other temp waypoint") { block -> Ui.say(JourneyMapWaypoints.counterpart(api, block.x, block.z, dimension)) }
            }
        }
        // The list reads BlueMap, so it is only worth offering on a server that has one.
        if (Session.current != null) menu.addMenuItem("Players…") { Ui.open(PlayerListScreen(map.screen)) }
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
            blockUnder(map, event.mouseX, event.mouseY)?.let { (x, z) -> OtherDimensionCoords.forWorldMap(x, z) },
            MapPrep.status(),
        )
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

    /**
     * The block under the mouse at ([mouseX], [mouseY]), in GUI pixels: the map's own centre, plus
     * the mouse's distance from the middle of the window over JourneyMap's [UIState.blockSize]
     * (window pixels a block, at its zoom). The view's block area lags a moved map, so is not used.
     */
    private fun blockUnder(map: IFullscreen, mouseX: Int, mouseY: Int): Pair<Int, Int>? {
        val pixelsPerBlock = map.uiState?.blockSize?.takeIf { it > 0 } ?: return null
        val window = Minecraft.getInstance().window
        val scale = window.guiScale.toDouble()
        val x = map.getCenterBlockX(false) + (mouseX * scale - window.width / 2.0) / pixelsPerBlock
        val z = map.getCenterBlockZ(false) + (mouseY * scale - window.height / 2.0) / pixelsPerBlock
        return kotlin.math.floor(x).toInt() to kotlin.math.floor(z).toInt()
    }

    /**
     * Moves the full-screen map to ([x], [z]) of [dimension]: back to the open map [from] if that
     * is it, otherwise opened with JourneyMap's own key and moved once it is up.
     */
    fun goTo(dimension: String, x: Int, z: Int, from: Screen?): Boolean {
        val map = fullscreen
        if (map != null && from != null && from === map.screen) {
            Ui.open(from)
            showIfOther(map, dimension)
            map.centerOn(x.toDouble(), z.toDouble())
            return true
        }
        val mappings = Minecraft.getInstance().options.keyMappings
        val key = FULLSCREEN_KEYS.firstNotNullOfOrNull { name -> mappings.firstOrNull { it.name == name && !it.isUnbound } } ?: return false
        pending = x to z
        pendingDimension = dimension
        Ui.open(null)
        KeyMapping.click(InputConstants.getKey(key.saveString()))
        return true
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.error("Something of this mod's on JourneyMap's map failed", e)
        }
    }

    /** JourneyMap's keys for its full-screen map, the one it binds (J) first. */
    private val FULLSCREEN_KEYS = listOf("key.journeymap.map_toggle_alt", "key.journeymap.map_toggle")

    /** The dimension button's picture: an eye of ender, from the game's own items. */
    private val DIMENSION_ICON = net.minecraft.resources.Identifier.withDefaultNamespace("textures/item/ender_eye.png")

    /** Under JourneyMap's own line of where you are, at the top of the map. */
    private const val LINES_TOP = 70
}
