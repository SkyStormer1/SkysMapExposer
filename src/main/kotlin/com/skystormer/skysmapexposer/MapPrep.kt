package com.skystormer.skysmapexposer

import xaero.map.WorldMapSession

/**
 * Background preparation of the whole map: after joining, every tile BlueMap has is downloaded and
 * made ready to draw ([TileStore.prepare]), so the map is ready before it is opened. On unless
 * switched off in the settings.
 *
 * - Only where BlueMap's coarsest tiles say there is anything ([CoarseScan]): beyond the world
 *   border and never-rendered areas cost nothing.
 * - From the middle outwards, ring by ring: BlueMap's start position if the map has one, else 0, 0.
 *   The map of the dimension you are in first, then the others. A dimension Xaero shows in cave
 *   mode is left to the end, and left out if it still is then, since the overlay does not draw
 *   there; judged by Xaero's own layer, not the dimension's name.
 * - Tiles already on disk and within their schedule are skipped, so leaving and joining again
 *   carries on where it was.
 * - Gentle: at most [Config.prepareTilesPerSecond] downloads a second, and none while tiles you are
 *   looking at are loading or the game is busy ([FramePacer.paused]).
 *
 * How it is going shows at the top of the world map and in the settings ([status]);
 * `/mapexposer prepare cancel` or the settings' button stops it, and leaving the server does.
 */
object MapPrep {

    private class Job(val session: Session, val maps: List<Pair<String, String>>) {
        @Volatile var cancelled = false
        @Volatile var map = ""
        @Volatile var finding = true
        @Volatile var done = 0
        @Volatile var total = 0
        @Volatile var ended: String? = null
        @Volatile var endedAt = 0L
        val left = ArrayList<String>()
    }

    @Volatile
    private var job: Job? = null

    /** The session it last started for, so it starts once per join. */
    private var startedFor: Session? = null

    // Set on the client thread each tick, read by the preparing thread.
    @Volatile private var gameBusy = false
    @Volatile private var caveDimension: String? = null

    val running: Boolean get() = job?.let { it.ended == null && !it.cancelled } ?: false

    /** Each client tick: what the preparing thread needs to know, and starting it after joining. */
    fun tick(session: Session?) {
        if (session == null) {
            if (job != null) forget()
            startedFor = null
            return
        }
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor
        val dimension = processor?.mapWorld?.currentDimension?.dimId?.identifier()?.toString()
        // Only caves the overlay does not draw over: the Nether's usual cave layer is drawn over.
        caveDimension = dimension?.takeIf {
            XaeroWriter.blueMapLayer(it, processor.currentCaveLayer, session.server.mapFor(it) != null) == null
        }
        gameBusy = FramePacer.game.paused
        if (startedFor !== session && dimension != null) {
            startedFor = session
            if (Config.prepareMap) start(session, dimension)
        }
    }

    /** Starts (again) for [session], the map of [here] first. Returns what to tell the player. */
    fun start(session: Session, here: String?): String {
        if (running) return "The map is already being prepared"
        val dimensions = listOfNotNull(here) + session.server.maps.keys.filter { it != here }
        val maps = dimensions.mapNotNull { dimension -> session.server.mapFor(dimension)?.let { dimension to it } }.distinctBy { it.second }
        if (maps.isEmpty()) return "No BlueMap map's terrain is used on this server"
        val started = Job(session, maps)
        job = started
        Thread({ work(started) }, "SkysMapExposer prepare").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        Log.info("Preparing BlueMap's maps in the background: {}", maps.joinToString { "'${it.second}'" })
        return "Preparing the map in the background"
    }

    /** Stops it; the tiles done so far stay on disk. */
    fun cancel(): Boolean {
        val current = job ?: return false
        if (current.ended != null) return false
        current.cancelled = true
        return true
    }

    /** Leaving the server: it stops, and starts again on the next join. */
    fun forget() {
        job?.cancelled = true
        job = null
    }

