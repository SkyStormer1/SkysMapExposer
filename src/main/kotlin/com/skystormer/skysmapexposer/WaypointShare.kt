package com.skystormer.skysmapexposer

import net.minecraft.client.resources.language.I18n
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import xaero.common.minimap.waypoints.Waypoint
import xaero.hud.minimap.BuiltInHudModules
import xaero.map.mods.SupportMods

/**
 * Xaero's Minimap's side of sharing waypoints ([ChatShare.shareWaypoints]): the waypoints you can
 * pick from — every set of the waypoint world you are in — and adding ones shared with you to the
 * selected set of that dimension's waypoints, the same as ones made by hand.
 *
 * Only touched with Xaero's Minimap installed ([Waypoints.available]).
 */
object WaypointShare {

    /** A waypoint you can share, and the set it is in. */
    class Entry(val set: String, val waypoint: Waypoint) {
        val name: String get() = waypoint.getName()
        val label: String get() = "$name  ${waypoint.x}, ${if (waypoint.isYIncluded) "${waypoint.y}, " else ""}${waypoint.z}"

        fun shared() = ShareFormat.SharedWaypoint(
            waypoint.getName(), waypoint.initials, waypoint.x, waypoint.y, waypoint.z, waypoint.isYIncluded, waypoint.color,
        )
    }

    /** A set's name as Xaero shows it: its default set is named by a translation key, which this turns into words. */
    fun setName(name: String): String = I18n.get(name)

    /** The dimension you are in and its waypoints, every set, without temporary ones; null if Xaero has none open. */
    fun mine(): Pair<String, List<Entry>>? {
        val world = BuiltInHudModules.MINIMAP.currentSession?.worldManager?.currentWorld ?: return null
        val dimensionId = world.dimId?.identifier()?.toString() ?: return null
        val entries = ArrayList<Entry>()
        for (set in world.iterableWaypointSets) {
            for (waypoint in set.waypoints) {
                if (waypoint.isTemporary || waypoint.isServerWaypoint || waypoint.isDestination) continue
                entries.add(Entry(setName(set.name), waypoint))
            }
        }
        return dimensionId to entries
    }

    /**
     * Adds [waypoints] from [from] to the selected set of [dimensionId]'s waypoints, leaving out
     * any you already have (same name and place). Returns what to tell the player.
     */
    fun add(dimensionId: String, from: String, waypoints: List<ShareFormat.SharedWaypoint>): String {
        if (!Waypoints.available()) return "Adding waypoints needs Xaero's Minimap"
        val session = BuiltInHudModules.MINIMAP.currentSession ?: return "Xaero's Minimap is not running"
        val name = MapMenus.dimensionName(dimensionId) ?: dimensionId
        val target = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimensionId))
        val world = Waypoints.worldFor(target) ?: return "Xaero's Minimap has no waypoints for the $name yet; go there once first"
        val set = world.currentWaypointSet ?: return "Xaero's Minimap has no waypoint set selected for the $name"
        var added = 0
        for (w in waypoints) {
            if (set.waypoints.any { it.x == w.x && it.z == w.z && it.getName() == w.name }) continue
            val waypoint = Waypoint(w.x, w.y, w.z, w.name, w.initials.ifEmpty { w.name.take(1).uppercase() }, w.color)
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
