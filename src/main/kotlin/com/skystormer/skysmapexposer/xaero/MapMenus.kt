package com.skystormer.skysmapexposer.xaero

import com.skystormer.skysmapexposer.WaypointRules
import com.skystormer.skysmapexposer.Ui
import com.skystormer.skysmapexposer.ChatShare
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapCamera
import com.skystormer.skysmapexposer.PlayerPings
import com.skystormer.skysmapexposer.Session
import com.skystormer.skysmapexposer.ShareFormat
import com.skystormer.skysmapexposer.gui.PlayerListScreen
import com.skystormer.skysmapexposer.gui.WaypointShareScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import xaero.map.WorldMapSession
import xaero.map.gui.IRightClickableElement
import xaero.map.gui.MapTileSelection
import xaero.map.gui.dropdown.rightclick.RightClickOption

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
     * which dimension that click landed in, falling back to the one the map is showing. [y] is the
     * height Xaero shows for that block, when this Xaero gives it.
     */
    @JvmStatic
    fun addMapOptions(
        options: ArrayList<RightClickOption>,
        target: IRightClickableElement,
        x: Int,
        z: Int,
        y: Int?,
        dimension: ResourceKey<Level>?,
        selection: MapTileSelection?,
    ) {
        guard {
            val where = dimension?.identifier()?.toString() ?: MarkerElements.worldMapDimension()
            options.add(option("Copy coordinates", options.size, target) { Ui.copy(x, null, z, where) })
            addSaveOptions(options, target, selection)
            options.add(waypointsOption(options.size, target))
            if (where != null) options.add(pingOption(options.size, target, x, y, z, where))
            if (where != null) addCounterpartOption(options, target, x, z, where)
            // The list reads BlueMap, so it is only worth offering on a server that has one.
            if (Session.current != null) {
                options.add(option("Players…", options.size, target) { parent -> Ui.open(PlayerListScreen(parent)) })
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
            Ui.open(null)
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
        val other = WaypointRules.counterpartName(dimension) ?: return
        val tooltip = listOf(
            Component.literal("Temporary waypoint in the $other"),
            Component.literal("§7" + WaypointRules.counterpartTip(x, z, dimension)),
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
        val busy = BlueMapDownload.running
        val tooltip = listOf(
            Component.literal("Download from BlueMap"),
            Component.literal(
                if (busy) "§7A download is already running."
                else "§7${sizeOf(selection)}, written into your own map wherever BlueMap has them. Each region is backed up just before it is replaced."
            ),
        )
        val area = BlueMapDownload.Area(selection.left * 16, selection.top * 16, selection.right * 16 + 15, selection.bottom * 16 + 15)
        return MenuTooltips.Option("Download", index, target, tooltip) { Ui.say(BlueMapDownload.start(area)) }
            .also { it.setActive(!busy) }
    }

    private fun sizeOf(selection: MapTileSelection): String {
        val wide = selection.right - selection.left + 1
        val high = selection.bottom - selection.top + 1
        return if (wide == 1 && high == 1) "This chunk" else "These $wide × $high chunks"
    }

    /**
     * "Save terrain file" for the chunks Xaero has selected and "Save whole map" for every region of
     * this dimension, each the whole map of them, to send however you like; while one is saving,
     * "Stop saving" in their place.
     */
    private fun addSaveOptions(options: ArrayList<RightClickOption>, target: IRightClickableElement, selection: MapTileSelection?) {
        if (TerrainFiles.busy) {
            val tooltip = listOf(Component.literal("Stop saving the terrain file"), Component.literal("§7Nothing of it is kept."))
            options.add(MenuTooltips.Option("Stop saving", options.size, target, tooltip) {
                TerrainFiles.tell(if (TerrainFiles.cancel()) "Stopped saving the terrain file; nothing of it was kept" else "No terrain file is being saved")
            })
            return
        }
        val where = "saved in the \"shared maps\" folder of your game folder. Send the file to anyone; they drop it onto their game window to add it."
        if (selection != null) {
            val tooltip = listOf(Component.literal("Save terrain to a file"), Component.literal("§7${sizeOf(selection)} of your map, every block, $where"))
            options.add(MenuTooltips.Option("Save terrain file", options.size, target, tooltip) { parent ->
                startSave(parent, ShareFormat.Area(selection.left, selection.top, selection.right, selection.bottom))
            })
        }
        val tooltip = listOf(Component.literal("Save the whole map to a file"), Component.literal("§7All of this dimension's map, every block, $where Asks first if it is big."))
        options.add(MenuTooltips.Option("Save whole map", options.size, target, tooltip) { parent -> startSave(parent, null) })
    }

    /** Starts a save of [area] (null: the whole map); a big one asks first, then comes back to the map ([parent]). */
    private fun startSave(parent: Screen, area: ShareFormat.Area?) {
        val answer = TerrainFiles.save(area, confirmed = false)
        if (!answer.confirm) return TerrainFiles.tell(answer.text)
        Ui.open(ConfirmScreen(
            { yes ->
                Ui.open(parent)
                TerrainFiles.tell(if (yes) TerrainFiles.save(area, confirmed = true).text else "Not saved")
            },
            Component.literal("Save this much?"),
            Component.literal(answer.text),
            Component.literal("Save"),
            CommonComponents.GUI_CANCEL,
        ))
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
            if (mine == null) Ui.say("Xaero's Minimap has no waypoint world open")
            else Ui.open(WaypointShareScreen(parent, mine.first, mine.second))
        }.also { it.setActive(Waypoints.available() && !busy) }
    }

    /** "Ping here": flashes a beam on this block for those online you ping. */
    private fun pingOption(index: Int, target: IRightClickableElement, x: Int, y: Int?, z: Int, dimension: String): RightClickOption {
        val to = PlayerPings.recipients()
        val tooltip = listOf(
            Component.literal("Ping this spot"),
            Component.literal(
                if (to.isEmpty()) "§7Nobody you ping is online. Trust someone, then pick them with Ping in the player list or /mapexposer pingto add."
                else "§7A flashing beam here for ${to.joinToString()}. Sent by private message."
            ),
        )
        return MenuTooltips.Option("Ping here", index, target, tooltip) { Ui.say(PlayerPings.sendFromMap(dimension, x, z, y)) }
            .also { it.setActive(to.isNotEmpty()) }
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
            Ui.open(map)
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
            Ui.open(xaero.map.gui.GuiMap(null, null, session.mapProcessor, player))
            true
        }
    } catch (e: Throwable) {
        Log.error("Could not open the world map", e)
        false
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
