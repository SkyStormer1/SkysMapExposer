package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft

/** What every map shows as pins: BlueMap's markers and players, and pings, by the settings. */
object MapPins {

    /**
     * The markers and players BlueMap has for [dimension], in that dimension's own coordinates.
     *
     * Asked for every frame, so the list is kept and only rebuilt when what it is made from
     * changes. Markers and players are each replaced by a new list when read again, so comparing
     * them by identity is enough; another dimension or a switch turned off gives another list or none.
     */
    fun pinsFor(dimension: String?): List<Markers.Pin> {
        if (dimension == null) return emptyList()
        val session = Session.current
        // Pings need no BlueMap, so a server without one still gets them.
        val map = session?.server?.markersFor(dimension)
        val snapshot = if (map != null && Config.showMarkers) session.markers.of(map) else null
        val players = if (map != null && Config.showPlayers) session.markers.players(map) else null
        val pings = PlayerPings.pinsIn(dimension)
        val me = Minecraft.getInstance().player?.uuid
        if (snapshot === pinsSnapshot && players === pinsPlayers && pings === pinsPings && me == pinsMe) return pins
        val built = ArrayList<Markers.Pin>()
        snapshot?.let { built.addAll(it.points) }
        players?.filterTo(built) { it.uuid != me }
        built.addAll(pings)
        pins = built
        pinsSnapshot = snapshot
        pinsPlayers = players
        pinsPings = pings
        pinsMe = me
        return built
    }

    // What [pinsFor] last built [pins] from.
    private var pins: List<Markers.Pin> = emptyList()
    private var pinsSnapshot: Markers.Snapshot? = null
    private var pinsPlayers: List<Markers.Player>? = null
    private var pinsPings: List<Markers.Ping>? = null
    private var pinsMe: java.util.UUID? = null
}
