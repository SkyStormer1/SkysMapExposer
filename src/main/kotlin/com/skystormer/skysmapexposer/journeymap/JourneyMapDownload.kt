package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.BlueMapPicture
import com.skystormer.skysmapexposer.ChatButton
import com.skystormer.skysmapexposer.CoarseScan
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapLayers
import com.skystormer.skysmapexposer.SafeFiles
import com.skystormer.skysmapexposer.SeaFloor
import com.skystormer.skysmapexposer.Session
import com.skystormer.skysmapexposer.TilePixels
import com.skystormer.skysmapexposer.Ui
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloading BlueMap into JourneyMap's own map, as the Download does into Xaero's: everything
 * BlueMap has of the dimension you are in, or one region; with "unexplored only", just where
 * JourneyMap has nothing. Written into JourneyMap's day map ([JourneyMapFiles]) with BlueMap's
 * shaded colours, the same picture BlueMap's terrain shows on JourneyMap's map.
 *
 * - Pictures are made region by region, nearest first, off the client thread, through the shared
 *   BlueMap connection and its request limit.
 * - Each region is copied aside before it is changed ([JourneyMapFiles.backUp]), into a folder of
 *   this download's own, and [undo] puts the last download back.
 * - A region JourneyMap holds in memory is not written (it would write its own over it): it waits,
 *   a few at most, until JourneyMap lets go of it, and is dropped if you leave first.
 *
 * Only the day map: the night, topo and cave maps are JourneyMap's own to make as you explore.
 */
internal object JourneyMapDownload {

    private class Picture(val regionX: Int, val regionZ: Int, val pixels: IntArray)

    private class Job(
        val session: Session, val dimension: String, val mapId: String, val folder: Path, val backups: Path,
        val region: Pair<Int, Int>?, val unexploredOnly: Boolean, val centreX: Int, val centreZ: Int,
    ) {
        @Volatile var cancelled = false
        @Volatile var total = 0
        @Volatile var finding = true
        @Volatile var made = false
        val looked = AtomicInteger()
        var written = 0
        var unreadable = 0
        val ready = LinkedBlockingQueue<Picture>(QUEUE)
    }

    private var job: Job? = null

    /** Pictures for regions JourneyMap held when their turn came, by region. */
    private val waiting = LinkedHashMap<Pair<Int, Int>, Picture>()
    private var waitingFor: Job? = null
    private var retriedAt = 0L

    /** The last download's backup folder, for [undo]. */
    private var lastBackups: Path? = null

    val running: Boolean get() = job != null

    /** Starts a download into the dimension you are in; [region] for one region only. What to tell you. */
    fun start(unexploredOnly: Boolean, region: Pair<Int, Int>? = null): String {
        if (job != null) return "A download into JourneyMap's map is already running"
        val session = Session.current ?: return "This server has no BlueMap set up"
        val player = Minecraft.getInstance().player ?: return "Not in a world"
        val dimension = player.level().dimension().identifier().toString()
        val mapId = session.server.markersFor(dimension)
            ?: return "No BlueMap map is set for the ${Ui.dimensionName(dimension)}; add one in the settings"
        // The day map is the surface; in the Nether only a map of the roof belongs in it.
        if (SeaFloor.hasCeiling(dimension) && session.view(mapId) != MapLayers.View.ROOF) {
            return "BlueMap's map here shows the inside, which JourneyMap keeps in its cave maps; only the surface can be downloaded"
        }
        val folder = JourneyMapFiles.dayFolder(dimension) ?: return "JourneyMap has no map folder for this world yet"
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val backups = session.folder.resolve("journeymap-backups").resolve(stamp).resolve(SafeFiles.name(dimension))
        val started = Job(session, dimension, mapId, folder, backups, region, unexploredOnly, player.blockX, player.blockZ)
        // Where these backups go back to, so they are never put into another world's map.
        Files.createDirectories(backups)
        SafeFiles.writeString(backups.resolve(ORIGIN), folder.toAbsolutePath().toString())
        job = started
        lastBackups = backups
        Thread({ find(started) }, "SkysMapExposer JourneyMap download").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        Log.info("Download from BlueMap map '{}' into JourneyMap's {} started: {}{}", mapId, dimension,
            region?.let { "region ${it.first}, ${it.second}" } ?: "everything BlueMap has", if (unexploredOnly) " (unexplored only)" else "")
        return "Downloading from BlueMap into JourneyMap's map…"
    }

    fun cancel(): Boolean {
        val running = job ?: return false
        running.cancelled = true
        return true
    }

