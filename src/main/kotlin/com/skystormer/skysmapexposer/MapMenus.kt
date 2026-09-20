package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.gui.PlayerListScreen
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import xaero.map.WorldMapSession
import xaero.map.gui.IRightClickableElement
import xaero.map.gui.dropdown.rightclick.RightClickOption
import kotlin.math.floor

/**
 * What this mod adds to Xaero's world map right-click menu, and the odd jobs those options do:
 * copying coordinates, opening the player list, and moving the map's camera.
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
    ) {
        guard {
            val where = dimension?.identifier()?.toString() ?: MarkerElements.worldMapDimension()
            options.add(option("Copy coordinates", options.size, target) { copy(x, null, z, where) })
            // The list reads BlueMap, so it is only worth offering on a server that has one.
            if (Session.current != null) {
                options.add(option("Players…", options.size, target) { parent -> open(PlayerListScreen(parent)) })
            }
        }
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

    /** A message on the action bar, which only this client sees. */
    fun say(message: String) {
        Minecraft.getInstance().player?.sendOverlayMessage(Component.literal(message))
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
