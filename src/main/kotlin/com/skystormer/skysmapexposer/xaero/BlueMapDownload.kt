package com.skystormer.skysmapexposer.xaero

import com.skystormer.skysmapexposer.Ui
import com.skystormer.skysmapexposer.BiomeGuide
import com.skystormer.skysmapexposer.BlockArea
import com.skystormer.skysmapexposer.BlueMap
import com.skystormer.skysmapexposer.BlueMapPicture
import com.skystormer.skysmapexposer.ChatButton
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.LowresSource
import com.skystormer.skysmapexposer.MapLayers
import com.skystormer.skysmapexposer.RegionJob
import com.skystormer.skysmapexposer.Session
import com.skystormer.skysmapexposer.SkippedRegions
import com.skystormer.skysmapexposer.TickGuard
import net.minecraft.client.Minecraft
import xaero.map.MapProcessor
import xaero.map.WorldMapSession
import xaero.map.region.MapRegion
import java.nio.file.Path
import java.util.BitSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
 * A terrain file someone sent you ([TerrainFiles]) is written the same way, region by region with
 * a backup of each first ([startShared]); only where the pixels come from differs.
 *
 * Filling only unexplored chunks, each region is looked at on the client thread before BlueMap's
 * tiles for it are fetched ([Probe]): one your map already has everywhere is skipped, and only the
 * tiles over its gaps are fetched for the rest, so starting a cancelled download again does not
 * fetch what it already wrote.
 *
 * What it shares with JourneyMap's edits (finding, ordering, handing over, skipped regions) is
 * [RegionJob]'s; what is here is how Xaero's regions are backed up, loaded, written and saved.
 */
object BlueMapDownload {

    private enum class Phase { FINDING, WRITING, FINISHING, DONE, FAILED, CANCELLED }

    /**
     * One region to write, and how to make its pixels once there is a palette to make them with;
     * null while they are still being made elsewhere (a shared map's, read off the client thread).
     */
    private class Pending(val regionX: Int, val regionZ: Int, val pixels: (XaeroPalette) -> XaeroWriter.Pixels?)

    /**
     * A region about to have BlueMap's tiles fetched for it, which the client thread looks at first:
     * [answer] is its gaps ([XaeroWriter.gaps]), empty when your map has it all, or null when it
     * could not be looked at, and everything is fetched.
     */
    private class Probe(val regionX: Int, val regionZ: Int) {
        val answer = CompletableFuture<BitSet?>()
        var since = 0L
    }

    private class Active(val pending: Pending) {
        var pixels: XaeroWriter.Pixels? = null
        var region: MapRegion? = null
        var next = 0
        val since = System.currentTimeMillis()
        var retried = false
        /** Whether its file has been copied aside, which comes before anything is written to it. */
        var backedUp = false
    }