    fun status(): String? {
        val running = job ?: return waiting.size.takeIf { it > 0 }?.let { "$it region(s) waiting for JourneyMap to let go of them" }
        return if (running.finding) "Asking BlueMap what it has…"
        else "Downloading BlueMap into JourneyMap: ${running.looked.get()} of ${running.total} regions, ${running.written} written"
    }

    /** Leaving a world: the download stops and what was waiting is dropped. */
    fun forget() {
        job?.cancelled = true
        job = null
        if (waiting.isNotEmpty()) Log.info("{} region(s) for JourneyMap's map were still open in JourneyMap and were not written", waiting.size)
        waiting.clear()
    }

    // ---- off the client thread: what to write ----

    private fun find(job: Job) {
        try {
            val blueMap = job.session.blueMap
            val layout = blueMap.layout(job.mapId)
            val size = layout.tileSize
            val finest: Set<Pair<Int, Int>>
            val regions: List<Pair<Int, Int>>
            if (job.region != null) {
                val (rx, rz) = job.region
                val x = rx * JourneyMapFiles.REGION
                val z = rz * JourneyMapFiles.REGION
                finest = BlueMapPicture.tilesOver(x, z, x + JourneyMapFiles.REGION - 1, z + JourneyMapFiles.REGION - 1, size).toSet()
                regions = listOf(job.region)
            } else {
                // BlueMap's coarsest level is a few tiles for a whole world, and says where the finest ones have anything.
                val scan = CoarseScan.scan(layout, job.centreX, job.centreZ, { job.cancelled }) { tx, tz -> blueMap.tile(job.mapId, layout.lodCount, tx, tz) } ?: return
                finest = scan.finest
                val found = HashSet<Pair<Int, Int>>()
                for ((tx, tz) in finest) found.addAll(BlueMapPicture.regionsOver(tx * size, tz * size, tx * size + size - 1, tz * size + size - 1))
                regions = found.sortedBy { (rx, rz) ->
                    val dx = rx * JourneyMapFiles.REGION + 256L - job.centreX
                    val dz = rz * JourneyMapFiles.REGION + 256L - job.centreZ
                    dx * dx + dz * dz
                }
            }
            job.total = regions.size
            job.finding = false
            // BlueMap's tiles, decoded once while neighbouring regions still need them.
            val tiles = object : LinkedHashMap<Pair<Int, Int>, IntArray?>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Int, Int>, IntArray?>) = size > KEEP_TILES
            }
            for ((rx, rz) in regions) {
                if (job.cancelled) break
                val picture = IntArray(JourneyMapFiles.REGION * JourneyMapFiles.REGION)
                var any = false
                val originX = rx * JourneyMapFiles.REGION
                val originZ = rz * JourneyMapFiles.REGION
                for ((tx, tz) in BlueMapPicture.tilesOver(originX, originZ, originX + JourneyMapFiles.REGION - 1, originZ + JourneyMapFiles.REGION - 1, size)) {
                    if (tx to tz !in finest) continue
                    val pixels = tiles.getOrPut(tx to tz) { blueMap.tile(job.mapId, 1, tx, tz)?.let { TilePixels.decode(it, size, 1) } } ?: continue
                    val width = size + 1
                    for (z in 0 until size) for (x in 0 until size) {
                        val colour = pixels[z * width + x]
                        if (colour ushr 24 == 0) continue
                        val px = tx * size + x - originX
                        val pz = tz * size + z - originZ
                        if (px !in 0 until JourneyMapFiles.REGION || pz !in 0 until JourneyMapFiles.REGION) continue
                        picture[pz * JourneyMapFiles.REGION + px] = colour
                        any = true
                    }
                }
                if (!any) { job.looked.incrementAndGet(); continue }
                while (!job.ready.offer(Picture(rx, rz, picture), 200, TimeUnit.MILLISECONDS)) if (job.cancelled) break
            }
        } catch (e: Exception) {
            Log.error("Download from BlueMap into JourneyMap failed", e)
            Minecraft.getInstance().execute { say("The download stopped: ${e.message ?: e.javaClass.simpleName}. The log says more.") }
        } finally {
            job.made = true
            job.finding = false
        }
    }

    // ---- client thread: writing ----

    /** Client thread, every tick: writes one finished region, and retries waiting ones every so often. */
    fun tick() {
        val running = job
        if (running != null) {
            running.ready.poll()?.let { write(running, it) }
            if (running.made && running.ready.isEmpty()) finish(running)
        }
        val now = System.currentTimeMillis()
        val owner = waitingFor
        if (waiting.isNotEmpty() && owner != null && now - retriedAt > RETRY_MS) {
            retriedAt = now
            waiting.values.toList().forEach { if (!JourneyMapFiles.held(owner.folder.parent, it.regionX, it.regionZ)) { waiting.remove(it.regionX to it.regionZ); merge(owner, it) } }
        }
    }

    private fun write(job: Job, picture: Picture) {
        job.looked.incrementAndGet()
        if (JourneyMapFiles.held(job.folder.parent, picture.regionX, picture.regionZ)) {
            if (waiting.size < MAX_WAITING) {
                waiting[picture.regionX to picture.regionZ] = picture
                waitingFor = job
            }
            return
        }
        merge(job, picture)
    }

    /** Puts [picture] into the region's file, after a backup; with unexplored only, just where it has nothing. */
    private fun merge(job: Job, picture: Picture) {
        val file = JourneyMapFiles.regionFile(job.folder, picture.regionX, picture.regionZ)
        try {
            val pixels = JourneyMapFiles.read(file) ?: return run { job.unreadable++ }
            var changed = false
            for (i in pixels.indices) {
                val colour = picture.pixels[i]
                if (colour ushr 24 == 0 || pixels[i] == colour) continue
                if (job.unexploredOnly && pixels[i] ushr 24 != 0) continue
                pixels[i] = colour
                changed = true
            }
            if (!changed) return
            JourneyMapFiles.backUp(file, job.backups.resolve(file.fileName))
            JourneyMapFiles.write(file, pixels)
            job.written++
        } catch (e: Exception) {
            Log.warn("Could not write JourneyMap's region {}, {}: {}", picture.regionX, picture.regionZ, e.toString())
        }
    }

    private fun finish(job: Job) {
        this.job = null
        val parts = listOfNotNull(
            "${job.written} region(s) written",
            "${waiting.size} open in JourneyMap, written once it lets go".takeIf { waiting.isNotEmpty() },
            "${job.unreadable} left alone (not a picture this can read)".takeIf { job.unreadable > 0 },
        )
        val head = if (job.cancelled) "Download into JourneyMap's map stopped: " else "Download into JourneyMap's map done: "
        val message = Component.literal(head + parts.joinToString("; ") + ". Zoom out and back in to see it.  ")
        if (job.written > 0) message.append(ChatButton.of("[Undo download]", "/mapexposer download undo", "Puts the regions back as they were before this download."))
        Minecraft.getInstance().player?.sendSystemMessage(message)
    }

    /**
     * Puts the regions of the last download back, from its backups, into the map they came from
     * (refused if that is not this world's). Regions JourneyMap holds are left for another try.
     */
    fun undo(): String {
        if (job != null) return "Wait for the download to finish, or cancel it first"
        val backups = lastBackups ?: return "No download into JourneyMap's map to undo"
        val origin = runCatching { Files.readString(backups.resolve(ORIGIN)).trim() }.getOrNull() ?: return "That download's backups are gone"
        val dimension = Minecraft.getInstance().level?.dimension()?.identifier()?.toString() ?: return "Not in a world"
        val folder = JourneyMapFiles.dayFolder(dimension)
        if (folder == null || folder.toAbsolutePath().toString() != origin) return "That download was into another world or dimension; go back there to undo it"
        var restored = 0
        var held = 0
        Files.list(backups).use { files ->
            for (backup in files.toList()) {
                val name = backup.fileName.toString().removeSuffix(".none")
                val match = REGION_FILE.matchEntire(name) ?: continue
                val (rx, rz) = match.destructured
                if (JourneyMapFiles.held(folder.parent, rx.toInt(), rz.toInt())) { held++; continue }
                if (backup.fileName.toString() == name || !Files.exists(backups.resolve(name))) {
                    if (JourneyMapFiles.restore(JourneyMapFiles.regionFile(folder, rx.toInt(), rz.toInt()), backups.resolve(name))) restored++
                }
            }
        }
        return "Put back $restored region(s) of JourneyMap's map" +
            (if (held > 0) "; $held are open in JourneyMap, so move away from them and undo again" else "") + ". Zoom out and back in to see it."
    }

    private fun say(message: String) = Minecraft.getInstance().player?.sendSystemMessage(Component.literal(message))

    private val REGION_FILE = Regex("""(-?\d{1,7}),(-?\d{1,7})\.png""")
    private const val ORIGIN = "origin.txt"
    private const val QUEUE = 4
    private const val KEEP_TILES = 8
    private const val MAX_WAITING = 32
    private const val RETRY_MS = 2_000L
}
