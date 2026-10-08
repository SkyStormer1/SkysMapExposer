package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import xaero.map.MapProcessor
import xaero.map.WorldMapSession
import xaero.map.region.MapRegion
import xaero.map.world.MapDimension
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloading BlueMap into Xaero's own map: for a rectangle picked on the world map, or for
 * everything BlueMap has in the dimension the map is showing.
 *
 * In order:
 * 1. BlueMap is asked what it has: every finest tile under the rectangle, or, for everything, the
 *    tiles its coarsest level shows anything in.
 * 2. Region by region, nearest to you first: BlueMap's tiles are read into a picture of the region
 *    off the client thread. On the client thread, that region's file is copied aside
 *    ([MapBackup.keep]) — and if the copy fails, the region is not touched — then Xaero loads the
 *    region, [XaeroWriter] writes the picture a few tile chunks per tick, and Xaero saves it before
 *    the next is written over. Each region is backed up just before it is replaced, rather than
 *    the whole map up front.
 *
 * It runs on its own connection to BlueMap and on client ticks, so it carries on while you play,
 * walk about and open and close screens; only leaving the server stops it.
 *
 * The map screen and the settings show how far it has got ([status]); `/mapexposer download
 * cancel` or the settings' button stops it after the region it is on.
 *
 * A map someone shared in chat ([ChatShare]) is written the same way, region by region with a
 * backup of each first ([startShared]); only where the pixels come from differs.
 */
object BlueMapDownload {

    /** A block rectangle, inclusive at both ends. */
    class Area(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int)

    private enum class Phase { FINDING, WRITING, FINISHING, DONE, FAILED, CANCELLED }

    /**
     * One region to write, and how to make its pixels once there is a palette to make them with;
     * null while they are still being made elsewhere (a shared map's, read off the client thread).
     */
    private class Pending(val regionX: Int, val regionZ: Int, val pixels: (XaeroPalette) -> XaeroWriter.Pixels?)

    private class Active(val pending: Pending) {
        var pixels: XaeroWriter.Pixels? = null
        var region: MapRegion? = null
        var next = 0
        val since = System.currentTimeMillis()
        var retried = false
    }

    private class Job(
        /** Null for a shared map, which needs no BlueMap. */
        val session: Session?,
        val dimension: MapDimension,
        val dimensionId: String,
        val mapId: String?,
        /** The region folder of [layer]. */
        val folder: Path,
        val area: Area?,
        /** Only chunks you have never mapped are written; the rest of your map is left as it is. */
        val unexploredOnly: Boolean,
        val centreX: Int,
        val centreZ: Int,
        /** "Download from BlueMap", or what else is being written, for the messages. */
        val title: String = "Download from BlueMap",
        val doing: String = "Downloading from BlueMap",
        val source: String = "BlueMap",
        capacity: Int = 2,
        /** Xaero's cave layer written into: the surface, except for a shared map read from a cave layer. */
        val layer: Int = XaeroWriter.SURFACE,
        /** Says what is said at the end, for a job that is not a download ([MapClear]); null for the usual. */
        val summary: ((XaeroWriter.Tally) -> String)? = null,
    ) {
        // Its own client: the session's is closed and replaced whenever the settings are saved.
        val blueMap = session?.let { BlueMap(it.server.url) }
        @Volatile var phase = Phase.FINDING
        @Volatile var cancelled = false
        @Volatile var scanFinished = false
        @Volatile var message = ""
        @Volatile var error: String? = null
        @Volatile var total = 0
        val done = AtomicInteger()
        val ready = LinkedBlockingQueue<Pending>(capacity)
        // Regions Xaero was slow to load, tried once more at the end.
        val later = ArrayDeque<Active>()
        var ticks = 0
        val tally = XaeroWriter.Tally()
        var skipped = 0
        var active: Active? = null
        val saving = ArrayList<Pair<MapRegion, Long>>()
        var palette: XaeroPalette? = null
        var endedAt = 0L
        val name: String = MapMenus.dimensionName(dimensionId) ?: dimensionId
        val startedAt = System.currentTimeMillis()
        val guide: BiomeGuide? by lazy { if (Config.guessBiomes) BiomeGuide.forDimension(dimensionId) else null }
        /** Season dates: a chunk mapped before the season began is written over like one never mapped. */
        val age: MapAge? = MapAge.of(dimensionId, folder)
    }

