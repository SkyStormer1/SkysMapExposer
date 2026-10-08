package com.skystormer.skysmapexposer

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import xaero.common.minimap.waypoints.Waypoint
import xaero.hud.minimap.BuiltInHudModules
import xaero.hud.minimap.waypoint.WaypointColor
import xaero.hud.minimap.world.MinimapWorld
import xaero.map.mods.SupportMods
import kotlin.math.floor

/**
 * Turns a BlueMap marker into an ordinary, permanent Xaero waypoint — the same as one made by
 * hand, in the waypoint set currently selected — and saves it; or puts a temporary waypoint on it,
 * the same as Xaero's own "Set Temporary Waypoint".
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

    /** A temporary waypoint on [pin], made by Xaero exactly as its own menu makes one: gone when you leave. */
    fun setTemporary(pin: Markers.Pin) {
        say(
            try {
                temporary(pin)
            } catch (e: Throwable) {
                Log.error("Could not set a temporary waypoint on ${pin.label}", e)
                "Could not set the waypoint: ${e.message ?: e.javaClass.simpleName}"
            }
        )
    }

    private fun temporary(pin: Markers.Pin): String {
        if (!available()) return "Temporary waypoints need Xaero's Minimap"
        wrongDimension()?.let { return it }
        val session = BuiltInHudModules.MINIMAP.currentSession ?: return "Xaero's Minimap is not running"
        val world = session.worldManager.currentWorld ?: return "Xaero's Minimap has no waypoint world open"
        session.waypointSession.temporaryHandler.createTemporaryWaypoint(world, floor(pin.x).toInt(), floor(pin.y).toInt(), floor(pin.z).toInt())
        return "Temporary waypoint set on ${pin.label}"
    }

    /** "Nether" for the Overworld and "Overworld" for the Nether: the dimensions with a counterpart. */
    fun counterpartName(dimension: String?): String? = when (dimension) {
        Config.OVERWORLD -> "Nether"
        Config.NETHER -> "Overworld"
        else -> null
    }

    /** Block ([x], [z]) of [dimension] in the other one, written as the waypoint will have it. */
    private fun counterpartCoordinates(x: Int, z: Int, dimension: String): String =
        if (dimension == Config.OVERWORLD) "${Math.floorDiv(x, NETHER_SCALE)}, $NETHER_Y, ${Math.floorDiv(z, NETHER_SCALE)}"
        else "${x * NETHER_SCALE}, ${z * NETHER_SCALE}"

    /** What [setCounterpart] would do for this block, for the menu's tooltip. */
    fun counterpartTip(x: Int, z: Int, dimension: String): String =
        if (dimension == Config.OVERWORLD) "At ${counterpartCoordinates(x, z, dimension)}, on the Nether roof. It shows when you are in the Nether."
        else "At ${counterpartCoordinates(x, z, dimension)}, height unknown. It shows when you are in the Overworld."

    /**
     * A temporary waypoint at the other dimension's counterpart of block ([x], [z]) of [dimension]:
     * an eighth as far out in the Nether, eight times in the Overworld. It goes in that dimension's
     * own waypoints, so it is there when you arrive, wherever you are when you set it.
     *
     * In the Nether it is put at Y [NETHER_Y], on top of the bedrock roof, where Nether highways
     * usually run. The Overworld has no such height, so there Y is left out and Xaero shows it as
     * unknown.
     */
    fun setCounterpart(x: Int, z: Int, dimension: String) {
        say(
            try {
                counterpart(x, z, dimension)
            } catch (e: Throwable) {
                Log.error("Could not set a temporary waypoint in the other dimension", e)
                "Could not set the waypoint: ${e.message ?: e.javaClass.simpleName}"
            }
        )
    }

    private fun counterpart(x: Int, z: Int, dimension: String): String {
        if (!available()) return "Temporary waypoints need Xaero's Minimap"
        val toNether = dimension == Config.OVERWORLD
        val target = if (toNether) Level.NETHER else Level.OVERWORLD
        val session = BuiltInHudModules.MINIMAP.currentSession ?: return "Xaero's Minimap is not running"
        session.worldManager.currentWorld ?: return "Xaero's Minimap has no waypoint world open"
        val world = worldFor(target) ?: return "Xaero's Minimap has no waypoints for the ${counterpartName(dimension)} yet"
        // Xaero converts from the scale it is given to the world's own, so the coordinates go in as
        // they are in the dimension clicked, with that dimension's scale.
        val scale = if (toNether) 1.0 else NETHER_SCALE.toDouble()
        val y = if (toNether) NETHER_Y else 0
        session.waypointSession.temporaryHandler.createTemporaryWaypoint(world, x, y, z, toNether, scale)
        SupportMods.xaeroMinimap?.requestWaypointsRefresh()
        return "Temporary waypoint set in the ${counterpartName(dimension)} at ${counterpartCoordinates(x, z, dimension)}"
    }

    /**
     * Where Xaero's Minimap keeps [target]'s waypoints on this server: the world you are in if it
     * is of [target], else the one found the way its world map finds it — the dimension's folder
     * under the same root as the world you are in. Null if there is none yet.
     */
    fun worldFor(target: ResourceKey<Level>): MinimapWorld? {
        val session = BuiltInHudModules.MINIMAP.currentSession ?: return null
        val manager = session.worldManager
        val current = manager.currentWorld ?: return null
        if (current.dimId == target) return current
        val path = current.container.root.path.resolve(session.dimensionHelper.getDimensionDirectoryName(target))
        val container = manager.getWorldContainerNullable(path)
        return container?.getFirstWorldConnectedTo(current) ?: container?.firstWorld ?: manager.getWorld(path.resolve("waypoints"))
    }

    /** Why a waypoint cannot go on a pin of the map's dimension from here, or null when it can. */
    private fun wrongDimension(): String? {
        val mapDimension = xaero.map.WorldMapSession.getCurrentSession()?.mapProcessor?.mapWorld?.currentDimension?.dimId
        if (mapDimension != null && Minecraft.getInstance().level?.dimension() != mapDimension) {
            return "Go to ${mapDimension.identifier().path} first: waypoints are saved to the dimension you are in"
        }
        return null
    }

    private fun add(pin: Markers.Pin): String {
        if (!available()) return "Saving waypoints needs Xaero's Minimap"
        wrongDimension()?.let { return it }
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

    /** Height of a waypoint put in the Nether from the Overworld. */
    private const val NETHER_Y = 128

    /** Overworld blocks to one Nether block. */
    private const val NETHER_SCALE = 8
}
