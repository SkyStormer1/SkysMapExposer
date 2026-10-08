package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.gui.PlayerListScreen
import com.skystormer.skysmapexposer.gui.ShareScreen
import com.skystormer.skysmapexposer.gui.WaypointShareScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import xaero.map.WorldMapSession
import xaero.map.gui.IRightClickableElement
import xaero.map.gui.MapTileSelection
import xaero.map.gui.dropdown.rightclick.RightClickOption
import kotlin.math.floor

/**
 * What this mod adds to Xaero's world map right-click menu, and the odd jobs those options do:
 * copying coordinates, opening the player list, downloading BlueMap into the map, and moving the
 * map's camera.
 *
 * Called from `GuiMapMixin`; a failure here costs the options, not the menu.
 */
object MapMenus {

    /**
     * The map itself, right-clicked at block ([x], [z]) of [dimension] — Xaero's own reading of
     * which dimension that click landed in, falling back to the one the map is showing.
     */
    @JvmStatic
    fun addMapOptions(
        options: ArrayList<RightClickOption>,
        target: IRightClickableElement,
        x: Int,
        z: Int,
        dimension: ResourceKey<Level>?,
        selection: MapTileSelection?,
    ) {
        guard {
            val where = dimension?.identifier()?.toString() ?: MarkerElements.worldMapDimension()
            options.add(option("Copy coordinates", options.size, target) { copy(x, null, z, where) })
            if (selection != null) {
                options.add(shareOption(options.size, target, selection))
                options.add(saveOption(options.size, target, selection))
            }
            options.add(waypointsOption(options.size, target))
            if (where != null) addCounterpartOption(options, target, x, z, where)
            // The list reads BlueMap, so it is only worth offering on a server that has one.
            if (Session.current != null) {
                options.add(option("Players…", options.size, target) { parent -> open(PlayerListScreen(parent)) })
                if (selection != null) options.add(downloadOption(options.size, target, selection))
                options.add(clearOption(options.size, target, MapClear.Kind.OLD_SEASON, "Clear old season…",
                    "Takes your map from before the season began (/mapexposer season) off this dimension's map. Asks first; each region is copied aside."))
            }
            options.add(clearOption(options.size, target, MapClear.Kind.BORDER, "Clear past border…",
                "Takes your map beyond the world border off this dimension's map. Asks first; each region is copied aside."))
        }
    }

    /**
     * "Clear …": closes the map and asks in chat, where the button to go ahead is. Nothing is
     * taken off the map from here.
     */
    private fun clearOption(index: Int, target: IRightClickableElement, kind: MapClear.Kind, name: String, tip: String): RightClickOption {
        val busy = BlueMapDownload.running
        val tooltip = listOf(
            Component.literal(name.removeSuffix("…")),
            Component.literal(if (busy) "§7Something is already being written into your map." else "§7$tip"),
        )
        return MenuTooltips.Option(name, index, target, tooltip) {
            open(null)
            MapClear.preview(kind)?.let { Minecraft.getInstance().player?.sendSystemMessage(Component.literal(it)) }
        }.also { it.setActive(!busy) }
    }

    /**
     * A temporary waypoint at this block's counterpart in the other dimension, for the Overworld
     * and the Nether. Xaero's menu is 150 wide at every GUI scale and cuts off anything longer, so
     * the name is short and the tooltip says the rest.
     */
    private fun addCounterpartOption(
        options: ArrayList<RightClickOption>,
        target: IRightClickableElement,
        x: Int,
        z: Int,
        dimension: String,
    ) {
        val other = Waypoints.counterpartName(dimension) ?: return
        val tooltip = listOf(
            Component.literal("Temporary waypoint in the $other"),
            Component.literal("§7" + Waypoints.counterpartTip(x, z, dimension)),
        )
        options.add(
            MenuTooltips.Option("$other temp waypoint", options.size, target, tooltip) { Waypoints.setCounterpart(x, z, dimension) }
                .also { it.setActive(Waypoints.available()) }
        )
    }