    @Volatile
    private var job: Job? = null

    /** Whether a download is under way (and has not yet finished, failed or been cancelled). */
    val running: Boolean
        get() = job?.phase?.let { it != Phase.DONE && it != Phase.FAILED && it != Phase.CANCELLED } ?: false

    /**
     * Starts a download of [area], or of everything BlueMap has when [area] is null, into the
     * dimension the world map is showing; with [unexploredOnly], only into chunks you have never
     * mapped. Returns what to tell the player.
     */
    fun start(area: Area?, unexploredOnly: Boolean = false): String {
        if (running) return "A download from BlueMap is already running"
        val session = Session.current ?: return "This server has no BlueMap set up"
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor
            ?: return "Xaero's world map is not ready yet; open it once and try again"
        val dimension = processor.mapWorld?.currentDimension ?: return "Xaero has no dimension open"
        val dimensionId = dimension.dimId?.identifier()?.toString() ?: return "Xaero's dimension has no id"
        val mapId = session.server.markersFor(dimensionId)
            ?: return "No BlueMap map is set for the ${MapMenus.dimensionName(dimensionId)}; add one in the settings"
        val multiworld = dimension.currentMultiworld ?: return "Xaero has not chosen a map folder for this dimension yet"
        val folder = dimension.mainFolderPath?.resolve(multiworld) ?: return "Xaero has not chosen a map folder for this dimension yet"
        val player = Minecraft.getInstance().player
        val started = Job(
            session, dimension, dimensionId, mapId, folder, area, unexploredOnly,
            player?.blockX ?: 0, player?.blockZ ?: 0,
        )
        // Each download backs up into a folder of its own, so it can be put back in one go.
        MapBackup.forget()
        job = started
        Thread({ work(started) }, "SkysMapExposer download").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        Log.info(
            "Download from BlueMap map '{}' into the {} started: {}{}", mapId, started.name,
            area?.let { "blocks ${it.minX}, ${it.minZ} to ${it.maxX}, ${it.maxZ}" } ?: "everything BlueMap has",
            if (unexploredOnly) " (unexplored chunks only)" else "",
        )
        return "Downloading from BlueMap…"
    }

    /**
     * Writes [regions] — a map [from] shared, each region's position and how to read it when its
     * turn comes — into [dimensionId], which the world map has to be showing; with
     * [unexploredOnly], only into chunks you have never mapped. Returns what to tell the player.
     */
    fun startShared(regions: List<Triple<Int, Int, () -> XaeroWriter.Pixels?>>, dimensionId: String, layer: Int, from: String, unexploredOnly: Boolean): String =
        startLocal(regions, dimensionId, layer, unexploredOnly, "Adding $from's map", "Adding $from's map", "$from's map", null)
            ?.let { "Adding $from's map…" }
            ?: lastRefusal

    /** Why [startLocal] last said no. */
    var lastRefusal = ""
        private set

    /**
     * Writes [regions], whose pixels come from this computer rather than BlueMap — a shared map,
     * or [MapClear]'s nothing — into [dimensionId]'s cave [layer], which the world map has to be
     * showing. Returns the job's backup label once it has started, or null with [lastRefusal] set.
     */
    fun startLocal(
        regions: List<Triple<Int, Int, () -> XaeroWriter.Pixels?>>,
        dimensionId: String,
        layer: Int,
        unexploredOnly: Boolean,
        title: String,
        doing: String,
        source: String,
        summary: ((XaeroWriter.Tally) -> String)?,
    ): String? {
        fun no(why: String): String? = null.also { lastRefusal = why }
        if (running) return no("Something is already being written into your map; try again when it has finished")
        val (shown, why) = XaeroWriter.shown()
        shown ?: return no(why!!)
        val name = MapMenus.dimensionName(dimensionId) ?: dimensionId
        if (shown.dimensionId != dimensionId) return no("That map is of the $name; switch the world map to the $name and try again")
        val player = Minecraft.getInstance().player
        val started = Job(
            null, shown.dimension, dimensionId, null, XaeroWriter.layerFolder(shown.base, layer), null, unexploredOnly,
            player?.blockX ?: 0, player?.blockZ ?: 0,
            title = title, doing = doing, source = source, capacity = Int.MAX_VALUE,
            layer = layer, summary = summary,
        )
        for ((rx, rz, pixels) in regions) started.ready.add(Pending(rx, rz) { pixels() })
        started.total = regions.size
        started.scanFinished = true
        started.phase = Phase.WRITING
        MapBackup.forget()
        job = started
        Log.info("{} in the {} (cave layer {}): {} regions{}", title, name, layer, regions.size, if (unexploredOnly) " (unexplored chunks only)" else "")
        return MapBackup.label(started.name, layer)
    }

