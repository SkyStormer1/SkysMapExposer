package com.skystormer.skysmapexposer

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import xaero.common.minimap.waypoints.Waypoint
import xaero.hud.minimap.BuiltInHudModules
import xaero.hud.minimap.waypoint.WaypointColor
import kotlin.math.floor

/**
 * Turns a BlueMap marker into an ordinary, permanent Xaero waypoint — the same as one made by
 * hand, in the waypoint set currently selected — and saves it.
 *
 * The answer is shown on the action bar, which is local to this client: nothing is ever sent to
 * the server.
 */
object Waypoints {

    fun available(): Boolean = FabricLoader.getInstance().isModLoaded("xaerominimap")

    fun save(pin: Markers.Pin) {
        say(
            try {
                add(pin)
            } catch (e: Throwable) {
                Log.error("Could not save ${pin.label} as a waypoint", e)
                "Could not save the waypoint: ${e.message ?: e.javaClass.simpleName}"
            }
        )
    }

    private fun add(pin: Markers.Pin): String {
        if (!available()) return "Saving waypoints needs Xaero's Minimap"
        val minecraft = Minecraft.getInstance()
        val mapDimension = xaero.map.WorldMapSession.getCurrentSession()?.mapProcessor?.mapWorld?.currentDimension?.dimId
        if (mapDimension != null && minecraft.level?.dimension() != mapDimension) {
            return "Go to ${mapDimension.identifier().path} first: waypoints are saved to the dimension you are in"
        }
        val session = BuiltInHudModules.MINIMAP.currentSession ?: return "Xaero's Minimap is not running"
        val world = session.worldManager.currentWorld ?: return "Xaero's Minimap has no waypoint world open"
        val set = world.currentWaypointSet ?: return "Xaero's Minimap has no waypoint set selected"
        val x = floor(pin.x).toInt()
        val y = floor(pin.y).toInt()
        val z = floor(pin.z).toInt()
        if (set.waypoints.any { it.x == x && it.z == z && it.name == pin.label }) return "${pin.label} is already a waypoint"
        set.add(Waypoint(x, y, z, pin.label, initials(pin.label), colourOf(pin)))
        session.worldManagerIO.saveWorld(world)
        return "Saved ${pin.label} as a waypoint"
    }

    private fun say(message: String) {
        Minecraft.getInstance().player?.sendOverlayMessage(Component.literal(message))
    }

    private fun initials(label: String): String {
        val words = label.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        return when {
            words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
            words.size == 1 -> words[0].take(2).uppercase()
            else -> "B"
        }
    }

    /** Banners in the colour of the banner, shops gold, everything else aqua. */
    private fun colourOf(pin: Markers.Pin): WaypointColor {
        val icon = (pin as? Markers.Point)?.icon?.substringAfterLast('/')?.substringBeforeLast('.')?.lowercase() ?: ""
        return when (icon) {
            "white" -> WaypointColor.WHITE
            "orange" -> WaypointColor.GOLD
            "magenta" -> WaypointColor.MAGENTA
            "light_blue" -> WaypointColor.LIGHT_BLUE
            "yellow" -> WaypointColor.YELLOW
            "lime" -> WaypointColor.LIME
            "pink" -> WaypointColor.PINK
            "gray" -> WaypointColor.DARK_GRAY
            "light_gray" -> WaypointColor.GRAY
            "cyan" -> WaypointColor.DARK_AQUA
            "purple" -> WaypointColor.DARK_PURPLE
            "blue" -> WaypointColor.BLUE
            "brown" -> WaypointColor.BROWN
            "green" -> WaypointColor.DARK_GREEN
            "red" -> WaypointColor.RED
            "black" -> WaypointColor.BLACK
            "shop" -> WaypointColor.GOLD
            else -> WaypointColor.AQUA
        }
    }
}
