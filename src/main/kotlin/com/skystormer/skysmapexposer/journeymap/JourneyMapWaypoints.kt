package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.levelKey
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.Markers
import com.skystormer.skysmapexposer.ShareFormat
import com.skystormer.skysmapexposer.ShareableWaypoint
import com.skystormer.skysmapexposer.Ui
import com.skystormer.skysmapexposer.WaypointRules
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.common.waypoint.Waypoint
import journeymap.api.v2.common.waypoint.WaypointFactory
import journeymap.api.v2.common.waypoint.WaypointGroup
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import kotlin.math.floor

/**
 * Waypoints on JourneyMap, by the same rules as on Xaero's ([WaypointRules]): markers saved, shares
 * and pings added, and temporary ones, all in a "Map Exposer" group of JourneyMap's so they can be
 * switched off together; and yours read back to share. Temporary ones last until you delete them or
 * leave the world ([forget]), as Xaero's do, a change of dimension included.
 *
 * Temporary ones carry a mark of this mod's ([TEMPORARY]), not JourneyMap's "persistent" flag: where
 * a server keeps the waypoints (JourneyMap on the server, as in single player), saved ones come back
 * flagged as not kept, which would have shared nothing and deleted saved ones on leaving; and that
 * server keeps temporary ones too, so any left over are removed shortly after joining ([tidy]).
 */
internal object JourneyMapWaypoints {

    private const val GROUP = "Map Exposer"

    /** The mark on this mod's temporary waypoints, kept by JourneyMap with the waypoint. */
    private const val TEMPORARY = "skysmapexposer.temporary"

    /** [pin] of [dimension] as a waypoint: kept, or [temporary] for this visit only. */
    fun save(api: IClientAPI, pin: Markers.Pin, dimension: String, temporary: Boolean): String = guard("save ${pin.label}") {
        val x = floor(pin.x).toInt()
        val y = floor(pin.y).toInt()
        val z = floor(pin.z).toInt()
        if (!temporary && has(api, dimension, pin.label, x, z)) return@guard "${pin.label} is already a waypoint"
        add(api, dimension, pin.label, x, y, z, WaypointRules.RGB[WaypointRules.colourFor(pin)], temporary)
        if (temporary) "Temporary waypoint set on ${pin.label}" else "Saved ${pin.label} as a waypoint"
    }

    /** Waypoints [from] someone else, added to [dimension]'s, leaving out any you already have (same name and place). */
    fun addShared(api: IClientAPI, dimension: String, from: String, waypoints: List<ShareFormat.SharedWaypoint>): String =
        guard("add $from's waypoints") {
            var added = 0
            for (w in waypoints) {
                if (has(api, dimension, w.name, w.x, w.z)) continue
                add(api, dimension, w.name, w.x, w.y, w.z, WaypointRules.RGB[w.color.coerceIn(0, WaypointRules.RGB.lastIndex)], temporary = false)
                added++
            }
            val name = Ui.dimensionName(dimension) ?: dimension
            if (added == 0) "You already have all ${waypoints.size} of $from's waypoints"
            else "Added $added of $from's waypoints to the $name (group \"$GROUP\")" +
                (waypoints.size - added).let { if (it > 0) "; $it you already had" else "" }
        }

    /**
     * A temporary waypoint at block ([x], [z])'s counterpart in the other dimension, an eighth as far
     * out in the Nether and eight times in the Overworld, as Xaero's menu sets one.
     */
    fun counterpart(api: IClientAPI, x: Int, z: Int, dimension: String): String = guard("set a waypoint in the other dimension") {
        val other = WaypointRules.counterpartName(dimension) ?: return@guard "Only the Overworld and the Nether have a counterpart"
        val target = if (dimension == Config.OVERWORLD) Config.NETHER else Config.OVERWORLD
        val (cx, cy, cz) = WaypointRules.counterpart(x, z, dimension)
        // JourneyMap needs a height; the Overworld has no rule for one, so it is sea level there.
        add(api, target, "From the ${Ui.dimensionName(dimension)}", cx, cy ?: SEA_LEVEL, cz, WHITE, temporary = true)
        "Temporary waypoint set in the $other at ${WaypointRules.counterpartCoordinates(x, z, dimension)}"
    }