    /** Stops after the region being written, which is finished rather than left half done. */
    fun cancel(): Boolean {
        val current = job ?: return false
        if (!running) return false
        current.cancelled = true
        return true
    }

    /** Leaving the server: nothing more can be written, and Xaero saves what it holds itself. */
    fun forget() {
        job?.cancelled = true
        job = null
    }

    /**
     * One line on how the download is going, or null when there is nothing to say. A finished
     * download is reported for a while afterwards, a failed one for longer.
     */
    fun status(): String? {
        val current = job ?: return null
        val shownFor = if (current.phase == Phase.FAILED) 60_000L else 20_000L
        if (current.endedAt != 0L && System.currentTimeMillis() - current.endedAt > shownFor) return null
        return current.message
    }

    // ---- the download thread ----

    private fun work(job: Job) {
        try {
            job.message = "Asking BlueMap what it has…"
            val blueMap = job.blueMap!!
            val mapId = job.mapId!!
            val layout = blueMap.layout(mapId)
            val size = layout.tileSize
            val finest = HashSet<Pair<Int, Int>>()
            val regions: List<Pair<Int, Int>>
            if (job.area != null) {
                val a = job.area
                regions = BlueMapPicture.regionsOver(a.minX, a.minZ, a.maxX, a.maxZ)
                finest.addAll(BlueMapPicture.tilesOver(a.minX, a.minZ, a.maxX, a.maxZ, size))
            } else {
                // The coarsest level is a few tiles for a whole world, and says where the finest
                // ones have anything. ±COARSE_REACH tiles covers any world border in use.
                val lod = layout.lodCount
                val blocksPerPixel = layout.blocksPerPixel(lod)
                for (tx in -COARSE_REACH until COARSE_REACH) {
                    for (tz in -COARSE_REACH until COARSE_REACH) {
                        if (job.cancelled) return
                        val bytes = blueMap.tile(mapId, lod, tx, tz) ?: continue
                        BlueMapPicture.finestTilesIn(BlueMapPicture.decode(tx, tz, size, bytes), blocksPerPixel, size, finest)
                    }
                }
                val found = HashSet<Pair<Int, Int>>()
                for ((tx, tz) in finest) found.addAll(BlueMapPicture.regionsOver(tx * size, tz * size, tx * size + size - 1, tz * size + size - 1))
                regions = found.toList()
            }
            val ordered = regions.sortedBy { (rx, rz) ->
                val dx = rx * BlueMapPicture.REGION + 256L - job.centreX
                val dz = rz * BlueMapPicture.REGION + 256L - job.centreZ
                dx * dx + dz * dz
            }
            job.total = ordered.size
            if (ordered.isEmpty()) return fail(job, "BlueMap has nothing there to download")
            Log.info("Download: {} regions to look at, {} BlueMap tiles", ordered.size, finest.size)

            job.phase = Phase.WRITING
            // BlueMap's tiles, fetched once each while neighbouring regions need them. Only tiles
            // BlueMap has anything in are asked for.
            val source = LowresSource(size, hasSea = job.dimensionId == Config.OVERWORLD) { tx, tz ->
                if (tx to tz !in finest) null
                else blueMap.tile(mapId, 1, tx, tz)?.let { BlueMapPicture.decode(tx, tz, size, it) }
            }
            for ((rx, rz) in ordered) {
                if (job.cancelled) break
                val picture = BlueMapPicture.RegionPicture(rx, rz)
                val a = job.area
                val minX = maxOf(picture.originX, a?.minX ?: Int.MIN_VALUE)
                val minZ = maxOf(picture.originZ, a?.minZ ?: Int.MIN_VALUE)
                val maxX = minOf(picture.originX + BlueMapPicture.REGION - 1, a?.maxX ?: Int.MAX_VALUE)
                val maxZ = minOf(picture.originZ + BlueMapPicture.REGION - 1, a?.maxZ ?: Int.MAX_VALUE)
                for ((tx, tz) in BlueMapPicture.tilesOver(minX, minZ, maxX, maxZ, size)) {
                    source.tile(tx, tz)?.let { picture.fill(it.tile, minX, minZ, maxX, maxZ) }
                }
                if (picture.count == 0) {
                    job.done.incrementAndGet()
                    continue
                }
                picture.guess = job.guide?.guess(source, rx, rz)
                val pending = Pending(rx, rz) { palette -> XaeroWriter.BlueMapPixels(picture, palette) }
                while (!job.ready.offer(pending, 200, TimeUnit.MILLISECONDS)) {
                    if (job.cancelled) break
                }
            }
        } catch (e: Throwable) {
            Log.error("Download from BlueMap failed", e)
            val why = "The download stopped: ${e.message ?: e.javaClass.simpleName}. The log says more."
            // Once writing has begun, what is already queued is finished and saved as usual.
            if (job.phase == Phase.WRITING) job.error = why else fail(job, why)
        } finally {
            job.scanFinished = true
            job.blueMap?.close()
            if (job.cancelled && job.phase == Phase.FINDING) {
                job.phase = Phase.CANCELLED
                job.message = "Download from BlueMap cancelled before anything was written"
                job.endedAt = System.currentTimeMillis()
            }
        }
    }

