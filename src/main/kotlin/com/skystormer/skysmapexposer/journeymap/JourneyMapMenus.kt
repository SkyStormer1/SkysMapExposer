package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.levelKey
import com.skystormer.skysmapexposer.BiomePicks
import com.skystormer.skysmapexposer.CaveNotice
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
import com.skystormer.skysmapexposer.gui.ShareScreen
import com.skystormer.skysmapexposer.gui.WaypointShareScreen
import journeymap.api.v2.client.event.FullscreenRenderEvent
import journeymap.api.v2.client.event.PopupMenuEvent
import journeymap.api.v2.client.fullscreen.IFullscreen
import journeymap.api.v2.client.util.UIState
import journeymap.api.v2.common.event.FullscreenEventRegistry
import journeymap.client.ui.UIManager
import journeymap.client.ui.fullscreen.Fullscreen
import journeymap.client.ui.fullscreen.menu.FullScreenPopupMenu
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

    /**
     * The screen Go to was pressed on when the map was not open (the player list, with the settings
     * behind it), put back once the map Go to opened is closed; [backToShown] once that map is up.
     */
    private var backTo: Screen? = null
    private var backToShown = false

    /** Switches [map] to [dimension] if it is showing another ([JourneyMapDimension]). */
    private fun showIfOther(map: IFullscreen, dimension: String) {
        if (map.uiState?.dimension?.identifier()?.toString() == dimension) return
        JourneyMapDimension.show(map, levelKey(dimension))
    }

    fun register() {
        FullscreenEventRegistry.FULLSCREEN_POPUP_MENU_EVENT.subscribe(JourneyMapAdapter.MOD_ID) { guard { menu(it) } }
        FullscreenEventRegistry.WAYPOINT_POPUP_MENU_EVENT.subscribe(JourneyMapAdapter.MOD_ID) { guard { waypointMenu(it) } }
        FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(JourneyMapAdapter.MOD_ID) { guard { render(it) } }
        JourneyMapSelection.register()
        // The map bar, as on Xaero's world map; also while JourneyMap's right-click menu is open,
        // which is a screen of its own over the map, so the bar does not vanish under it: there drawn
        // under the menu (`PopupMenuMixin`), never over it.
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            when (screen) {
                is Fullscreen -> guard { MapBar.attachTo(screen) }
                is FullScreenPopupMenu -> guard { MapBar.showUnder(screen) }
            }
        }
        FullscreenEventRegistry.ADDON_BUTTON_DISPLAY_EVENT.subscribe(JourneyMapAdapter.MOD_ID) { event ->
            guard {
                // Xaero's map has a dimension button of its own; this is JourneyMap's, at the end of the
                // top row ([JourneyMapToolbar]), or here in the side column if that hook could not apply.
                if (!JourneyMapToolbar.inTopRow) {
                    event.themeButtonDisplay.addThemeButton("Dimension", JourneyMapToolbar.iconFor(event.fullscreen?.uiState?.dimension)) { event.fullscreen?.let(JourneyMapDimension::showNext) }
                        .setTooltip("Shows the next of the Overworld, the Nether and the End; back to yours when you leave the map")
                }
            }
        }
    }

    fun forget() {
        fullscreen = null
        pending = null
        backTo = null
        JourneyMapSelection.forget()
        JourneyMapDimension.forget()
    }

    /**
     * Client thread, every tick: once you leave the map, it goes back to your own dimension, as
     * Xaero's does. The player list, JourneyMap's right-click menu and its other screens opened from
     * the map (the waypoint editor) close the map for a moment and count as still on it.
     */
    fun tick(client: Minecraft) {
        // The map Go to opened over another screen has closed: back to that screen.
        if (backTo != null && backToShown && client.gui.screen() == null) {
            val screen = backTo
            backTo = null
            Ui.open(screen)
        }
        if (JourneyMapDimension.chosen == null) return
        val screen = client.gui.screen()
        // JourneyMap's own screens opened from the map (its waypoint editor, pickers) count as on it.
        val onTheMap = screen != null && (screen is Fullscreen || screen is PlayerListScreen || screen is FullScreenPopupMenu ||
            screen.javaClass.name.startsWith("journeymap.client.ui."))
        if (!onTheMap && pending == null) {
            JourneyMapDimension.forget()
            // Coming back to your own dimension goes to you, as on Xaero's map.
            JourneyMapDimension.backToYou = true
        }
    }

    /** Right-click on a waypoint: Share, for that one waypoint, to a player picked next, as from Share waypoints. */
    private fun waypointMenu(event: PopupMenuEvent.WaypointPopupMenuEvent) {
        val waypoint = event.waypoint ?: return
        val map = event.fullscreen ?: return
        val dimension = map.uiState?.dimension?.identifier()?.toString() ?: return
        event.popupMenu.addMenuItem("Share…") {
            val shared = JourneyMapWaypoints.shareable(waypoint, dimension)
            Ui.open(ShareScreen.forWaypoint(map.screen, dimension, shared))
        }
    }

    /** Right-click on the map. Entries that can't do anything just now are left out: JourneyMap's menu can't grey them. */
    private fun menu(event: PopupMenuEvent) {
        if (event.layer != PopupMenuEvent.Layer.FULLSCREEN) return
        val map = event.fullscreen ?: return
        fullscreen = map
        val dimension = map.uiState?.dimension?.identifier()?.toString()
        val menu = event.popupMenu
        val item = JourneyMapMenuLayout::item
        JourneyMapMenuLayout.begin(map.screen)
        // The line at the top: the block clicked, copied when clicked ([MenuLayout]'s "coords").
        blockAtMouse(map)?.let { at ->
            item(menu, "coords", "§6X ${at.x}  Y ${at.y}  Z ${at.z}") { block -> Ui.copy(block.x, block.y, block.z, dimension) }
        }
        item(menu, "copy", "Copy coordinates") { block -> Ui.copy(block.x, block.y, block.z, dimension) }
        if (dimension != null && PlayerPings.recipients().isNotEmpty()) {
            item(menu, "ping", "Ping here") { block -> Ui.say(PlayerPings.sendFromMap(dimension, block.x, block.z, null)) }
        }
        val api = JourneyMapAdapter.api
        if (api != null) {
            item(menu, "sharewp", "Share waypoints…") {
                val mine = JourneyMapWaypoints.mine(api)
                if (mine == null || mine.second.isEmpty()) Ui.say("You have no saved waypoints here to share")
                else Ui.open(WaypointShareScreen(map.screen, mine.first, mine.second))
            }
            // As Xaero's: the area dragged over with the right button ([JourneyMapSelection]), else the
            // whole map. JourneyMap's menu has no tooltips, so the entry says which.
            val picked = JourneyMapSelection.area
            if (dimension != null && JourneyMapTerrainSave.busy) {
                item(menu, "save", "Stop saving") { Ui.say(if (JourneyMapTerrainSave.cancel()) "Stopping the terrain file save…" else "No terrain file is being saved") }
            } else if (dimension != null) {
                item(menu, "save", if (picked != null) "Save terrain file (area)" else "Save terrain file (whole map)") { startSave(map.screen, dimension, picked) }
            }
            val other = dimension?.let(WaypointRules::counterpartName)
            if (other != null) {
                item(menu, "counter", "$other temp waypoint") { block -> Ui.say(JourneyMapWaypoints.counterpart(api, block.x, block.z, dimension)) }
            }
        }
        // Biome highlight, as Xaero's map bar picks them: the biome JourneyMap knows under the click.
        val biome = blockAtMouse(map)?.let { JourneyMapBiomes.biomeAt(map.uiState, it.x, it.z) }
        if (biome != null) {
            val name = BiomePicks.nameOf(biome)
            item(menu, "highlight", if (BiomePicks.isPicked(biome)) "Stop highlighting $name" else "Highlight $name") { BiomePicks.toggle(biome) }
        }
        if (BiomePicks.any) item(menu, "clearhighlights", "Clear biome highlights") { BiomePicks.clear() }
        // The list and downloads read BlueMap, so they are only worth offering on a server that has one.
        if (Session.current != null) {
            item(menu, "players", "Players…") { Ui.open(PlayerListScreen(map.screen)) }
            if (!JourneyMapEdits.running) {
                item(menu, "download", "Download BlueMap…") { block -> offerDownload(block.x, block.z, JourneyMapSelection.area) }
                // As on Xaero's map: what would go is said in chat, with the button to go ahead.
                item(menu, "season", "Clear old season…") { offerClear(JourneyMapEdits.Clear.OLD_SEASON) }
                item(menu, "border", "Clear past border…") { offerClear(JourneyMapEdits.Clear.BORDER) }
            }
        }
    }

    private fun render(event: FullscreenRenderEvent) {
        val map = event.fullscreen ?: return
        fullscreen = map
        JourneyMapToolbar.refresh()
        if (backTo != null) backToShown = true
        if (JourneyMapDimension.backToYou) {
            JourneyMapDimension.backToYou = false
            JourneyMapDimension.restore(map)
            Minecraft.getInstance().player?.let { map.centerOn(it.x, it.z) }
        }
        pending?.let { (x, z) ->
            pending = null
            pendingDimension?.let { showIfOther(map, it) }
            pendingDimension = null
            map.centerOn(x.toDouble(), z.toDouble())
        }
        val below = listOfNotNull(blockAtMouse(map)?.let { OtherDimensionCoords.forWorldMap(it.x, it.z) }).filter { it.isNotBlank() }
        val above = listOfNotNull(
            JourneyMapEdits.status() ?: MapPrep.status() ?: CaveNotice.line(),
            BiomePicks.all.keys.takeIf { it.isNotEmpty() }?.joinToString(", ", "Highlighted: ") { BiomePicks.nameOf(it) },
        ).filter { it.isNotBlank() }
        JourneyMapSelection.draw(map, event.graphics)
        if (below.isEmpty() && above.isEmpty()) return
        val drawn = map is Fullscreen && themed && try {
            drawThemed(map, event.graphics, above, below)
            true
        } catch (e: LinkageError) {
            themed = false
            Log.warn("This JourneyMap draws its labels differently; Map Exposer's lines go at the top of its map instead: {}", e.toString())
            false
        }
        if (drawn) return
        val graphics = event.graphics
        val font = Minecraft.getInstance().font
        val middle = map.screen.width / 2
        var top = LINES_TOP
        for (line in below + above) {
            val half = font.width(line) / 2 + 4
            graphics.fill(middle - half, top, middle + half, top + 12, 0xA0000000.toInt())
            graphics.centeredText(font, line, middle, top + 2, 0xFFFFFFFF.toInt())
            top += 13
        }
    }

    /** Whether JourneyMap's own label drawing is there to use ([drawThemed]). */
    private var themed = true

    /**
     * Map Exposer's lines drawn as JourneyMap draws its own two, in its theme's label style and its
     * full-screen font size: [top] under its line beneath the top bar (you, your position, the
     * zoom), [bottom] over its line above the bottom bar (the block under the mouse), each stacked
     * away from it. Laid out the way JourneyMap lays out its own: in its scaled drawing space, a
     * toolbar margin from the bars.
     */
    private fun drawThemed(map: Fullscreen, graphics: net.minecraft.client.gui.GuiGraphicsExtractor, top: List<String>, bottom: List<String>) {
        val theme = journeymap.client.io.ThemeLoader.getCurrentTheme()
        val spec = theme.fullscreen.statusLabel
        val factor = journeymap.client.ui.component.screens.JmUI.calculateScaleFactor()
        val fontScale = journeymap.client.JourneymapClient.getInstance().fullMapProperties.fontScale.get().toDouble()
        val margin = theme.container.toolbar.horizontal.margin * factor
        val font = Minecraft.getInstance().font
        // One of JourneyMap's labels, top to bottom (its text and padding), and a pixel between.
        val step = journeymap.client.render.draw.DrawUtil.getLabelHeight(font, spec.shadow) * fontScale + 1
        val middle = map.width / 2.0 * factor
        val buttons = map.isButtonsVisable
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.scale((1 / factor).toFloat())
        try {
            fun label(text: String, y: Double, valign: journeymap.client.render.draw.DrawUtil.VAlign) =
                journeymap.client.render.draw.DrawUtil.drawLabel(
                    graphics, Component.literal(text), spec, middle, y, journeymap.client.render.draw.DrawUtil.HAlign.Center, valign, fontScale, 0.0,
                )
            // JourneyMap's own lines show only with its buttons; without them, ours go to the edges.
            var y = map.optionsToolbarBounds?.takeIf { buttons }?.let { it.maxY + margin + step } ?: margin
            for (line in top) {
                label(line, y, journeymap.client.render.draw.DrawUtil.VAlign.Below)
                y += step
            }
            y = map.menuToolbarBounds?.takeIf { buttons }?.let { it.minY - margin - step } ?: (map.height * factor - margin)
            for (line in bottom) {
                label(line, y, journeymap.client.render.draw.DrawUtil.VAlign.Above)
                y -= step
            }
        } finally {
            pose.popMatrix()
        }
    }

    /**
     * "Download BlueMap…": closes the map and asks in chat, where the buttons to go ahead are, as on
     * Xaero's map; nothing is written from here. Into the dimension you are in, which is the map
     * JourneyMap keeps for this world.
     */
    private fun offerDownload(x: Int, z: Int, picked: ShareFormat.Area?) {
        Ui.open(null)
        val here = if (picked != null) {
            val blocks = "${picked.left * 16} ${picked.top * 16} ${picked.right * 16 + 15} ${picked.bottom * 16 + 15}"
            val size = "the ${picked.width} × ${picked.height} chunks you picked"
            ChatButton.of("[Picked area, unexplored]", "/mapexposer download area $blocks", "Only where JourneyMap has nothing, in $size.")
                .copy().append(" ")
                .append(ChatButton.of("[Picked area]", "/mapexposer download area $blocks all", "All of $size, over your own map too."))
        } else {
            ChatButton.of("[This region, unexplored]", "/mapexposer download region $x $z", "Only where JourneyMap has nothing, in the 512-block region you clicked.")
        }
        val message = Component.literal("Download BlueMap into JourneyMap's map, backing up each region first: ")
            .append(here)
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
     * and, at its live zoom ([pixelsPerBlock]), the window's size. Null until the map has been seen open.
     */
    fun shownArea(state: UIState): DoubleArray? {
        val map = fullscreen ?: return null
        val pixels = pixelsPerBlock(state).takeIf { it > 0 } ?: return null
        val window = Minecraft.getInstance().window
        val halfWide = window.width / 2.0 / pixels
        val halfHigh = window.height / 2.0 / pixels
        val x = map.getCenterBlockX(false)
        val z = map.getCenterBlockZ(false)
        return doubleArrayOf(x - halfWide, z - halfHigh, x + halfWide, z + halfHigh)
    }

    /**
     * Window pixels a block on the full-screen map, at the zoom it shows now. JourneyMap's [UIState]
     * keeps the zoom the map opened at (its block size is zoom / 512), so the live one is asked of
     * the map itself, falling back to [state]'s if that cannot be reached.
     */
    fun pixelsPerBlock(state: UIState): Double {
        val zoom = try {
            (fullscreen as? Fullscreen)?.zoom
        } catch (e: LinkageError) {
            null
        }
        return zoom?.takeIf { it > 0 }?.let { it / 512.0 } ?: state.blockSize
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
        // JourneyMap opens its map only with no other screen up, so whatever Go to was pressed on is
        // closed first, and put back when the map closes.
        val current = Minecraft.getInstance().gui.screen()
        if (current != null && current !is Fullscreen) {
            backTo = current
            backToShown = false
            Ui.open(null)
        }
        return try {
            UIManager.INSTANCE.getOrOpenFullscreenMap()
            true
        } catch (e: LinkageError) {
            pending = null
            backTo = null
            false
        }
    }

    private inline fun guard(block: () -> Unit) = Log.guarded("Something of this mod's on JourneyMap's map failed", block)

    /** Under JourneyMap's own line of where you are, at the top of the map. */
    private const val LINES_TOP = 70
}