    /**
     * "Download" for the chunks Xaero has selected: every right-click selects the chunk under the
     * mouse, and dragging with the right button held selects a rectangle of them, which Xaero
     * outlines on the map.
     */
    private fun downloadOption(index: Int, target: IRightClickableElement, selection: MapTileSelection): RightClickOption {
        val wide = selection.right - selection.left + 1
        val high = selection.bottom - selection.top + 1
        val size = if (wide == 1 && high == 1) "This chunk" else "These $wide × $high chunks"
        val busy = BlueMapDownload.running
        val tooltip = listOf(
            Component.literal("Download from BlueMap"),
            Component.literal(
                if (busy) "§7A download is already running."
                else "§7$size, written into your own map wherever BlueMap has them. Each region is backed up just before it is replaced."
            ),
        )
        val area = BlueMapDownload.Area(selection.left * 16, selection.top * 16, selection.right * 16 + 15, selection.bottom * 16 + 15)
        return MenuTooltips.Option("Download", index, target, tooltip) { say(BlueMapDownload.start(area)) }
            .also { it.setActive(!busy) }
    }

    private fun sizeOf(selection: MapTileSelection): String {
        val wide = selection.right - selection.left + 1
        val high = selection.bottom - selection.top + 1
        return if (wide == 1 && high == 1) "This chunk" else "These $wide × $high chunks"
    }

    /** "Share biomes…" for the chunks Xaero has selected, the same selection "Download" takes. */
    private fun shareOption(index: Int, target: IRightClickableElement, selection: MapTileSelection): RightClickOption {
        val busy = ChatShare.sending
        val tooltip = listOf(
            Component.literal("Share biomes with a player"),
            Component.literal(
                if (busy) "§7Something is already being shared; /mapexposer share cancel stops it."
                else "§7Which biome is where in ${sizeOf(selection).lowercase()}, sent by private message to a player who has this mod. " +
                    "A few messages even for a big area. They see them highlighted and named on their map."
            ),
        )
        return MenuTooltips.Option("Share biomes…", index, target, tooltip) { parent ->
            open(ShareScreen(parent, "Share biomes with…") { name ->
                ChatShare.shareBiomes(name, selection.left, selection.top, selection.right, selection.bottom)
            })
        }.also { it.setActive(!busy) }
    }

    /** "Save terrain file" for the chunks Xaero has selected: the whole map of them, to send however you like. */
    private fun saveOption(index: Int, target: IRightClickableElement, selection: MapTileSelection): RightClickOption {
        val busy = TerrainFiles.busy
        val tooltip = listOf(
            Component.literal("Save terrain to a file"),
            Component.literal(
                if (busy) "§7A map file is already being saved."
                else "§7${sizeOf(selection)} of your map, every block, saved in the \"shared maps\" folder of your game folder. " +
                    "Send the file to anyone; they drop it onto their game window to add it."
            ),
        )
        return MenuTooltips.Option("Save terrain file", index, target, tooltip) {
            say(TerrainFiles.save(selection.left, selection.top, selection.right, selection.bottom))
        }.also { it.setActive(!busy) }
    }

    /** "Share waypoints…": pick some of the waypoints of the dimension you are in, then who gets them. */
    private fun waypointsOption(index: Int, target: IRightClickableElement): RightClickOption {
        val busy = ChatShare.sending
        val tooltip = listOf(
            Component.literal("Share waypoints with a player"),
            Component.literal(
                when {
                    !Waypoints.available() -> "§7Needs Xaero's Minimap."
                    busy -> "§7Something is already being shared; /mapexposer share cancel stops it."
                    else -> "§7Pick waypoints of the dimension you are in, then a player who has this mod. Sent by private message."
                }
            ),
        )
        return MenuTooltips.Option("Share waypoints…", index, target, tooltip) { parent ->
            val mine = WaypointShare.mine()
            if (mine == null) say("Xaero's Minimap has no waypoint world open")
            else open(WaypointShareScreen(parent, mine.first, mine.second))
        }.also { it.setActive(Waypoints.available() && !busy) }
    }