    private fun fail(job: Job, why: String) {
        job.phase = Phase.FAILED
        job.message = why
        job.endedAt = System.currentTimeMillis()
        Log.warn("Download: {}", why)
    }

    // ---- the client thread ----

    /** Each client tick: writes a little, and keeps an eye on what Xaero is saving. */
    fun tick() {
        val job = job ?: return
        if (job.phase != Phase.WRITING && job.phase != Phase.FINISHING) return
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return
        val now = System.currentTimeMillis()

        job.saving.removeAll { (region, since) ->
            val saved = XaeroWriter.saved(region)
            if (!saved && now - since > SAVE_WAIT) {
                Log.warn("Download: Xaero had not saved region {}_{} after {} s; it will save it later", region.regionX, region.regionZ, SAVE_WAIT / 1000)
            }
            saved || now - since > SAVE_WAIT
        }

        if (processor.mapWorld?.currentDimension !== job.dimension) {
            job.message = "${job.title} paused: switch the world map back to the ${job.name} to carry on"
            return
        }

        val active = job.active ?: run {
            if (job.cancelled || job.saving.size >= MAX_SAVING) null
            else job.ready.poll()?.let { pending -> begin(job, pending) }
                ?: if (job.scanFinished) job.later.removeFirstOrNull()?.also { job.active = it } else null
        }
        if (active != null) write(job, processor, active, now)

        val finished = job.scanFinished && job.ready.isEmpty() && job.later.isEmpty() && job.active == null
        if (finished || (job.cancelled && job.active == null)) {
            job.phase = Phase.FINISHING
            if (job.saving.isEmpty()) end(job)
            else job.message = "${job.title}: waiting for Xaero to save the last ${job.saving.size} region(s)…"
        } else {
            job.message = "${job.doing}: region ${job.done.get() + 1} of ${job.total} " +
                "(${job.tally.newChunks + job.tally.replacedChunks} chunks so far)"
        }
        // Away from the map, the action bar says how it is going.
        if (++job.ticks % 40 == 0 && Minecraft.getInstance().gui.screen() == null) MapMenus.say(job.message)
    }

    /** Copies the region aside before anything is written to it; without that copy, it is skipped. */
    private fun begin(job: Job, pending: Pending): Active? {
        if (!MapBackup.keep(job.folder, MapBackup.label(job.name, job.layer), pending.regionX, pending.regionZ)) {
            job.skipped++
            job.done.incrementAndGet()
            Log.warn("Download: skipped region {}_{} because it could not be backed up first", pending.regionX, pending.regionZ)
            return null
        }
        return Active(pending).also { job.active = it }
    }

