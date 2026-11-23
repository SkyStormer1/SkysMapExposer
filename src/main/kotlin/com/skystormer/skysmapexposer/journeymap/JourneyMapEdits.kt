package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.lruMap
import com.skystormer.skysmapexposer.background
import com.skystormer.skysmapexposer.BiomeGuide
import com.skystormer.skysmapexposer.BlockArea
import com.skystormer.skysmapexposer.BlockGuesser
import com.skystormer.skysmapexposer.BlueMapPicture
import com.skystormer.skysmapexposer.ChatButton
import com.skystormer.skysmapexposer.Clock
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.LowresSource
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapExposerClient
import com.skystormer.skysmapexposer.ShareFormat
import com.skystormer.skysmapexposer.SharedTerrain
import com.skystormer.skysmapexposer.MapBorder
import com.skystormer.skysmapexposer.MapLayers
import com.skystormer.skysmapexposer.RegionJob
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

/**
 * Changing JourneyMap's own map ([JourneyMapFiles]), as Download and Clear change Xaero's, in the
 * dimension you are in (the map JourneyMap keeps for this world):
 *  - download ([download]): BlueMap's shaded colours (what BlueMap's terrain shows on JourneyMap)
 *    into JourneyMap's day map, for a region or everything, only where it has nothing or over it;
 *  - clear ([clearPreview], [clear]): chunks from before the season began, or wholly past the world
 *    border, taken off it;
 *  - a shared map file ([addShared]), coloured as JourneyMap colours its own ([JourneyMapShared]).
 *
 * All run the same way ([RegionJob], as Xaero's do). Regions are found off the client thread and
 * changed on it, one a tick. Each is copied aside into this edit's own backup folder before it
 * changes, and [undo] puts the last edit back. A region JourneyMap holds in memory, and that this
 * JourneyMap won't let be changed there, is not written (it would write its own over it): it waits
 * until JourneyMap lets go, and the edit isn't over until it has, as Xaero's waits for its regions
 * to load and save, so a second edit can never start under it.
 *
 * Only the day map: the night, topo and cave maps are JourneyMap's own to make as you explore.
 */
internal object JourneyMapEdits {

    /**
     * One region to change: [apply] changes its pixels in place, true if it changed any; then [cache]
     * is shown the region's pixels as they now are, to put in JourneyMap's chunk cache what took
     * ([JourneyMapCacheWrites]). Client thread.
     */
    private class Edit(val regionX: Int, val regionZ: Int, val cache: ((IntArray) -> Unit)? = null, val apply: (IntArray, Path) -> Boolean)

    /** [what] is "Download", "Clear" or "Adding Sam's map", which the title is made from. */
    private class Job(val what: String, val session: Session?, dimension: String, val folder: Path, val backups: Path) : RegionJob<Edit>(
        if (what.startsWith("Adding")) "$what to JourneyMap's map" else "$what of JourneyMap's map", dimension, QUEUE,
    ) {
        /** Downloaded and shared columns into JourneyMap's chunk cache, for saving terrain files; none for a clear. */
        var cache: JourneyMapCacheWrites.Batch? = null
        var changed = 0
        /** Edits for regions JourneyMap held when their turn came, by region, and since when. */
        val waiting = LinkedHashMap<Pair<Int, Int>, Pair<Edit, Long>>()
        var retriedAt = 0L
        /** Regions still waiting when it was cancelled, left as they were. */
        var leftOpen = 0
    }

