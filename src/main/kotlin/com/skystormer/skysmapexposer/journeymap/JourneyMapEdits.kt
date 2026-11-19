package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.BlueMapPicture
import com.skystormer.skysmapexposer.ChatButton
import com.skystormer.skysmapexposer.Clock
import com.skystormer.skysmapexposer.CoarseScan
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapExposerClient
import com.skystormer.skysmapexposer.ShareFormat
import com.skystormer.skysmapexposer.SharedTerrain
import com.skystormer.skysmapexposer.MapBorder
import com.skystormer.skysmapexposer.MapLayers
import com.skystormer.skysmapexposer.SafeFiles
import com.skystormer.skysmapexposer.SeaFloor
import com.skystormer.skysmapexposer.Seasons
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
 * Changing JourneyMap's own map ([JourneyMapFiles]), as Download and Clear change Xaero's, in the
 * dimension you are in (the map JourneyMap keeps for this world):
 *  - download ([download]): BlueMap's shaded colours (what BlueMap's terrain shows on JourneyMap)
 *    into JourneyMap's day map, for a region or everything, only where it has nothing or over it;
 *  - clear ([clearPreview], [clear]): chunks from before the season began, or wholly past the world
 *    border, taken off it;
 *  - a shared map file ([addShared]), coloured as JourneyMap colours its own ([JourneyMapShared]).
 *
 * Both run the same way. Regions are found off the client thread and changed on it, one a tick.
 * Each is copied aside into this edit's own backup folder before it changes, and [undo] puts the
 * last edit back. A region JourneyMap holds in memory is not written (it would write its own over
 * it): a few wait until it lets go, and are dropped if you leave first.
 *
 * Only the day map: the night, topo and cave maps are JourneyMap's own to make as you explore.
 */
internal object JourneyMapEdits {

    /** One region to change: [apply] changes its pixels in place, true if it changed any. Client thread. */
    private class Edit(val regionX: Int, val regionZ: Int, val apply: (IntArray, Path) -> Boolean)

    private class Job(
        val name: String, val session: Session?, val dimension: String, val folder: Path, val backups: Path,
        val centreX: Int, val centreZ: Int,
    ) {
        /** "Download of JourneyMap's map", "Adding Sam's map to JourneyMap's map", for messages. */
        val title = if (name.startsWith("Adding")) "$name to JourneyMap's map" else "$name of JourneyMap's map"
        @Volatile var cancelled = false
        @Volatile var total = 0
        @Volatile var finding = true
        @Volatile var found = false
        val looked = AtomicInteger()
        var changed = 0
        var unreadable = 0
        val ready = LinkedBlockingQueue<Edit>(QUEUE)
    }

    private var job: Job? = null

    /** Edits for regions JourneyMap held when their turn came, by region. */
    private val waiting = LinkedHashMap<Pair<Int, Int>, Edit>()
    private var waitingFor: Job? = null
    private var retriedAt = 0L

    /** The last edit's backup folder, for [undo]. */
    private var lastBackups: Path? = null

    val running: Boolean get() = job != null

    // ---- download ----

    /** Downloads BlueMap into the dimension you are in; [area] (chunks, inclusive) for those chunks only. What to tell you. */
    fun download(unexploredOnly: Boolean, area: ShareFormat.Area? = null): String {
        val session = Session.current ?: return "This server has no BlueMap set up"
        val dimension = here() ?: return "Not in a world"
        val mapId = session.server.markersFor(dimension)
            ?: return "No BlueMap map is set for the ${Ui.dimensionName(dimension)}; add one in the settings"
        // The day map is the surface; in the Nether only a map of the roof belongs in it.
        if (SeaFloor.hasCeiling(dimension) && session.view(mapId) != MapLayers.View.ROOF) {
            return "BlueMap's map here shows the inside, which JourneyMap keeps in its cave maps; only the surface can be downloaded"
        }
        val job = begin("Download", session, dimension) ?: return busyOrMissing()
        Thread({ findDownload(job, mapId, area, unexploredOnly) }, "SkysMapExposer JourneyMap download").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        Log.info("Download from BlueMap map '{}' into JourneyMap's {} started: {}{}", mapId, dimension,
            area?.let { "blocks ${it.left * 16}, ${it.top * 16} to ${it.right * 16 + 15}, ${it.bottom * 16 + 15}" } ?: "everything BlueMap has", if (unexploredOnly) " (unexplored only)" else "")
        return "Downloading from BlueMap into JourneyMap's map…"
    }