    /**
     * Puts a position on the clipboard, as `-350 72 200 (Overworld)` — the numbers first, the way
     * `/tp` and most chat messages want them, and the dimension after, because coordinates from a
     * map you can switch between dimensions mean nothing without it.
     *
     * Answers on the action bar, which only this client sees.
     */
    fun copy(x: Double, y: Double?, z: Double, dimension: String?) =
        copy(floor(x).toInt(), y?.let { floor(it).toInt() }, floor(z).toInt(), dimension)

    fun copy(x: Int, y: Int?, z: Int, dimension: String?) {
        val position = if (y == null) "$x $z" else "$x $y $z"
        val name = dimensionName(dimension)
        val text = if (name == null) position else "$position ($name)"
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(text)
            say("Copied $text")
        } catch (e: Throwable) {
            Log.error("Could not copy $text to the clipboard", e)
            say("Could not copy $text to the clipboard")
        }
    }

    /** A dimension id as a person would write it: `minecraft:the_nether` is the Nether. */
    fun dimensionName(dimension: String?): String? = when (dimension) {
        null -> null
        Config.OVERWORLD -> "Overworld"
        Config.NETHER -> "Nether"
        Config.END -> "End"
        else -> dimension.removePrefix("minecraft:")
    }

    /**
     * Moves Xaero's world map to ([x], [z]): the open map if [screen] is it, otherwise a new one,
     * opened at that spot the way Xaero's own map key would open it. Returns whether it worked.
     */
    fun goTo(screen: Screen?, x: Int, z: Int): Boolean {
        if (screen is MapCamera) {
            screen.skysmapexposerCentreOn(x, z)
            screen.skysmapexposerZoomIn(MapView.GO_TO_ZOOM)
            return true
        }
        return try {
            val player = Minecraft.getInstance().player ?: return false
            val session = WorldMapSession.getCurrentSession()
                ?: return false.also { Log.warn("Go to: Xaero's world map has no session yet") }
            val map = xaero.map.gui.GuiMap(null, null, session.mapProcessor, player)
            val camera = map as Any as? MapCamera
            if (camera == null) {
                Log.warn("Go to: the world map hook is missing, so the map cannot be moved")
                return false
            }
            camera.skysmapexposerCentreOn(x, z)
            camera.skysmapexposerZoomIn(MapView.GO_TO_ZOOM)
            open(map)
            true
        } catch (e: Throwable) {
            Log.error("Could not open the world map at $x, $z", e)
            false
        }
    }

    /** Opens Xaero's world map, the way its own key does. Returns whether it opened. */
    fun openMap(): Boolean = try {
        val player = Minecraft.getInstance().player
        val session = WorldMapSession.getCurrentSession()
        if (player == null || session == null) false
        else {
            open(xaero.map.gui.GuiMap(null, null, session.mapProcessor, player))
            true
        }
    } catch (e: Throwable) {
        Log.error("Could not open the world map", e)
        false
    }

    fun open(screen: Screen?) {
        Minecraft.getInstance().gui.setScreen(screen)
    }

    /** True while [say] is showing a line, which passes through the same chat events as the server's. */
    var saying = false
        private set

    /** A message on the action bar, which only this client sees. */
    fun say(message: String) {
        saying = true
        try {
            Minecraft.getInstance().player?.sendOverlayMessage(Component.literal(message))
        } finally {
            saying = false
        }
    }

    fun option(name: String, index: Int, target: IRightClickableElement, action: (Screen) -> Unit) =
        object : RightClickOption(name, index, target) {
            override fun onAction(screen: Screen) = action(screen)
        }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.error("Could not add options to Xaero's right-click menu", e)
        }
    }
}