    private fun write(job: Job, processor: MapProcessor, active: Active, now: Long) {
        val pending = active.pending
        val region = active.region ?: XaeroWriter.loaded(processor, job.folder, pending.regionX, pending.regionZ, job.layer)
        if (region == null) {
            if (now - active.since > LOAD_WAIT) {
                if (!active.retried) {
                    // Once more at the end, when Xaero is less busy.
                    job.later.addLast(Active(pending).also { it.retried = true })
                    job.active = null
                    Log.info("Download: Xaero has not loaded region {}_{} yet; trying it again at the end", pending.regionX, pending.regionZ)
                    return
                }
                job.skipped++
                job.done.incrementAndGet()
                job.active = null
                Log.warn("Download: Xaero did not load region {}_{} within {} s; skipped it", pending.regionX, pending.regionZ, LOAD_WAIT / 1000)
            }
            return
        }
        active.region = region
        val palette = job.palette ?: Minecraft.getInstance().level?.let { XaeroPalette(it, job.dimensionId) }?.also { job.palette = it } ?: return
        val pixels = active.pixels ?: pending.pixels(palette)?.also { active.pixels = it } ?: return
        val tally = XaeroWriter.Tally()
        // A tile chunk at a time, until this tick's share of time or of tile chunks is used up.
        val deadline = System.nanoTime() + WRITE_BUDGET
        val most = minOf(active.next + TILE_CHUNKS_PER_TICK, TILE_CHUNKS)
        var next = active.next
        while (true) {
            val until = next + 1
            next = XaeroWriter.write(processor, region, pixels, next, until, tally, job.unexploredOnly, job.age)
            if (next != until || next >= most || System.nanoTime() > deadline) break
        }
        job.tally.add(tally)
        when {
            next < 0 -> {
                // Xaero let go of the region part way; load it again and go over it once more.
                // Pixels already written now match and are left as they are.
                active.region = null
                active.next = 0
            }
            next >= TILE_CHUNKS -> {
                XaeroWriter.finish(processor, region)
                job.saving.add(region to now)
                job.done.incrementAndGet()
                job.active = null
            }
            else -> active.next = next
        }
    }

    private fun end(job: Job) {
        val t = job.tally
        job.phase = when {
            job.error != null -> Phase.FAILED
            job.cancelled -> Phase.CANCELLED
            else -> Phase.DONE
        }
        job.endedAt = System.currentTimeMillis()
        // The job stays for its status line; regions it never got to (a cancelled job's, read
        // ahead from a file) would otherwise stay in memory with it until the next one.
        job.ready.clear()
        job.later.clear()
        job.active = null
        val chunks = t.newChunks + t.replacedChunks
        val took = duration(job.endedAt - job.startedAt)
        val head = if (job.cancelled) "${job.title} stopped after $took" else if (job.session == null) "${job.title}: done in $took" else "Downloaded from BlueMap in $took"
        job.message = when {
            job.error != null -> "${job.error} Written before it stopped: $chunks chunks."
            job.summary != null -> (if (job.cancelled) "$head: " else "") + job.summary.invoke(t) +
                (if (job.skipped > 0) ", ${job.skipped} regions skipped — see the log" else "")
            chunks == 0 && !job.cancelled -> "${job.source} had nothing new there: your map already matches it"
            else -> "$head: $chunks chunks (${t.newChunks} new, ${t.replacedChunks} updated)" +
                (if (job.skipped > 0) ", ${job.skipped} regions skipped — see the log" else "")
        }
        Log.info(
            "{} finished{} in {}: {} new chunks, {} updated, {} pixels written, {} of yours kept as they matched, " +
                "{} unmapped chunks skipped for being only partly on BlueMap, {} cleared, {} regions skipped",
            job.title, if (job.cancelled) " (cancelled)" else "", took, t.newChunks, t.replacedChunks, t.written, t.kept, t.partialSkipped,
            t.cleared, job.skipped,
        )
        MapMenus.say(job.message)
    }

    private fun duration(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds >= 3600 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
            seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${"%.1f".format(millis / 1000.0)}s"
        }
    }

    private const val COARSE_REACH = 4
    private const val TILE_CHUNKS = 64
    private const val TILE_CHUNKS_PER_TICK = 8
    /** Time spent writing into your map per tick, so the game keeps its frame rate. */
    private const val WRITE_BUDGET = 4_000_000L
    private const val MAX_SAVING = 3
    private const val LOAD_WAIT = 60_000L
    private const val SAVE_WAIT = 120_000L
}