    private fun findDownload(job: Job, mapId: String, area: ShareFormat.Area?, unexploredOnly: Boolean) = finding(job) {
        val blueMap = job.session!!.blueMap
        val layout = blueMap.layout(mapId)
        val size = layout.tileSize
        val finest: Set<Pair<Int, Int>>
        val regions: List<Pair<Int, Int>>
        // The blocks wanted, left, top, right, bottom; everything when no area was picked.
        val blocks = area?.let { intArrayOf(it.left * 16, it.top * 16, it.right * 16 + 15, it.bottom * 16 + 15) }
        if (blocks != null) {
            finest = BlueMapPicture.tilesOver(blocks[0], blocks[1], blocks[2], blocks[3], size).toSet()
            regions = BlueMapPicture.regionsOver(blocks[0], blocks[1], blocks[2], blocks[3]).toList()
        } else {
            // BlueMap's coarsest level is a few tiles for a whole world, and says where the finest ones have anything.
            val scan = CoarseScan.scan(layout, job.centreX, job.centreZ, { job.cancelled }) { tx, tz -> blueMap.tile(mapId, layout.lodCount, tx, tz) } ?: return@finding
            finest = scan.finest
            val found = HashSet<Pair<Int, Int>>()
            for ((tx, tz) in finest) found.addAll(BlueMapPicture.regionsOver(tx * size, tz * size, tx * size + size - 1, tz * size + size - 1))
            regions = nearestFirst(job, found)
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
                val pixels = tiles.getOrPut(tx to tz) { blueMap.tile(mapId, 1, tx, tz)?.let { TilePixels.decode(it, size, 1) } } ?: continue
                val width = size + 1
                for (z in 0 until size) for (x in 0 until size) {
                    val colour = pixels[z * width + x]
                    if (colour ushr 24 == 0) continue
                    val px = tx * size + x - originX
                    val pz = tz * size + z - originZ
                    if (px !in 0 until JourneyMapFiles.REGION || pz !in 0 until JourneyMapFiles.REGION) continue
                    if (blocks != null && (originX + px !in blocks[0]..blocks[2] || originZ + pz !in blocks[1]..blocks[3])) continue
                    picture[pz * JourneyMapFiles.REGION + px] = colour
                    any = true
                }
            }
            if (!any) { job.looked.incrementAndGet(); continue }
            queue(job, Edit(rx, rz) { pixels, _ ->
                var changed = false
                for (i in pixels.indices) {
                    val colour = picture[i]
                    if (colour ushr 24 == 0 || pixels[i] == colour) continue
                    if (unexploredOnly && pixels[i] ushr 24 != 0) continue
                    pixels[i] = colour
                    changed = true
                }
                changed
            })
        }
    }

    // ---- a shared map file ----

    /**
     * Adds shared map file [file] to JourneyMap's day map of its dimension; with [unexploredOnly],
     * only where JourneyMap has nothing. What to tell you.
     */
    fun addShared(file: SharedTerrain.Opened, unexploredOnly: Boolean): String {
        val name = Ui.dimensionName(file.dimensionId) ?: file.dimensionId
        // JourneyMap's day map is the surface; Xaero's cave layers have no place in it.
        if (file.layer != MapLayers.SURFACE) {
            return "This map is of ${SharedTerrain.layerName(file.layer)} of the $name; JourneyMap's day map is the surface, so only Xaero's map can take it"
        }
        val job = begin("Adding ${file.from}'s map", Session.current, file.dimensionId) ?: return busyOrMissing()
        val biomes = JourneyMapImportedBiomes.folder(file.dimensionId)
        Thread({ findShared(job, file, unexploredOnly, biomes) }, "SkysMapExposer JourneyMap shared map").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        Log.info("Adding {} ({} regions of the {}) to JourneyMap's map{}", file.path.fileName, file.entries.size, file.dimensionId, if (unexploredOnly) " (unexplored only)" else "")
        return "Adding ${file.from}'s map to JourneyMap's map of the $name…"
    }

    private fun findShared(job: Job, file: SharedTerrain.Opened, unexploredOnly: Boolean, biomes: Path?) = finding(job) {
        val entries = file.entries.associateBy { it.regionX to it.regionZ }
        job.total = entries.size
        job.finding = false
        for ((rx, rz) in nearestFirst(job, entries.keys)) {
            if (job.cancelled) break
            val picture = try {
                val region = SharedTerrain.region(file, entries.getValue(rx to rz))
                // Its biomes too, which JourneyMap's pictures have no room for ([JourneyMapImportedBiomes]).
                biomes?.let { folder ->
                    try {
                        JourneyMapImportedBiomes.add(folder, rx, rz, region)
                    } catch (e: Exception) {
                        Log.warn("Could not keep the biomes of region {}, {} of {}: {}", rx, rz, file.path.fileName, e.toString())
                    }
                }
                JourneyMapShared.picture(region)
            } catch (e: Exception) {
                Log.error("Could not read region ${rx}_${rz} of ${file.path}; left it out", e)
                job.looked.incrementAndGet()
                job.unreadable++
                continue
            }
            queue(job, Edit(rx, rz) { pixels, _ -> merge(pixels, picture, unexploredOnly) })
        }
    }

    /** Puts [picture]'s pixels into [pixels], over your map or only where it has nothing; true if any changed. */
    private fun merge(pixels: IntArray, picture: IntArray, unexploredOnly: Boolean): Boolean {
        var changed = false
        for (i in pixels.indices) {
            val colour = picture[i]
            if (colour ushr 24 == 0 || pixels[i] == colour) continue
            if (unexploredOnly && pixels[i] ushr 24 != 0) continue
            pixels[i] = colour
            changed = true
        }
        return changed
    }

    // ---- clear ----

    enum class Clear(val word: String) { OLD_SEASON("oldseason"), BORDER("border") }

    /** What clearing [kind] would take off JourneyMap's map, with a button to go ahead; or why it can't. */
    fun clearPreview(kind: Clear, box: MapBorder.Box?): Component {
        val (rule, why) = clearRule(kind, box)
        rule ?: return Component.literal(why!!)
        val command = "/mapexposer clear ${kind.word}${box?.let { " " + it.words } ?: ""} confirm"
        return Component.literal("Clear ${rule.what} from JourneyMap's map? Each region is copied aside first, and /mapexposer undo puts it back.  ")
            .append(ChatButton.of("[Clear]", command, "Takes it off JourneyMap's map now."))
    }

    fun clear(kind: Clear, box: MapBorder.Box?): String {
        val (rule, why) = clearRule(kind, box)
        rule ?: return why!!
        val session = Session.current ?: return "This server has no BlueMap set up"
        val dimension = here() ?: return "Not in a world"
        val job = begin("Clear", session, dimension) ?: return busyOrMissing()
        Thread({ findClear(job, rule) }, "SkysMapExposer JourneyMap clear").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        return "Clearing ${rule.what} from JourneyMap's map…"
    }

    /**
     * Which chunks go, asked of chunk ([cx], [cz]) of a region file, and which regions may hold any.
     * [what] names it for the messages.
     */
    private class Rule(val what: String, val mayHold: (Int, Int) -> Boolean, val clears: (Int, Int, Path) -> Boolean)

    private fun clearRule(kind: Clear, box: MapBorder.Box?): Pair<Rule?, String?> {
        val dimension = here() ?: return null to "Not in a world"
        val name = Ui.dimensionName(dimension) ?: dimension
        val session = Session.current
        return when (kind) {
            Clear.OLD_SEASON -> {
                val start = session?.server?.coverBefore?.get(dimension)?.let(Clock::minutesOf)
                    ?: return null to "No season start is set for the $name; /mapexposer season set <yyyy-MM-dd HH:mm> sets it"
                // A chunk's age: when you last saw it, else when JourneyMap last wrote its region
                // (it keeps no dates of its own), as Xaero's map falls back on its region files.
                Rule("your $name map from before ${Seasons.format(start)}", { _, _ -> true }) { cx, cz, file ->
                    val seen = session.visits.lastSeen(dimension, cx, cz).takeIf { it != 0 }
                        ?: runCatching { Clock.minutesOf(Files.getLastModifiedTime(file).toMillis()) }.getOrDefault(Int.MAX_VALUE)
                    seen < start
                } to null
            }
            Clear.BORDER -> {
                val (shape, why) = MapBorder.border(box, dimension, name)
                shape ?: return null to why
                val r = JourneyMapFiles.REGION.toDouble()
                Rule(shape.what + " (border: ${shape.from})", { x, z -> !shape.inside(x * r, z * r, x * r + r, z * r + r) }) { cx, cz, _ ->
                    shape.beyond(cx * 16.0, cz * 16.0, cx * 16.0 + 16, cz * 16.0 + 16)
                } to null
            }
        }
    }

    private fun findClear(job: Job, rule: Rule) = finding(job) {
        val regions = Files.list(job.folder).use { files ->
            files.toList().mapNotNull { REGION_FILE.matchEntire(it.fileName.toString())?.destructured?.let { (x, z) -> x.toInt() to z.toInt() } }
        }.filter { (x, z) -> rule.mayHold(x, z) }
        job.total = regions.size
        job.finding = false
        for ((rx, rz) in nearestFirst(job, regions)) {
            if (job.cancelled) break
            queue(job, Edit(rx, rz) { pixels, file ->
                var changed = false
                for (chunkZ in 0 until CHUNKS) for (chunkX in 0 until CHUNKS) {
                    if (!rule.clears(rx * CHUNKS + chunkX, rz * CHUNKS + chunkZ, file)) continue
                    for (z in chunkZ * 16 until chunkZ * 16 + 16) for (x in chunkX * 16 until chunkX * 16 + 16) {
                        val i = z * JourneyMapFiles.REGION + x
                        if (pixels[i] != 0) { pixels[i] = 0; changed = true }
                    }
                }
                changed
            })
        }
    }

    // ---- running an edit ----

    private fun begin(name: String, session: Session?, dimension: String): Job? {
        if (job != null) return null
        val folder = JourneyMapFiles.dayFolder(dimension) ?: return null
        val player = Minecraft.getInstance().player ?: return null
        val root = serverFolder() ?: return null
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val backups = root.resolve("journeymap-backups").resolve(stamp).resolve(SafeFiles.name(dimension))
        Files.createDirectories(backups)
        // Where these backups go back to, so they are never put into another world's map.
        SafeFiles.writeString(backups.resolve(ORIGIN), folder.toAbsolutePath().toString())
        val started = Job(name, session, dimension, folder, backups, player.blockX, player.blockZ)
        job = started
        lastBackups = backups
        return started
    }

    private fun busyOrMissing(): String =
        if (job != null) "Something is already being written into JourneyMap's map" else "JourneyMap has no map folder for this world yet"

    private inline fun finding(job: Job, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.error("${job.name} of JourneyMap's map failed", e)
            Minecraft.getInstance().execute { say("The ${job.name.lowercase()} stopped: ${e.message ?: e.javaClass.simpleName}. The log says more.") }
        } finally {
            job.found = true
            job.finding = false
        }
    }

    private fun queue(job: Job, edit: Edit) {
        while (!job.ready.offer(edit, 200, TimeUnit.MILLISECONDS)) if (job.cancelled) return
    }

    private fun nearestFirst(job: Job, regions: Collection<Pair<Int, Int>>) = regions.sortedBy { (rx, rz) ->
        val dx = rx * JourneyMapFiles.REGION + 256L - job.centreX
        val dz = rz * JourneyMapFiles.REGION + 256L - job.centreZ
        dx * dx + dz * dz
    }

    fun cancel(): Boolean {
        val running = job ?: return false
        running.cancelled = true
        return true
    }

    fun status(): String? {
        val running = job ?: return waiting.size.takeIf { it > 0 }?.let { "$it region(s) waiting for JourneyMap to let go of them" }
        return if (running.finding) "${running.name}: finding the regions…"
        else "${running.title}: ${running.looked.get()} of ${running.total} regions, ${running.changed} changed"
    }

    /** Leaving a world: the edit stops and what was waiting is dropped. */
    fun forget() {
        job?.cancelled = true
        job = null
        if (waiting.isNotEmpty()) Log.info("{} region(s) of JourneyMap's map were still open in JourneyMap and were not changed", waiting.size)
        waiting.clear()
    }

    /** Client thread, every tick: changes one region, and retries waiting ones every so often. */
    fun tick() {
        val running = job
        if (running != null) {
            running.ready.poll()?.let { change(running, it) }
            if (running.found && running.ready.isEmpty()) finish(running)
        }
        val now = System.currentTimeMillis()
        val owner = waitingFor
        if (waiting.isNotEmpty() && owner != null && now - retriedAt > RETRY_MS) {
            retriedAt = now
            waiting.values.toList().forEach {
                if (!JourneyMapFiles.held(owner.folder.parent, it.regionX, it.regionZ)) {
                    waiting.remove(it.regionX to it.regionZ)
                    write(owner, it)
                }
            }
        }
    }

    private fun change(job: Job, edit: Edit) {
        job.looked.incrementAndGet()
        if (JourneyMapFiles.held(job.folder.parent, edit.regionX, edit.regionZ)) {
            // Open in JourneyMap: changed in its memory, shown at once and saved by it, as Xaero's
            // regions are refreshed while its map is open. Waits only if this JourneyMap will not take it.
            if (writeHeld(job, edit)) return
            if (waiting.size < MAX_WAITING) {
                waiting[edit.regionX to edit.regionZ] = edit
                waitingFor = job
            }
            return
        }
        write(job, edit)
    }

    /** Changes the region's file, after a backup, if [Edit.apply] changes anything. */
    private fun write(job: Job, edit: Edit) {
        val file = JourneyMapFiles.regionFile(job.folder, edit.regionX, edit.regionZ)
        try {
            val pixels = JourneyMapFiles.read(file) ?: return run { job.unreadable++ }
            if (!edit.apply(pixels, file)) return
            JourneyMapFiles.backUp(file, job.backups.resolve(file.fileName))
            JourneyMapFiles.write(file, pixels)
            job.changed++
            JourneyMapTerrain.changed(job.dimension, edit.regionX, edit.regionZ)
        } catch (e: Exception) {
            Log.warn("Could not change JourneyMap's region {}, {}: {}", edit.regionX, edit.regionZ, e.toString())
        }
    }

    /** Changes a region JourneyMap holds, in its memory, after a backup of it as held; false if that cannot be done here. */
    private fun writeHeld(job: Job, edit: Edit): Boolean {
        // Held, but not its day picture: nothing of JourneyMap's would be written over the file.
        val holder = JourneyMapFiles.heldDay(job.folder.parent, job.dimension, edit.regionX, edit.regionZ)
            ?: return true.also { write(job, edit) }
        val pixels = JourneyMapFiles.pixelsOf(holder) ?: return false
        val before = pixels.copyOf()
        val file = JourneyMapFiles.regionFile(job.folder, edit.regionX, edit.regionZ)
        return try {
            if (!edit.apply(pixels, file)) return true
            JourneyMapFiles.backUpHeld(before, job.backups.resolve(file.fileName))
            if (!JourneyMapFiles.replaceHeld(holder, pixels)) return false
            job.changed++
            JourneyMapTerrain.changed(job.dimension, edit.regionX, edit.regionZ)
            true
        } catch (e: Exception) {
            Log.warn("Could not change JourneyMap's region {}, {}: {}", edit.regionX, edit.regionZ, e.toString())
            true
        }
    }

    private fun finish(job: Job) {
        this.job = null
        val parts = listOfNotNull(
            "${job.changed} region(s) changed",
            "${waiting.size} open in JourneyMap, changed once it lets go".takeIf { waiting.isNotEmpty() },
            "${job.unreadable} left alone (could not be read; the log says why)".takeIf { job.unreadable > 0 },
        )
        val head = "${job.title} ${if (job.cancelled) "stopped" else "done"}: "
        val message = Component.literal(head + parts.joinToString("; ") + ".  ")
        if (job.changed > 0) message.append(ChatButton.of("[Undo]", "/mapexposer undo", "Puts the regions back as they were before this."))
        Minecraft.getInstance().player?.sendSystemMessage(message)
    }

    /**
     * Puts the regions of the last download or clear back, from its backups, into the map they came
     * from (refused if that is not this world's). Regions JourneyMap holds are left for another try.
     */
    fun undo(): String {
        if (job != null) return "Wait for what is being written to finish, or cancel it first"
        val dimension = here() ?: return "Not in a world"
        // The last edit this game, or else the newest one kept on disk for this dimension.
        val backups = lastBackups ?: newestBackups(dimension) ?: return "Nothing to undo on JourneyMap's map"
        val origin = runCatching { Files.readString(backups.resolve(ORIGIN)).trim() }.getOrNull() ?: return "Those backups are gone"
        val folder = JourneyMapFiles.dayFolder(dimension)
        if (folder == null || folder.toAbsolutePath().toString() != origin) return "That was in another world or dimension; go back there to undo it"
        var restored = 0
        var held = 0
        Files.list(backups).use { files ->
            for (backup in files.toList()) {
                val name = backup.fileName.toString().removeSuffix(".none")
                val match = REGION_FILE.matchEntire(name) ?: continue
                val (rx, rz) = match.destructured
                if (JourneyMapFiles.held(folder.parent, rx.toInt(), rz.toInt())) {
                    // Open in JourneyMap: put back in its memory, as it was changed.
                    val holder = JourneyMapFiles.heldDay(folder.parent, dimension, rx.toInt(), rz.toInt())
                    val pixels = JourneyMapFiles.backupPixels(backups.resolve(name))
                    if (holder != null && pixels != null && JourneyMapFiles.replaceHeld(holder, pixels)) {
                        restored++
                        JourneyMapTerrain.changed(dimension, rx.toInt(), rz.toInt())
                    } else {
                        held++
                    }
                    continue
                }
                if (JourneyMapFiles.restore(JourneyMapFiles.regionFile(folder, rx.toInt(), rz.toInt()), backups.resolve(name))) restored++
            }
        }
        if (held == 0) lastBackups = null.also { runCatching { Files.delete(backups.resolve(ORIGIN)) } }
        return "Put back $restored region(s) of JourneyMap's map" +
            (if (held > 0) "; $held are open in JourneyMap, so move away from them and undo again" else "") + "."
    }

    /** The newest backup folder of [dimension] on this server, from an earlier game. */
    private fun newestBackups(dimension: String): Path? {
        val root = serverFolder()?.resolve("journeymap-backups") ?: return null
        if (!Files.isDirectory(root)) return null
        return Files.list(root).use { stamps ->
            stamps.toList().sortedByDescending { it.fileName.toString() }
                .map { it.resolve(SafeFiles.name(dimension)) }
                .firstOrNull { Files.exists(it.resolve(ORIGIN)) }
        }
    }

    /** This mod's folder for the server you are on, whether or not it has a BlueMap, where backups go. */
    fun serverFolder(): Path? = Session.current?.folder
        ?: MapExposerClient.addressOf(Minecraft.getInstance())?.let(Session::folderFor)

    private fun here(): String? = Minecraft.getInstance().level?.dimension()?.identifier()?.toString()

    private fun say(message: String) = Minecraft.getInstance().player?.sendSystemMessage(Component.literal(message))

    private val REGION_FILE = Regex("""(-?\d{1,7}),(-?\d{1,7})\.png""")
    private const val CHUNKS = JourneyMapFiles.REGION / 16
    private const val ORIGIN = "origin.txt"
    private const val QUEUE = 4
    private const val KEEP_TILES = 8
    private const val MAX_WAITING = 32
    private const val RETRY_MS = 2_000L
}