    private class Job(
        /** Null for a shared map, which needs no BlueMap. */
        val session: Session?,
        dimensionId: String,
        val mapId: String?,
        /** The region folder of [layer]. */
        val folder: Path,
        val area: BlockArea?,
        /** Only chunks you have never mapped are written; the rest of your map is left as it is. */
        val unexploredOnly: Boolean,
        /** "Download from BlueMap", or what else is being written, for the messages. */
        title: String = "Download from BlueMap",
        val doing: String = "Downloading from BlueMap",
        val source: String = "BlueMap",
        capacity: Int = 2,
        /** Xaero's cave layer written into: the surface, except for a shared map read from a cave layer. */
        val layer: Int = MapLayers.SURFACE,
        /** Says what is said at the end, for a job that is not a download ([MapClear]); null for the usual. */
        val summary: ((XaeroWriter.Tally) -> String)? = null,
        /** Added to the end of what is said at the end, e.g. how many regions of a file could not be read. */
        val afterword: () -> String = { "" },
    ) : RegionJob<Pending>(title, dimensionId, capacity) {
        // Its own client: the session's is closed and replaced whenever the settings are saved.
        val blueMap = session?.let { BlueMap(it.server.url) }
        @Volatile var phase = Phase.FINDING
        @Volatile var message = ""
        @Volatile var error: String? = null
        // Regions Xaero was slow to load, tried once more at the end.
        val later = ArrayDeque<Active>()
        var ticks = 0
        val tally = XaeroWriter.Tally()
        var active: Active? = null
        val saving = ArrayList<Pair<MapRegion, Long>>()
        var palette: XaeroPalette? = null
        val guide: BiomeGuide? by lazy { if (Config.guessBiomes) BiomeGuide.forDimension(dimensionId) else null }
        /** Season dates: a chunk mapped before the season began is written over like one never mapped. */
        val age: MapAge? = MapAge.of(dimensionId, folder)
        /** Regions waiting to be looked at before their tiles are fetched, and those being looked at. */
        val probes = ConcurrentLinkedQueue<Probe>()
        val probing = ArrayList<Probe>()
        /** Regions your map already had everywhere, skipped before fetching anything ([Probe]), and what BlueMap was asked. */
        @Volatile var alreadyMapped = 0
        @Volatile var onBlueMap: RegionJob.Found? = null
        /** Where its copies come from and go back to, and the name of their folder. */
        val origin = MapBackup.Origin(dimensionId, layer, folder, MapBackup.server())
        val label = MapBackup.label(name, layer)
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
    fun start(area: BlockArea?, unexploredOnly: Boolean = false): String {
        if (running) return "A download from BlueMap is already running"
        if (Minecraft.getInstance().hasSingleplayerServer()) return SINGLE_PLAYER
        val session = Session.current ?: return "This server has no BlueMap set up"
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor
            ?: return "Xaero's world map is not ready yet; open it once and try again"
        val dimension = processor.mapWorld?.currentDimension ?: return "Xaero has no dimension open"
        val dimensionId = dimension.dimId?.identifier()?.toString() ?: return "Xaero's dimension has no id"
        val mapId = session.server.markersFor(dimensionId)
            ?: return "No BlueMap map is set for the ${Ui.dimensionName(dimensionId)}; add one in the settings"
        val multiworld = dimension.currentMultiworld ?: return "Xaero has not chosen a map folder for this dimension yet"
        val base = dimension.mainFolderPath?.resolve(multiworld) ?: return "Xaero has not chosen a map folder for this dimension yet"
        // In the Nether, a map of the roof into Xaero's roof layer and one of the inside into its cave layer, never the other way.
        val (into, note) = MapLayers.downloadLayer(processor.currentCaveLayer, XaeroWriter.hasCeiling(dimensionId), XaeroWriter.viewOf(mapId))
        val layer = into ?: return note!!
        val started = Job(session, dimensionId, mapId, XaeroWriter.layerFolder(base, layer), area, unexploredOnly, layer = layer)
        // Each download backs up into a folder of its own, so it can be put back in one go.
        MapBackup.forget()
        job = started
        find(started)
        Log.info(
            "Download from BlueMap map '{}' into the {} started: {}{}", mapId, started.name,
            area?.let { "blocks ${it.minX}, ${it.minZ} to ${it.maxX}, ${it.maxZ}" } ?: "everything BlueMap has",
            if (unexploredOnly) " (unexplored chunks only)" else "",
        )
        return "Downloading from BlueMap…" + (note?.let { " ($it)" } ?: "")
    }

    /**
     * Writes [regions] — a map [from] shared, each region's position and how to read it when its
     * turn comes — into [dimensionId], which the world map has to be showing; with
     * [unexploredOnly], only into chunks you have never mapped. Returns what to tell the player.
     */
    fun startShared(
        regions: List<Triple<Int, Int, () -> XaeroWriter.Pixels?>>, dimensionId: String, layer: Int, from: String, unexploredOnly: Boolean,
        afterword: () -> String = { "" },
    ): String =
        if (Minecraft.getInstance().hasSingleplayerServer()) SINGLE_PLAYER
        else startLocal(regions, dimensionId, layer, unexploredOnly, "Adding $from's map", "Adding $from's map", "$from's map", afterword, null)
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
        afterword: () -> String = { "" },
        summary: ((XaeroWriter.Tally) -> String)?,
    ): String? {
        fun no(why: String): String? = null.also { lastRefusal = why }
        if (running) return no("Something is already being written into your map; try again when it has finished")
        val (shown, why) = XaeroWriter.shown()
        shown ?: return no(why!!)
        val name = Ui.dimensionName(dimensionId) ?: dimensionId
        if (shown.dimensionId != dimensionId) return no("That map is of the $name; switch the world map to the $name and try again")
        val started = Job(
            null, dimensionId, null, XaeroWriter.layerFolder(shown.base, layer), null, unexploredOnly,
            title = title, doing = doing, source = source, capacity = Int.MAX_VALUE,
            layer = layer, summary = summary, afterword = afterword,
        )
        for ((rx, rz, pixels) in regions) started.ready.add(Pending(rx, rz) { pixels() })
        started.total = regions.size
        started.finding = false
        started.found = true
        started.phase = Phase.WRITING
        MapBackup.forget()
        job = started
        Log.info("{} in the {} (cave layer {}): {} regions{}", title, name, layer, regions.size, if (unexploredOnly) " (unexplored chunks only)" else "")
        return started.label
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
        RegionJob.last = null
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

    private fun find(job: Job) = job.findOff(
        "SkysMapExposer download",
        // Once writing has begun, what is already queued is finished and saved as usual.
        failed = { why -> if (job.phase == Phase.WRITING) job.error = why else fail(job, why) },
        after = {
            job.blueMap?.close()
            if (job.cancelled && job.phase == Phase.FINDING) {
                job.phase = Phase.CANCELLED
                job.message = "Download from BlueMap cancelled before anything was written"
                job.endedAt = System.currentTimeMillis()
            }
        },
    ) {
        job.message = "Asking BlueMap what it has…"
        val found = job.findOnBlueMap(job.blueMap!!, job.mapId!!, job.area) ?: return@findOff
        job.onBlueMap = found
        val size = found.tileSize
        job.total = found.regions.size
        if (found.regions.isEmpty()) return@findOff fail(job, "BlueMap has nothing there to download")
        Log.info("Download: {} regions to look at, {} BlueMap tiles", found.regions.size, found.finest.size)

        job.phase = Phase.WRITING
        job.finding = false
        // BlueMap's tiles, fetched once each while neighbouring regions need them. Only tiles
        // BlueMap has anything in are asked for.
        val source = LowresSource(size, hasSea = job.dimensionId == Config.OVERWORLD, found::tile)
        val regions = found.regions
        // Only unexplored chunks: the client thread looks at each region a few ahead ([Probe]).
        val asked = ArrayDeque<Probe>()
        var nextAsked = 0
        for ((rx, rz) in regions) {
            if (job.cancelled) break
            val gaps = if (job.unexploredOnly) {
                while (nextAsked < regions.size && asked.size < PROBE_AHEAD) {
                    val (x, z) = regions[nextAsked++]
                    asked.addLast(Probe(x, z).also(job.probes::add))
                }
                answer(job, asked.removeFirst())
            } else null
            if (job.cancelled) break
            if (gaps != null && gaps.isEmpty) {
                job.alreadyMapped++
                job.done.incrementAndGet()
                continue
            }
            val a = job.area
            val picture = BlueMapPicture.region(rx, rz, size, a?.minX, a?.minZ, a?.maxX, a?.maxZ) { tx, tz ->
                if (gaps != null && !anyGapUnder(gaps, rx, rz, tx, tz, size)) null else source.tile(tx, tz)?.tile
            }
            if (picture.count == 0) {
                job.done.incrementAndGet()
                continue
            }
            picture.guess = job.guide?.guess(source, rx, rz)
            if (!job.hand(Pending(rx, rz) { palette -> XaeroWriter.BlueMapPixels(picture, palette) })) break
        }
    }

    /** What the client thread found of [probe]'s region; null if cancelled first. Download thread. */
    private fun answer(job: Job, probe: Probe): BitSet? {
        while (!job.cancelled) {
            try {
                return probe.answer.get(200, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                // still waiting
            }
        }
        return null
    }

    /** Whether any of [gaps] of region ([rx], [rz]) is under BlueMap tile ([tx], [tz]), [size] blocks a side. */
    private fun anyGapUnder(gaps: BitSet, rx: Int, rz: Int, tx: Int, tz: Int, size: Int): Boolean {
        val x0 = maxOf(tx * size, rx * BlueMapPicture.REGION)
        val x1 = minOf(tx * size + size - 1, rx * BlueMapPicture.REGION + BlueMapPicture.REGION - 1)
        val z0 = maxOf(tz * size, rz * BlueMapPicture.REGION)
        val z1 = minOf(tz * size + size - 1, rz * BlueMapPicture.REGION + BlueMapPicture.REGION - 1)
        if (x0 > x1 || z0 > z1) return false
        for (cz in (z0 shr 4)..(z1 shr 4)) for (cx in (x0 shr 4)..(x1 shr 4)) {
            if (gaps[(cz - rz * 32) * 32 + (cx - rx * 32)]) return true
        }
        return false
    }

    private fun fail(job: Job, why: String) {
        job.phase = Phase.FAILED
        job.message = why
        job.endedAt = System.currentTimeMillis()
        Log.warn("Download: {}", why)
    }

    // ---- the client thread ----

    /**
     * Each client tick: writes a little, and keeps an eye on what Xaero is saving. This reaches
     * deep into Xaero, so an error stops the job rather than the game: what was written and saved
     * before stays, and the end message offers the copies to put it all back.
     */
    fun tick() {
        val job = job ?: return
        if (job.phase != Phase.WRITING && job.phase != Phase.FINISHING) return
        TickGuard.run(job.title, { why -> crashed(job, why) }) { tick(job) }
    }

    private fun crashed(job: Job, why: String) {
        job.error = why
        job.cancelled = true
        job.active = null
        end(job)
    }

    private fun tick(job: Job) {
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return
        val now = System.currentTimeMillis()

        job.saving.removeAll { (region, since) ->
            val saved = XaeroWriter.saved(region)
            if (!saved && now - since > SAVE_WAIT) {
                Log.warn("Download: Xaero had not saved region {}_{} after {} s; it will save it later", region.regionX, region.regionZ, SAVE_WAIT / 1000)
            }
            saved || now - since > SAVE_WAIT
        }

        // A job cancelled between regions has nothing left to write, so it ends whatever the map shows.
        if (!(job.cancelled && job.active == null)) {
            pausedFor(job, processor)?.let { job.message = it; return }
        }
        probe(job, processor, now)

        val active = job.active ?: run {
            if (job.cancelled || job.saving.size >= MAX_SAVING) null
            else job.ready.poll()?.let { pending -> begin(job, pending) }
                ?: if (job.found) job.later.removeFirstOrNull()?.also { job.active = it } else null
        }
        if (active != null && backUp(job, active)) write(job, processor, active, now)

        val finished = job.found && job.ready.isEmpty() && job.later.isEmpty() && job.active == null
        if (finished || (job.cancelled && job.active == null)) {
            job.phase = Phase.FINISHING
            if (job.saving.isEmpty()) end(job)
            else job.message = "${job.title}: waiting for Xaero to save the last ${job.saving.size} region(s)…"
        } else if (job.active?.backedUp == false) {
            job.message = "${job.doing}: having Xaero save region ${job.done.get() + 1} of ${job.total} before copying it aside…"
        } else {
            job.message = "${job.doing}: region ${job.done.get() + 1} of ${job.total} " +
                "(${job.tally.newChunks + job.tally.replacedChunks} chunks so far)"
        }
        // Away from the map, the action bar says how it is going.
        if (++job.ticks % 40 == 0 && Minecraft.getInstance().gui.screen() == null) Ui.say(job.message)
    }

    private fun begin(job: Job, pending: Pending): Active = Active(pending).also { job.active = it }

    /**
     * Looks at the regions the download thread is about to fetch tiles for ([Probe]), a few at a
     * time: Xaero loads each, and its gaps go back. One Xaero does not load in time gets everything
     * fetched, as before.
     */
    private fun probe(job: Job, processor: MapProcessor, now: Long) {
        while (job.probing.size < PROBES_AT_ONCE) job.probing.add(job.probes.poll()?.also { it.since = now } ?: break)
        job.probing.removeAll { probe ->
            if (job.cancelled) return@removeAll true.also { letGo(processor, job, probe) }
            val region = XaeroWriter.loaded(processor, job.folder, probe.regionX, probe.regionZ, job.layer)
            when {
                region != null -> {
                    val gaps = synchronized(region) { XaeroWriter.gaps(region, job.area, job.age) }
                    XaeroWriter.release(region)
                    probe.answer.complete(gaps)
                    true
                }
                now - probe.since > PROBE_WAIT -> true.also { letGo(processor, job, probe) }
                else -> false
            }
        }
    }

    /** Done with [probe] without an answer: everything is fetched, and Xaero need not keep its region in full. */
    private fun letGo(processor: MapProcessor?, job: Job, probe: Probe) {
        probe.answer.complete(null)
        processor?.getLeafMapRegion(job.layer, probe.regionX, probe.regionZ, false)?.let(XaeroWriter::release)
    }

    /** Why the job has to wait until the world map shows its map again, or null when it does. */
    private fun pausedFor(job: Job, processor: MapProcessor): String? {
        // By name: Xaero can make its dimensions anew while you play (on some servers, after a trip to the
        // Nether and back), so the one the job started with may no longer be the one it shows.
        val dimension = processor.mapWorld?.currentDimension
        if (dimension == null || dimension.dimId?.identifier()?.toString() != job.dimensionId) {
            return "${job.title} paused: switch the world map back to the ${job.name} to carry on"
        }
        // On a server with several worlds behind a proxy, Xaero can switch map folders (its
        // multiworld) within the same dimension; the job's regions are in the folder it started in.
        val base = dimension.currentMultiworld?.let { dimension.mainFolderPath?.resolve(it) }
        if (base == null || !job.folder.startsWith(base)) {
            return "${job.title} paused: Xaero is showing another map folder of the ${job.name}; switch back to the one it started in to carry on"
        }
        return null
    }

    /**
     * Copies the region aside before anything is written to it, once Xaero has saved what it holds
     * of it; without that copy, it is skipped. True once it may be written.
     */
    private fun backUp(job: Job, active: Active): Boolean {
        if (active.backedUp) return true
        val pending = active.pending
        return when (val kept = MapBackup.keep(job.origin, job.label, pending.regionX, pending.regionZ)) {
            MapBackup.Kept.Yes -> true.also { active.backedUp = true }
            MapBackup.Kept.Wait -> false
            is MapBackup.Kept.No -> {
                job.skipped.add(pending.regionX, pending.regionZ, SkippedRegions.notBackedUp(kept.why))
                job.done.incrementAndGet()
                job.active = null
                Log.warn("Download: skipped region {}_{} because it could not be backed up first", pending.regionX, pending.regionZ)
                false
            }
        }
    }

    private fun write(job: Job, processor: MapProcessor, active: Active, now: Long) {
        val pending = active.pending
        val region = active.region ?: XaeroWriter.loaded(processor, job.folder, pending.regionX, pending.regionZ, job.layer)
        if (region == null) {
            if (now - active.since > LOAD_WAIT) {
                if (!active.retried) {
                    // Once more at the end, when Xaero is less busy.
                    job.later.addLast(Active(pending).also { it.retried = true; it.backedUp = active.backedUp })
                    job.active = null
                    Log.info("Download: Xaero has not loaded region {}_{} yet; trying it again at the end", pending.regionX, pending.regionZ)
                    return
                }
                job.skipped.add(pending.regionX, pending.regionZ, "${SkippedRegions.NOT_LOADED} in ${LOAD_WAIT / 1000} s")
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
        job.probes.clear()
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor
        job.probing.forEach { letGo(processor, job, it) }
        job.probing.clear()
        val chunks = t.newChunks + t.replacedChunks
        val took = job.took()
        val head = if (job.cancelled) "${job.title} stopped after $took" else if (job.session == null) "${job.title}: done in $took" else "Downloaded from BlueMap in $took"
        val skipped = job.skipped.size
        val skippedNote = if (skipped > 0) ", $skipped region${if (skipped == 1) "" else "s"} skipped (see chat)" else ""
        job.message = when {
            job.error != null -> "${job.error} Written before it stopped: $chunks chunks."
            job.summary != null -> (if (job.cancelled) "$head: " else "") + job.summary.invoke(t) + skippedNote
            chunks == 0 && !job.cancelled -> "${job.source} had nothing new there: your map already matches it" + skippedNote
            else -> "$head: $chunks chunks (${t.newChunks} new, ${t.replacedChunks} updated)$skippedNote"
        } + job.afterword()
        Log.info(
            "{} finished{} in {}: {} new chunks, {} updated, {} pixels written, {} of yours kept as they matched, " +
                "{} unmapped chunks skipped for being only partly on BlueMap, {} cleared, {} regions skipped{}{}",
            job.title, if (job.cancelled) " (cancelled)" else "", took, t.newChunks, t.replacedChunks, t.written, t.kept, t.partialSkipped,
            t.cleared, skipped, if (skipped > 0) ": " + job.skipped.all().joinToString("; ") else "",
            job.onBlueMap?.let { "; ${job.alreadyMapped} regions already mapped everywhere, ${it.fetched.get()} BlueMap tiles fetched" } ?: "",
        )
        Ui.say(job.message)
        // The action bar is gone in seconds: what was skipped, and the copies to undo it with, go to chat.
        val batch = MapBackup.batchName(job.label)?.takeIf { chunks > 0 || t.cleared > 0 || job.error != null }
        // A clear offers its own [Restore] in its summary.
        val restore = batch?.takeIf { job.summary == null || job.error != null }
        if (skipped > 0 || restore != null || job.error != null) {
            val button = restore?.let { ChatButton.of("[Restore]", "/mapexposer restore $it", "Puts back your map as it was before this: /mapexposer restore $it") }
            Minecraft.getInstance().player?.sendSystemMessage(job.endLine(if (job.error != null) job.message else head, button))
        }
    }

    private const val TILE_CHUNKS = 64
    /**
     * In single player Xaero builds its map from the world's own save, and a region written here is
     * dropped when it does, sometimes with what an earlier download put in it (measured: a region
     * of 1,024 downloaded chunks saved as 4 after a second download into it). The overlay still
     * draws BlueMap's terrain there; JourneyMap keeps downloads in single player too.
     */
    private const val SINGLE_PLAYER = "Downloads and shared maps go into Xaero's map only on a server: in single player Xaero " +
        "rebuilds its map from your world's save and drops them. BlueMap's terrain still shows on the map while Terrain is on."
    /** Regions looked at ahead of the one whose tiles are being fetched, and at once on the client thread. */
    private const val PROBE_AHEAD = 4
    private const val PROBES_AT_ONCE = 2
    private const val PROBE_WAIT = 30_000L
    private const val TILE_CHUNKS_PER_TICK = 8
    /** Time spent writing into your map per tick, so the game keeps its frame rate. */
    private const val WRITE_BUDGET = 4_000_000L
    private const val MAX_SAVING = 3
    private const val LOAD_WAIT = 60_000L
    private const val SAVE_WAIT = 120_000L
}