    /**
     * One line on how it is going, e.g. "Preparing map 'world': 1,240 / 3,180 tiles (39%)", or
     * null when there is nothing to say. "Map ready" shows for a few seconds at the end.
     */
    fun status(): String? {
        val current = job ?: return null
        current.ended?.let { ended ->
            return if (System.currentTimeMillis() - current.endedAt < SHOWN_AFTER) ended else null
        }
        if (current.cancelled) return null
        val head = "Preparing map '${current.map}'"
        if (current.finding) return "$head: finding BlueMap's tiles…"
        val percent = if (current.total == 0) 0 else current.done * 100 / current.total
        return "$head: %,d / %,d tiles (%d%%)".format(current.done, current.total, percent) + if (gameBusy) ", paused: game is busy" else ""
    }

    // ---- the preparing thread ----

    private fun work(job: Job) {
        try {
            val later = ArrayList<Pair<String, String>>()
            for (target in job.maps) {
                if (job.cancelled) return
                if (!prepare(job, target)) later.add(target)
            }
            // Left for being in cave mode: once more, and left out if they still are.
            for (target in later) {
                if (job.cancelled) return
                if (!prepare(job, target)) job.left.add(MapMenus.dimensionName(target.first) ?: target.first)
            }
            val leftOut = if (job.left.isEmpty()) "" else " (left out, as Xaero shows it in cave mode: ${job.left.joinToString()})"
            end(job, "Map ready$leftOut")
        } catch (e: Exception) {
            if (job.cancelled) return
            Log.warn("Preparing the map stopped: {}", e.toString())
            end(job, "Preparing the map stopped: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            if (job.cancelled && job.ended == null) end(job, "Preparing the map stopped")
        }
    }

    private fun end(job: Job, message: String) {
        job.ended = message
        job.endedAt = System.currentTimeMillis()
        if (!job.cancelled) Log.info("Preparing the map: {}", message)
    }

    /** One map, from the middle outwards. False if left for later, its dimension being in cave mode. */
    private fun prepare(job: Job, target: Pair<String, String>): Boolean {
        val (dimension, map) = target
        if (caveDimension == dimension) return false
        val blueMap = job.session.blueMap
        job.map = map
        job.finding = true
        job.done = 0
        val layout = blueMap.layout(map)
        val (startX, startZ) = runCatching { blueMap.startPosition(map) }.getOrNull() ?: (0 to 0)
        val scan = CoarseScan.scan(layout, startX, startZ, { job.cancelled }) { x, z -> blueMap.tile(map, layout.lodCount, x, z) } ?: return true
        Log.info("Preparing BlueMap map '{}': {}", map, scan)
        val tiles = order(scan.finest, Math.floorDiv(startX, layout.tileSize), Math.floorDiv(startZ, layout.tileSize))
        job.total = tiles.size
        job.finding = false
        var askedAt = 0L
        for ((x, z) in tiles) {
            // Tiles you are looking at come first, and a busy game is left alone.
            while (gameBusy || job.session.tiles.busy.get() > 0) {
                if (job.cancelled) return true
                Thread.sleep(200)
            }
            if (job.cancelled) return true
            if (caveDimension == dimension) return false
            val asked = job.session.tiles.prepare(TileStore.Key(map, 1, x, z), 1, layout.tileSize)
            job.done++
            if (asked) {
                val gap = 1000L / Config.prepareTilesPerSecond.coerceAtLeast(1)
                val wait = askedAt + gap - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                askedAt = System.currentTimeMillis()
            }
        }
        return true
    }

    /** [tiles] ring by ring outwards from tile ([middleX], [middleZ]), nearest first within a ring. */
    fun order(tiles: Collection<Pair<Int, Int>>, middleX: Int, middleZ: Int): List<Pair<Int, Int>> =
        tiles.sortedWith(compareBy<Pair<Int, Int>>({ (x, z) -> maxOf(Math.abs(x - middleX), Math.abs(z - middleZ)) },
            { (x, z) -> (x - middleX).toLong() * (x - middleX) + (z - middleZ).toLong() * (z - middleZ) }))

    private const val SHOWN_AFTER = 10_000L
}
