package com.skystormer.skysmapexposer

import xaero.map.WorldMap
import xaero.map.config.primary.option.WorldMapPrimaryClientConfigOptions
import xaero.map.config.util.WorldMapClientConfigUtils

/**
 * Makes Xaero show the waypoints of the dimension the world map is showing, rather than the ones
 * for the dimension you are standing in.
 *
 * Xaero already does this: `SupportXaeroMinimap` keeps a `waypointWorld` (yours) and a
 * `mapWaypointWorld` (the map's), and its `only_display_current_map_waypoints` option picks between
 * them. The option defaults to off and has no entry in Xaero's settings screen — the only way to
 * reach it is a small toggle in the world map's waypoint mini-menu, which is not somewhere anyone
 * finds by accident. So this switches it on once, through Xaero's own
 * [WorldMapClientConfigUtils.togglePrimaryOption], which is exactly what that button calls and
 * which saves the config the same way.
 *
 * It is Xaero's setting, not this mod's, so: it is only ever turned on, never off; it is left alone
 * if it is already on; and [Config.matchWaypointsToDimension] switches this off for anyone who
 * wants Xaero's original behaviour back.
 */
object WaypointScope {

    /** Once per game launch. Turning it off in Xaero mid-session is not fought. */
    private var applied = false

    /** Called once a world is joined, by which point Xaero's config is up. */
    fun matchToMapDimension() {
        if (applied) return
        applied = true
        if (!Config.matchWaypointsToDimension) return
        try {
            val option = WorldMapPrimaryClientConfigOptions.ONLY_CURRENT_MAP_WAYPOINTS
            val manager = WorldMap.INSTANCE?.configs?.clientConfigManager?.primaryConfigManager ?: return
            if (manager.config.get(option) as? Boolean != false) return
            WorldMapClientConfigUtils.togglePrimaryOption(option)
            Log.info(
                "Turned on Xaero's \"only display current map waypoints\", so the world map shows the " +
                    "waypoints of the dimension it is showing. Undo it in the world map's waypoint menu, " +
                    "or set matchWaypointsToDimension to false in config/skysmapexposer.json."
            )
        } catch (e: Throwable) {
            Log.error("Could not ask Xaero to show the waypoints of the dimension the map is showing", e)
        }
    }
}