    /** Your waypoints of the dimension you are in, but not this mod's temporary ones, with the group each is in, to share. */
    fun mine(api: IClientAPI, dimension: String? = null): Pair<String, List<ShareableWaypoint>>? {
        val level = Minecraft.getInstance().level ?: return null
        val wanted = dimension ?: level.dimension().identifier().toString()
        val waypoints = api.getAllWaypoints(levelKey(wanted)).filter { !isTemporary(it) }
        return wanted to waypoints.map { w ->
            val group = w.groupId?.let { api.getWaypointGroup(it)?.name } ?: "JourneyMap"
            ShareableWaypoint(group, shareable(w, wanted))
        }
    }

    /**
     * [waypoint] to share as in [dimension]: JourneyMap gives a waypoint's place at the scale of
     * where you are, so a Nether one read from the Overworld comes out eight times too far; read at
     * [dimension]'s scale ([JourneyMapDimension.inScaleOf]). Client thread.
     */
    fun shareable(waypoint: Waypoint, dimension: String): ShareFormat.SharedWaypoint = JourneyMapDimension.inScaleOf(levelKey(dimension)) {
        ShareFormat.SharedWaypoint(waypoint.name, WaypointRules.initials(waypoint.name), waypoint.x, waypoint.y, waypoint.z, true, WaypointRules.nearest(waypoint.color))
    }

    /** Leaving a world: this mod's temporary waypoints go, as Xaero's do. */
    fun forget(api: IClientAPI) = tidy(api)

    /**
     * Removes this mod's temporary waypoints. On leaving; and again shortly after joining, for a
     * server that kept them ([JourneyMapAdapter] calls it once JourneyMap has its waypoints).
     */
    fun tidy(api: IClientAPI) {
        try {
            api.getWaypoints(JourneyMapAdapter.MOD_ID).filter(::isTemporary).forEach { api.removeWaypoint(JourneyMapAdapter.MOD_ID, it) }
        } catch (e: Exception) {
            Log.warn("Could not remove the temporary waypoints from JourneyMap: {}", e.toString())
        }
    }

    private fun isTemporary(waypoint: Waypoint): Boolean = waypoint.getCustomData(TEMPORARY) != null

    /**
     * Adds a waypoint. Every one is handed to JourneyMap as kept: JourneyMap drops the ones it is
     * told not to keep whenever you change dimension, and a temporary one is meant to last until you
     * delete it or leave the world. Those carry [TEMPORARY], by which they go on leaving ([tidy]).
     */
    private fun add(api: IClientAPI, dimension: String, name: String, x: Int, y: Int, z: Int, rgb: Int, temporary: Boolean) {
        val waypoint = WaypointFactory.createWaypoint(JourneyMapAdapter.MOD_ID, BlockPos(x, y, z), name, levelKey(dimension), true)
        waypoint.setColor(rgb)
        if (temporary) waypoint.setCustomData(TEMPORARY, "1")
        group(api).addWaypoint(waypoint)
        api.addWaypoint(JourneyMapAdapter.MOD_ID, waypoint)
    }

    /**
     * Whether [dimension] already has a waypoint [name] at ([x], [z]). JourneyMap hands waypoints back
     * converted to the dimension you are in (an Overworld one eight times as far out seen from the
     * Nether), so the place is converted the same way before comparing.
     */
    private fun has(api: IClientAPI, dimension: String, name: String, x: Int, z: Int): Boolean {
        val here = Minecraft.getInstance().level?.dimension()?.identifier()?.toString()
        val (sx, sz) = when {
            dimension == Config.NETHER && here == Config.OVERWORLD -> x * Config.NETHER_SCALE to z * Config.NETHER_SCALE
            dimension == Config.OVERWORLD && here == Config.NETHER -> Math.floorDiv(x, Config.NETHER_SCALE) to Math.floorDiv(z, Config.NETHER_SCALE)
            else -> x to z
        }
        return api.getAllWaypoints(levelKey(dimension)).any { it.x == sx && it.z == sz && it.name == name }
    }

    /** JourneyMap's "Map Exposer" group, made the first time it is needed. */
    private fun group(api: IClientAPI): WaypointGroup =
        api.getWaypointGroupByName(JourneyMapAdapter.MOD_ID, GROUP)
            ?: WaypointFactory.createWaypointGroup(JourneyMapAdapter.MOD_ID, GROUP).also(api::addWaypointGroup)

    private inline fun guard(what: String, block: () -> String): String = try {
        block()
    } catch (e: Exception) {
        Log.error("Could not $what on JourneyMap", e)
        "Could not $what: ${e.message ?: e.javaClass.simpleName}"
    }

    private const val SEA_LEVEL = 63
    private const val WHITE = 0xFFFFFF
}
