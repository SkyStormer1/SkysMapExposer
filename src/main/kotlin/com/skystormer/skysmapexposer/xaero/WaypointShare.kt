package com.skystormer.skysmapexposer.xaero

import com.skystormer.skysmapexposer.levelKey
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.ShareableWaypoint
import com.skystormer.skysmapexposer.Ui
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.ShareFormat
import net.minecraft.client.resources.language.I18n
import xaero.common.minimap.waypoints.Waypoint
import xaero.hud.minimap.BuiltInHudModules
import xaero.hud.minimap.waypoint.WaypointColor
import xaero.map.mods.SupportMods

/**
 * Xaero's Minimap's side of sharing waypoints ([ChatShare.shareWaypoints]): the waypoints you can
 * pick from — every set of the waypoint world you are in — and adding ones shared with you to the
 * selected set of that dimension's waypoints, the same as ones made by hand.
 *
 * Only touched with Xaero's Minimap installed ([Waypoints.available]).
 */
object WaypointShare {

    /** A set's name as Xaero shows it: its default set is named by a translation key, which this turns into words. */
    fun setName(name: String): String = I18n.get(name)

    /**
     * [dimension] (the one you are in when null) and its waypoints, every set, without temporary
     * ones; null if Xaero has none for it (one you have never been to).
     */
    fun mine(dimension: String? = null): Pair<String, List<ShareableWaypoint>>? {
        val world = (if (dimension == null) BuiltInHudModules.MINIMAP.currentSession?.worldManager?.currentWorld
        else Waypoints.worldFor(levelKey(dimension))) ?: return null
        val dimensionId = dimension ?: world.dimId?.identifier()?.toString() ?: return null
        val entries = ArrayList<ShareableWaypoint>()
        for (set in world.iterableWaypointSets) {
            for (waypoint in set.waypoints) {
                if (waypoint.isTemporary || waypoint.isThirdParty || waypoint.isDestination) continue
                entries.add(
                    ShareableWaypoint(
                        setName(set.name),
                        ShareFormat.SharedWaypoint(
                            waypoint.getName(), waypoint.initials, waypoint.x, waypoint.y, waypoint.z, waypoint.isYIncluded, waypoint.waypointColor.ordinal,
                        ),
                    )
                )
            }
        }
        return dimensionId to entries
    }

    /**
     * [original] (one of Xaero's Minimap's waypoints, as the world map holds it) to share, with the
     * dimension whose waypoints it is among; null if it is in none of the three.
     */
    fun shareable(original: Any?): Pair<String, ShareFormat.SharedWaypoint>? {
        val waypoint = original as? Waypoint ?: return null
        val dimension = listOfNotNull(MarkerElements.worldMapDimension(), Config.OVERWORLD, Config.NETHER, Config.END).distinct().firstOrNull { dimension ->
            Waypoints.worldFor(levelKey(dimension))?.iterableWaypointSets?.any { set -> set.waypoints.any { it === waypoint } } == true
        } ?: return null
        return dimension to ShareFormat.SharedWaypoint(
            waypoint.getName(), waypoint.initials, waypoint.x, waypoint.y, waypoint.z, waypoint.isYIncluded, waypoint.waypointColor.ordinal,
        )
    }

    /**
     * Adds [waypoints] from [from] to the selected set of [dimensionId]'s waypoints, leaving out
     * any you already have (same name and place). Returns what to tell the player.
     */
    fun add(dimensionId: String, from: String, waypoints: List<ShareFormat.SharedWaypoint>): String {
        if (!Waypoints.available()) return "Adding waypoints needs Xaero's Minimap"
        val session = BuiltInHudModules.MINIMAP.currentSession ?: return "Xaero's Minimap is not running"
        val name = Ui.dimensionName(dimensionId) ?: dimensionId
        val target = levelKey(dimensionId)
        val world = Waypoints.worldFor(target) ?: return "Xaero's Minimap has no waypoints for the $name yet; go there once first"
        val set = world.currentWaypointSet ?: return "Xaero's Minimap has no waypoint set selected for the $name"
        var added = 0
        for (w in waypoints) {
            if (set.waypoints.any { it.x == w.x && it.z == w.z && it.getName() == w.name }) continue
            // Xaero's colours by number: one past the end would break its waypoint screens.
            val colour = w.color.coerceIn(0, WaypointColor.values().lastIndex)
            val waypoint = Waypoint(w.x, w.y, w.z, w.name, w.initials.ifEmpty { w.name.take(1).uppercase() }, WaypointColor.values()[colour])
            waypoint.isYIncluded = w.yIncluded
            set.add(waypoint)
            added++
        }
        if (added == 0) return "You already have all ${waypoints.size} of $from's waypoints"
        session.worldManagerIO.saveWorld(world)
        SupportMods.xaeroMinimap?.requestWaypointsRefresh()
        Log.info("Added {} waypoints from {} to the {} (set {})", added, from, dimensionId, set.name)
        val skipped = waypoints.size - added
        return "Added $added of $from's waypoints to the $name (set \"${setName(set.name)}\")" +
            if (skipped > 0) "; $skipped you already had" else ""
    }
}