    private var job: Job? = null

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
        val job = begin("Download", session, dimension, keepInCache = true) ?: return busyOrMissing()
        val biomes = JourneyMapImportedBiomes.folder(dimension)
        val registries = Minecraft.getInstance().level!!.registryAccess()
        val blocks = area?.let(BlockArea::ofChunks)
        find(job, "SkysMapExposer JourneyMap download") { findDownload(job, mapId, blocks, unexploredOnly, biomes, registries) }
        Log.info("Download from BlueMap map '{}' into JourneyMap's {} started: {}{}", mapId, dimension,
            blocks?.let { "blocks ${it.minX}, ${it.minZ} to ${it.maxX}, ${it.maxZ}" } ?: "everything BlueMap has", if (unexploredOnly) " (unexplored only)" else "")
        return "Downloading from BlueMap into JourneyMap's map…"
    }

    /** Finds what BlueMap has in [blocks], or everywhere, and hands each region's colours over. Finding thread. */
    private fun findDownload(
        job: Job, mapId: String, blocks: BlockArea?, unexploredOnly: Boolean, biomes: Path?, registries: net.minecraft.core.RegistryAccess,
    ) {
        val blueMap = job.session!!.blueMap
        val found = job.findOnBlueMap(blueMap, mapId, blocks) ?: return
        val size = found.tileSize
        val finest = found.finest
        job.total = found.regions.size
        job.finding = false
        // BlueMap's tiles, fetched and decoded once while neighbouring regions still need them.
        // Each tile's picture read once, for both the shaded colours and the heights.
        val read = lruMap<Pair<Int, Int>, IntArray?>(KEEP_TILES)
        fun source(tx: Int, tz: Int): IntArray? = read.getOrPut(tx to tz) {
            if (tx to tz in finest) blueMap.tile(mapId, 1, tx, tz)?.let { TilePixels.read(it, size) } else null
        }
        val tiles = lruMap<Pair<Int, Int>, IntArray?>(KEEP_TILES)
        // The biomes, guessed from BlueMap's ground by the same guide as a download into Xaero's map
        // ([BiomeGuide]), from the same tiles, and kept for JourneyMap ([JourneyMapImportedBiomes]).
        val guide = if (Config.guessBiomes && biomes != null) BiomeGuide.forDimension(job.dimensionId) else null
        // BlueMap's colours and heights, for the blocks kept in JourneyMap's chunk cache.
        val decoded = lruMap<Pair<Int, Int>, BlueMapPicture.Tile?>(KEEP_TILES)
        fun tile(tx: Int, tz: Int): BlueMapPicture.Tile? = decoded.getOrPut(tx to tz) { source(tx, tz)?.let { BlueMapPicture.decode(tx, tz, size, it) } }
        val ground = guide?.let {
            LowresSource(size, hasSea = job.dimensionId == Config.OVERWORLD) { tx, tz -> tile(tx, tz) }
        }
        // The block for each colour, as JourneyMap would draw it, by the same guesser as a download into Xaero's map.
        val guesser = BlockGuesser(registries, job.dimensionId, JourneyMapShared::baseColour)
        for ((rx, rz) in found.regions) {
            if (job.cancelled) break
            // Unexplored only, and your map of the region already has no gaps: nothing to fetch for it.
            if (unexploredOnly && JourneyMapFiles.read(JourneyMapFiles.regionFile(job.folder, rx, rz))?.none { it ushr 24 == 0 } == true) {
                job.done.incrementAndGet()
                continue
            }
            val picture = IntArray(JourneyMapFiles.REGION * JourneyMapFiles.REGION)
            var any = false
            val originX = rx * JourneyMapFiles.REGION
            val originZ = rz * JourneyMapFiles.REGION
            for ((tx, tz) in BlueMapPicture.tilesOver(originX, originZ, originX + JourneyMapFiles.REGION - 1, originZ + JourneyMapFiles.REGION - 1, size)) {
                if (tx to tz !in finest) continue
                val pixels = tiles.getOrPut(tx to tz) { source(tx, tz)?.let { TilePixels.decode(it, size, 1) } } ?: continue
                val width = size + 1
                for (z in 0 until size) for (x in 0 until size) {
                    val colour = pixels[z * width + x]
                    if (colour ushr 24 == 0) continue
                    val px = tx * size + x - originX
                    val pz = tz * size + z - originZ
                    if (px !in 0 until JourneyMapFiles.REGION || pz !in 0 until JourneyMapFiles.REGION) continue
                    if (blocks != null && !blocks.contains(originX + px, originZ + pz)) continue
                    picture[pz * JourneyMapFiles.REGION + px] = colour
                    any = true
                }
            }
            if (!any) { job.done.incrementAndGet(); continue }
            var guess: BiomeGuide.Guess? = null
            if (guide != null && ground != null && biomes != null) {
                try {
                    guess = guide.guess(ground, rx, rz)
                    JourneyMapImportedBiomes.addGuess(biomes, rx, rz, guess) { px, pz -> picture[pz * JourneyMapFiles.REGION + px] ushr 24 != 0 }
                } catch (e: Exception) {
                    Log.warn("Could not guess the biomes of region {}, {}: {}", rx, rz, e.toString())
                }
            }
            val columns = if (job.cache == null) null else try {
                downloadColumns(BlueMapPicture.region(rx, rz, size, blocks?.minX, blocks?.minZ, blocks?.maxX, blocks?.maxZ, ::tile), guesser, guess)
            } catch (e: Exception) {
                Log.warn("Could not work out the blocks of region {}, {} for JourneyMap's chunk cache: {}", rx, rz, e.toString())
                null
            }
            if (!queue(job, Edit(rx, rz, columns?.let { cacheWhereTaken(job, it, picture) }) { pixels, _ -> merge(pixels, picture, unexploredOnly) })) break
        }
    }

    /**
     * BlueMap's [picture] of a region as columns for JourneyMap's chunk cache: each block BlueMap has
     * as the block [guesser] takes its colour for, at its height, over water as deep as it looks,
     * with the guessed biome, as a download writes Xaero's map.
     */
    private fun downloadColumns(picture: BlueMapPicture.RegionPicture, guesser: BlockGuesser, guess: BiomeGuide.Guess?): SharedTerrain.Region {
        val region = SharedTerrain.Region(picture.regionX, picture.regionZ)
        val pixel = BlockGuesser.Pixel()
        val water = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()
        for (pz in 0 until BlueMapPicture.REGION) for (px in 0 until BlueMapPicture.REGION) {
            val i = pz * BlueMapPicture.REGION + px
            val colour = picture.colour[i]
            if (colour == 0) continue
            val height = picture.height[i].toInt()
            guesser.decide(colour, height, guess?.at(px, pz)?.let(guesser::biomeKey), pixel)
            val index = (pz shr 4) * 32 + (px shr 4)
            val chunk = region.chunks[index] ?: SharedTerrain.SharedChunk().also { region.chunks[index] = it }
            val c = (px and 15) * 16 + (pz and 15)
            val light = picture.light[i]
            chunk.state[c] = pixel.state
            chunk.biome[c] = pixel.biome
            chunk.light[c] = light
            chunk.top[c] = height.toShort()
            if (pixel.water) {
                chunk.height[c] = (height - pixel.depth).toShort()
                chunk.overlays[c] = listOf(SharedTerrain.SharedOverlay(water, light, false, pixel.depth))
            } else {
                chunk.height[c] = height.toShort()
                chunk.glowing[c] = pixel.state.lightEmission > 0
            }
        }
        return region
    }

    /**
     * For an [Edit]: once its region's pixels are as they will stay, [columns] into JourneyMap's chunk
     * cache wherever the picture took this edit's colour ([picture]), so the cache shows what the map
     * shows; not where your own map stayed, or where nothing was put.
     */
    private fun cacheWhereTaken(job: Job, columns: SharedTerrain.Region, picture: IntArray): ((IntArray) -> Unit)? {
        val batch = job.cache ?: return null
        return { pixels ->
            val taken = java.util.BitSet(pixels.size)
            for (i in pixels.indices) if (picture[i] ushr 24 != 0 && pixels[i] == picture[i]) taken.set(i)
            if (!taken.isEmpty) batch.add(columns) { index, column ->
                val px = (index and 31) * 16 + (column shr 4)
                val pz = (index shr 5) * 16 + (column and 15)
                taken.get(pz * JourneyMapFiles.REGION + px)
            }
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
        val job = begin("Adding ${file.from}'s map", Session.current, file.dimensionId, keepInCache = true) ?: return busyOrMissing()
        val biomes = JourneyMapImportedBiomes.folder(file.dimensionId)
        find(job, "SkysMapExposer JourneyMap shared map") { findShared(job, file, unexploredOnly, biomes) }
        Log.info("Adding {} ({} regions of the {}) to JourneyMap's map{}", file.path.fileName, file.entries.size, file.dimensionId, if (unexploredOnly) " (unexplored only)" else "")
        return "Adding ${file.from}'s map to JourneyMap's map of the $name…"
    }

    private fun findShared(job: Job, file: SharedTerrain.Opened, unexploredOnly: Boolean, biomes: Path?) {
        val entries = file.entries.associateBy { it.regionX to it.regionZ }
        job.total = entries.size
        job.finding = false
        for ((rx, rz) in job.nearestFirst(entries.keys)) {
            if (job.cancelled) break
            val (region, picture) = try {
                val region = SharedTerrain.region(file, entries.getValue(rx to rz))
                // Its biomes too, which JourneyMap's pictures have no room for ([JourneyMapImportedBiomes]).
                biomes?.let { folder ->
                    try {
                        JourneyMapImportedBiomes.add(folder, rx, rz, region)
                    } catch (e: Exception) {
                        Log.warn("Could not keep the biomes of region {}, {} of {}: {}", rx, rz, file.path.fileName, e.toString())
                    }
                }
                region to JourneyMapShared.picture(region)
            } catch (e: Exception) {
                Log.error("Could not read region ${rx}_${rz} of ${file.path}; left it out", e)
                job.done.incrementAndGet()
                job.skipped.add(rx, rz, "couldn't read it from the file")
                continue
            }
            if (!queue(job, Edit(rx, rz, cacheWhereTaken(job, region, picture)) { pixels, _ -> merge(pixels, picture, unexploredOnly) })) break
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
        find(job, "SkysMapExposer JourneyMap clear") { findClear(job, rule) }
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

    private fun findClear(job: Job, rule: Rule) {
        val regions = Files.list(job.folder).use { files ->
            files.toList().mapNotNull { REGION_FILE.matchEntire(it.fileName.toString())?.destructured?.let { (x, z) -> x.toInt() to z.toInt() } }
        }.filter { (x, z) -> rule.mayHold(x, z) }
        job.total = regions.size
        job.finding = false
        for ((rx, rz) in job.nearestFirst(regions)) {
            if (job.cancelled) break
            val handed = queue(job, Edit(rx, rz) { pixels, file ->
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
            if (!handed) break
        }
    }

    // ---- running an edit ----

    private fun begin(name: String, session: Session?, dimension: String, keepInCache: Boolean = false): Job? {
        if (job != null) return null
        val folder = JourneyMapFiles.dayFolder(dimension) ?: return null
        Minecraft.getInstance().player ?: return null
        val root = serverFolder() ?: return null
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"))
        val backups = root.resolve("journeymap-backups").resolve(stamp).resolve(SafeFiles.name(dimension))
        Files.createDirectories(backups)
        // Where these backups go back to, so they are never put into another world's map.
        SafeFiles.writeString(backups.resolve(ORIGIN), folder.toAbsolutePath().toString())
        val started = Job(name, session, dimension, folder, backups)
        if (keepInCache && JourneyMapCacheWrites.working) started.cache = JourneyMapCacheWrites.Batch(dimension, JourneyMapCacheWrites.ledgerIn(backups))
        job = started
        lastBackups = backups
        return started
    }

    private fun busyOrMissing(): String =
        if (job != null) "Something is already being written into JourneyMap's map" else "JourneyMap has no map folder for this world yet"

    private fun find(job: Job, thread: String, find: () -> Unit) =
        job.findOff(thread, failed = { why -> Minecraft.getInstance().execute { say(why) } }, find = find)

    /** Hands [edit] to the client thread; false once cancelled. */
    private fun queue(job: Job, edit: Edit): Boolean {
        // The cache's writing keeps up, or the finding waits for it: a region's columns take memory.
        while (!job.cancelled && (job.cache?.pending ?: 0) > MAX_CACHE_BEHIND) Thread.sleep(50)
        return job.hand(edit)
    }

    fun cancel(): Boolean {
        val running = job ?: return false
        running.cancelled = true
        return true
    }

    fun status(): String? {
        val running = job ?: return null
        val waitingNote = if (running.waiting.isNotEmpty()) ", ${running.waiting.size} waiting for JourneyMap to let go of them" else ""
        return if (running.finding) "${running.what}: finding the regions…"
        else "${running.title}: ${running.done.get()} of ${running.total} regions, ${running.changed} changed$waitingNote"
    }

    /** Leaving a world: the edit stops and what was waiting is dropped. */
    fun forget() {
        val running = job ?: return
        running.cancelled = true
        job = null
        RegionJob.last = null
        if (running.waiting.isNotEmpty()) Log.info("{} region(s) of JourneyMap's map were still open in JourneyMap and were not changed", running.waiting.size)
    }

    /** Client thread, every tick: changes one region, and retries waiting ones every so often. */
    fun tick() {
        val running = job ?: return
        running.ready.poll()?.let { change(running, it) }
        val now = System.currentTimeMillis()
        if (running.cancelled) {
            running.leftOpen += running.waiting.size
            running.waiting.clear()
        } else if (running.waiting.isNotEmpty() && now - running.retriedAt > RETRY_MS) {
            running.retriedAt = now
            for ((at, waited) in running.waiting.entries.toList()) {
                val (edit, since) = waited
                if (!JourneyMapFiles.held(running.folder.parent, edit.regionX, edit.regionZ)) {
                    running.waiting.remove(at)
                    write(running, edit)
                } else if (now - since > WAIT_MS) {
                    running.waiting.remove(at)
                    running.skipped.add(edit.regionX, edit.regionZ, KEPT_OPEN)
                }
            }
        }
        if (running.found && running.ready.isEmpty() && running.waiting.isEmpty()) finish(running)
    }

    private fun change(job: Job, edit: Edit) {
        job.done.incrementAndGet()
        if (JourneyMapFiles.held(job.folder.parent, edit.regionX, edit.regionZ)) {
            // Open in JourneyMap: changed in its memory, shown at once and saved by it, as Xaero's
            // regions are refreshed while its map is open. Waits only if this JourneyMap will not take it.
            if (writeHeld(job, edit)) return
            // Each waiting edit holds a region's pixels; past a few, the rest are named as skipped.
            if (job.waiting.size < MAX_WAITING) job.waiting[edit.regionX to edit.regionZ] = edit to System.currentTimeMillis()
            else job.skipped.add(edit.regionX, edit.regionZ, KEPT_OPEN)
            return
        }
        write(job, edit)
    }

    /** Changes the region's file, after a backup, if [Edit.apply] changes anything. */
    private fun write(job: Job, edit: Edit) {
        val file = JourneyMapFiles.regionFile(job.folder, edit.regionX, edit.regionZ)
        try {
            val pixels = JourneyMapFiles.read(file) ?: return run { job.skipped.add(edit.regionX, edit.regionZ, UNREADABLE) }
            val changed = edit.apply(pixels, file)
            edit.cache?.invoke(pixels)
            if (!changed) return
            JourneyMapFiles.backUp(file, job.backups.resolve(file.fileName))
            JourneyMapFiles.write(file, pixels)
            JourneyMapFiles.written(job.dimensionId, job.folder, edit.regionX, edit.regionZ, exists = true)
            job.changed++
            JourneyMapTerrain.changed(job.dimensionId, edit.regionX, edit.regionZ)
        } catch (e: Exception) {
            Log.warn("Could not change JourneyMap's region {}, {}: {}", edit.regionX, edit.regionZ, e.toString())
        }
    }

    /** Changes a region JourneyMap holds, in its memory, after a backup of it as held; false if that cannot be done here. */
    private fun writeHeld(job: Job, edit: Edit): Boolean {
        // Held, but not its day picture: nothing of JourneyMap's would be written over the file.
        val holder = JourneyMapFiles.heldDay(job.folder.parent, job.dimensionId, edit.regionX, edit.regionZ)
            ?: return true.also { write(job, edit) }
        val pixels = JourneyMapFiles.pixelsOf(holder) ?: return false
        val before = pixels.copyOf()
        val file = JourneyMapFiles.regionFile(job.folder, edit.regionX, edit.regionZ)
        return try {
            val changed = edit.apply(pixels, file)
            edit.cache?.invoke(pixels)
            if (!changed) return true
            JourneyMapFiles.backUpHeld(before, job.backups.resolve(file.fileName))
            if (!JourneyMapFiles.replaceHeld(holder, pixels)) return false
            job.changed++
            JourneyMapTerrain.changed(job.dimensionId, edit.regionX, edit.regionZ)
            true
        } catch (e: Exception) {
            Log.warn("Could not change JourneyMap's region {}, {}: {}", edit.regionX, edit.regionZ, e.toString())
            true
        }
    }

    private fun finish(job: Job) {
        this.job = null
        job.cache?.close { chunks -> if (chunks > 0) Log.info("{}: {} chunks kept in JourneyMap's chunk cache, for saving terrain files", job.title, chunks) }
        val leftNote = if (job.leftOpen > 0) "; ${job.leftOpen} open in JourneyMap left as they were" else ""
        val head = "${job.title} ${if (job.cancelled) "stopped" else "done"}: ${job.changed} region(s) changed$leftNote."
        val undo = if (job.changed > 0) ChatButton.of("[Undo]", "/mapexposer undo", "Puts the regions back as they were before this.") else null
        Minecraft.getInstance().player?.sendSystemMessage(job.endLine(head, undo))
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
                val file = JourneyMapFiles.regionFile(folder, rx.toInt(), rz.toInt())
                if (JourneyMapFiles.restore(file, backups.resolve(name))) {
                    restored++
                    JourneyMapFiles.written(dimension, folder, rx.toInt(), rz.toInt(), exists = Files.exists(file))
                    JourneyMapTerrain.changed(dimension, rx.toInt(), rz.toInt())
                }
            }
        }
        // The chunks it put in JourneyMap's chunk cache, taken back too, off the client thread.
        val ledger = JourneyMapCacheWrites.ledgerIn(backups)
        if (Files.isRegularFile(ledger)) {
            background("SkysMapExposer JourneyMap cache undo") {
                val taken = JourneyMapCacheWrites.withdraw(dimension, ledger)
                if (taken > 0) Log.info("Undo: took {} chunks back out of JourneyMap's chunk cache", taken)
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
    private const val UNREADABLE = "JourneyMap's file of it couldn't be read; the log says why"
    private const val QUEUE = 4
    private const val KEEP_TILES = 8
    private const val MAX_WAITING = 32
    private const val RETRY_MS = 2_000L
    /** How long a region may stay open in JourneyMap before it is skipped, as Xaero's wait for a region to load. */
    private const val WAIT_MS = 60_000L
    private const val KEPT_OPEN = "JourneyMap kept it open; move away from it and download it again"
    private const val MAX_CACHE_BEHIND = 3
}
