package com.skystormer.skysmapexposer.terrain

import com.skystormer.skysmapexposer.BlueMap
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapMenus
import com.skystormer.skysmapexposer.SafeFiles
import com.skystormer.skysmapexposer.Session
import net.minecraft.client.Minecraft
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.PalettedContainerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/**
 * Experimental: fills Bobby, Voxy and Distant Horizons with terrain rebuilt from BlueMap's 3D
 * tiles ([ChunkRebuilder]), around where you stand, in the chunks they have nothing for.
 *
 * Works through the area nearest first, a few chunks at a time ([BATCH] × [BATCH]): download that
 * piece's tiles, rebuild its chunks, hand them over, forget them. So memory use stays the same
 * however big the area, and it waits whenever one of the mods falls behind. Downloaded tiles are
 * kept on disk for a week, so filling the same place again costs no downloading.
 *
 * Only a couple of tiles are downloaded at a time: BlueMap usually runs on the game server itself.
 *
 * Before it starts ([check]): BlueMap must have 3D tiles around you (a map drawn flat only has
 * none, and that is said at once), and you are shown how much it would download and that it cannot
 * be undone. On the way, a tile that cannot be downloaded or read is skipped and counted, and a
 * mod that can no longer take chunks (Bobby and Distant Horizons close a dimension's storage when
 * you leave it) drops out while the others carry on in the dimension the fill started in.
 */
object TerrainFill {

    /** Sizes offered, in blocks from you to the edge. */
    val RADII = listOf(250, 500, 1000, 2000, 4000)
    var radius = 500

    private const val BATCH = 4

    /**
     * The most one 3D tile may unpack to. Real ones are well under 10 MB; a server sending more is
     * broken or hostile, and two of these at once must still fit in a small game's memory.
     */
    private const val MAX_TILE_BYTES = 32 * 1024 * 1024
    private const val KEEP_TILES_MS = 7L * 24 * 60 * 60 * 1000

    /** How long "BlueMap has no tile here" is believed: a day, so a map the server starts drawing in 3D is picked up soon. */
    private const val KEEP_MISSING_MS = 24L * 60 * 60 * 1000

    /** Rings of 3D tiles around you looked at before saying a map has none. */
    private const val CHECK_RINGS = 2

    /** Skipped tiles named in the log; the rest are only counted. */
    private const val LOGGED_FAILURES = 5

    private class Job(
        val session: Session,
        val map: String,
        val dimension: String,
        val centre: ChunkPos,
        val radiusChunks: Int,
        val rebuilderOf: (minY: Int, height: Int) -> ChunkRebuilder,
        val minY: Int,
        val height: Int,
        val targets: List<FillTarget>,
    ) {
        @Volatile var cancelled = false
        @Volatile var finished = false
        @Volatile var message = "Starting…"
        // Every target listed from the start, so one getting nothing shows as 0.
        val sent = LinkedHashMap<String, Int>().also { map -> targets.forEach { map[it.name] = 0 } }
        val progress = FillProgress(targets, MapMenus.dimensionName(dimension) ?: dimension)
        var endedAt = 0L
    }

    @Volatile
    private var job: Job? = null

    val running: Boolean get() = job?.let { !it.finished } ?: false

    /** One line on how it is going, or null when there is nothing to say. */
    fun status(): String? {
        val current = job ?: return null
        if (current.finished && System.currentTimeMillis() - current.endedAt > 60_000L) return null
        return current.message
    }

    fun cancel() {
        job?.cancelled = true
    }

    /** Leaving the world: nothing more can be handed over. */
    fun forget() {
        job?.cancelled = true
        job = null
    }

    /**
     * Whether a BlueMap map has 3D tiles to fill from, as found by [check]: null [problem] when it
     * has, with the average size of the tiles found, for the estimate.
     */
    private class Checked(val problem: String?, val averageBytes: Long)

    /** Answers of [check] per map, for the session they were found in; asked again next session. */
    private val checked = HashMap<String, Checked>()
    private var checkedFor: Session? = null

    private fun checkedHere(session: Session): HashMap<String, Checked> {
        if (checkedFor !== session) {
            checked.clear()
            checkedFor = session
        }
        return checked
    }

    /** Why the fill cannot start in the dimension you are in, if that is already known; for the button's tooltip. */
    fun knownProblem(): String? {
        val session = Session.current ?: return null
        val level = Minecraft.getInstance().level ?: return null
        val map = session.server.markersFor(level.dimension().identifier().toString()) ?: return null
        return checkedHere(session)[map]?.problem
    }

    /** What a fill around you would download, and that it cannot be undone, for the confirm screen. */
    class Estimate(val text: String)

    /**
     * Before a fill: off the client thread, checks that BlueMap has 3D tiles around you, then works
     * out how many tiles the fill would download and roughly how much. [done] gets, on the client
     * thread, either why not or the [Estimate] to confirm.
     */
    fun check(done: (problem: String?, estimate: Estimate?) -> Unit) {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return done("Join a world first", null)
        val player = minecraft.player ?: return done("Join a world first", null)
        val session = Session.current ?: return done("This server has no BlueMap set up", null)
        val map = session.server.markersFor(level.dimension().identifier().toString())
            ?: return done("No BlueMap map is set for this dimension", null)
        val chosen = FillTarget.chosen()
        if (chosen.isEmpty()) return done("Switch on at least one mod to fill", null)
        val x = player.blockX
        val z = player.blockZ
        val blocks = radius
        val known = checkedHere(session)
        Thread({
            val answer = known[map] ?: findTiles(session, map, x, z)
            val estimate = if (answer.problem != null) null else estimate(session, map, x, z, blocks, answer.averageBytes, chosen)
            minecraft.execute {
                if (checkedFor === session) known[map] = answer
                done(answer.problem, estimate)
            }
        }, "SkysMapExposer terrain check").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Whether [map] has its 3D layout and a 3D tile near block ([x], [z]): the player's tile, then a little wider. */
    private fun findTiles(session: Session, map: String, x: Int, z: Int): Checked {
        val noTerrain = Checked(
            "BlueMap map '$map' has no 3D terrain here, so it can't fill Bobby, Voxy or Distant Horizons. " +
                "It can still be shown on and downloaded into Xaero's map.", 0,
        )
        return try {
            val layout = session.blueMap.hiresLayout(map)
            val cache = cacheOf(session, map)
            val cx = layout.tileOf(x, layout.offsetX)
            val cz = layout.tileOf(z, layout.offsetZ)
            val sizes = ArrayList<Int>()
            for (ring in 0..CHECK_RINGS) {
                for (dx in -ring..ring) for (dz in -ring..ring) {
                    if (maxOf(abs(dx), abs(dz)) != ring) continue
                    cached(cache, cx + dx, cz + dz) { session.blueMap.hiresTile(map, cx + dx, cz + dz) }?.let { sizes.add(it.size) }
                }
                if (sizes.isNotEmpty() && ring >= 1) break
            }
            if (sizes.isEmpty()) noTerrain else Checked(null, sizes.average().toLong())
        } catch (e: Exception) {
            Log.info("Terrain fill: BlueMap map '{}' has no usable 3D tiles: {}", map, e.toString())
            noTerrain
        }
    }

    private fun estimate(session: Session, map: String, x: Int, z: Int, blocks: Int, averageBytes: Long, chosen: List<String>): Estimate {
        val warning = "This can't be undone: ${chosen.joinToString(" and ")} will keep this terrain until you visit those areas " +
            "yourself, or clear their data for this server."
        val layout = try {
            session.blueMap.hiresLayout(map)
        } catch (e: Exception) {
            return Estimate("Fill $blocks blocks around you. $warning")
        }
        val cache = cacheOf(session, map)
        var missing = 0
        val now = System.currentTimeMillis()
        for (tx in layout.tileOf(x - blocks, layout.offsetX)..layout.tileOf(x + blocks, layout.offsetX)) {
            for (tz in layout.tileOf(z - blocks, layout.offsetZ)..layout.tileOf(z + blocks, layout.offsetZ)) {
                if (!fresh(cache, tx, tz, now)) missing++
            }
        }
        val size = missing * averageBytes
        val download = if (missing == 0) "BlueMap's 3D tiles there are already downloaded"
        else "about %,d BlueMap 3D tiles, roughly %s".format(missing, if (size >= 1L shl 30) "%.1f GB".format(size / 1073741824.0) else "${maxOf(1, size / 1048576)} MB")
        return Estimate("Fill $blocks blocks around you: $download.\n\n$warning")
    }

    private fun cacheOf(session: Session, map: String): Path = session.folder.resolve("hires").resolve(SafeFiles.name(map))

    /** Starts a fill around you. Call on the client thread; returns what to tell the player. */
    fun start(): String {
        if (running) return "A terrain fill is already running"
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return "Join a world first"
        val player = minecraft.player ?: return "Join a world first"
        val session = Session.current ?: return "This server has no BlueMap set up"
        val dimension = level.dimension().identifier().toString()
        val map = session.server.markersFor(dimension) ?: return "No BlueMap map is set for this dimension"
        if (FillTarget.installed().isEmpty()) return "Install Bobby, Voxy or Distant Horizons to use this"
        if (FillTarget.chosen().isEmpty()) return "Switch on at least one mod to fill"

        val factory = PalettedContainerFactory.create(level.registryAccess())
        val (targets, notes) = FillTarget.ready(level, factory)
        if (targets.isEmpty()) return "Nothing to fill: " + notes.joinToString("; ")

        val blocks = TextureBlocks.build(minecraft)
        val biomes = BiomePicker.build(level, level.getBiome(player.blockPosition()))
        val radiusChunks = max(1, radius / 16)
        val started = Job(session, map, dimension, player.chunkPosition(), radiusChunks,
            { minY, height -> ChunkRebuilder(blocks, biomes, factory, minY, height) },
            level.minY, level.height, targets)
        job = started
        Thread({ work(started) }, "SkysMapExposer terrain fill").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        val names = targets.joinToString(", ") { it.name }
        return "Filling $names from BlueMap, $radius blocks around you" + if (notes.isEmpty()) "" else " (skipped ${notes.joinToString("; ")})"
    }

    private fun work(job: Job) {
        val blueMap = BlueMap(job.session.server.url)
        val downloads = Executors.newFixedThreadPool(2) { Thread(it, "SkysMapExposer terrain tiles").apply { isDaemon = true } }
        try {
            job.message = "Asking BlueMap about its 3D tiles…"
            val layout = blueMap.hiresLayout(job.map)
            val textures = blueMap.textures(job.map)
            val rebuilder = job.rebuilderOf(job.minY, job.height)
            val cache = cacheOf(job.session, job.map)
            prune(cache)
            val progress = job.progress
            // The fill keeps to the dimension it started in, wherever you go; the mods that can no
            // longer take its chunks drop out ([FillProgress.use]).
            fun nowIn(): String? = Minecraft.getInstance().level?.dimension()?.identifier()?.toString()?.let(MapMenus::dimensionName)

            // Small square pieces, nearest first.
            val c = job.centre
            val r = job.radiusChunks
            val pieces = ArrayList<Pair<Int, Int>>()
            for (bx in Math.floorDiv(c.x() - r, BATCH)..Math.floorDiv(c.x() + r, BATCH)) {
                for (bz in Math.floorDiv(c.z() - r, BATCH)..Math.floorDiv(c.z() + r, BATCH)) pieces += bx to bz
            }
            pieces.sortBy { (bx, bz) -> max(abs(bx * BATCH + BATCH / 2 - c.x()), abs(bz * BATCH + BATCH / 2 - c.z())) }

            var built = 0
            var empty = 0
            // Tiles asked for, and how many BlueMap had: a map drawn flat only (no 3D tiles) has none.
            var asked = 0
            var found = 0
            for ((index, piece) in pieces.withIndex()) {
                if (job.cancelled || progress.active.isEmpty()) break
                val chunks = ArrayList<ChunkPos>()
                val wanted = HashMap<ChunkPos, List<FillTarget>>()
                for (x in piece.first * BATCH until piece.first * BATCH + BATCH) {
                    for (z in piece.second * BATCH until piece.second * BATCH + BATCH) {
                        if (abs(x - c.x()) > r || abs(z - c.z()) > r) continue
                        val pos = ChunkPos(x, z)
                        val wanting = progress.active.toList().filter { target -> progress.use(target, nowIn()) { target.wants(pos) } == true }
                        if (wanting.isNotEmpty()) { chunks += pos; wanted[pos] = wanting }
                    }
                }
                job.message = "Filling from BlueMap: ${index * 100 / pieces.size}% — ${summary(job)}"
                if (chunks.isEmpty()) continue

                val minX = chunks.minOf { it.minBlockX }; val maxX = chunks.maxOf { it.maxBlockX }
                val minZ = chunks.minOf { it.minBlockZ }; val maxZ = chunks.maxOf { it.maxBlockZ }
                val fetches = ArrayList<Future<ChunkRebuilder.Tile?>>()
                for (tx in layout.tileOf(minX, layout.offsetX)..layout.tileOf(maxX, layout.offsetX)) {
                    for (tz in layout.tileOf(minZ, layout.offsetZ)..layout.tileOf(maxZ, layout.offsetZ)) {
                        fetches += downloads.submit<ChunkRebuilder.Tile?> {
                            val bytes = cached(cache, tx, tz) { blueMap.hiresTile(job.map, tx, tz) } ?: return@submit null
                            val faces = HiresTile.read(BlueMap.gunzipIfNeeded(bytes, MAX_TILE_BYTES))
                            ChunkRebuilder.Tile(faces, tx * layout.tileSize + layout.offsetX, tz * layout.tileSize + layout.offsetZ)
                        }
                    }
                }
                // One tile that times out, fails to download or cannot be read is skipped, not the fill.
                val failed = ArrayList<String>()
                val tiles = fetches.mapNotNull { fetch ->
                    if (job.cancelled) return@mapNotNull null
                    try {
                        fetch.get(2, TimeUnit.MINUTES)
                    } catch (e: Exception) {
                        fetch.cancel(true)
                        val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                        failed.add(cause.toString())
                        if (progress.unreadable + failed.size <= LOGGED_FAILURES) Log.warn("Terrain fill: skipped a BlueMap 3D tile: {}", cause.toString())
                        null
                    }
                }
                if (job.cancelled) break
                asked += fetches.size
                found += tiles.size
                progress.batch(fetches.size, failed)
                if (progress.hopeless) {
                    throw java.io.IOException("every BlueMap 3D tile so far failed to download or read (${progress.firstReasons().firstOrNull()})")
                }

                val done = rebuilder.build(chunks, tiles, textures)
                empty += chunks.size - done.size
                for (chunk in done) {
                    for (target in wanted[chunk.pos].orEmpty()) {
                        if (progress.use(target, nowIn()) { target.accept(chunk) } != null) job.sent.merge(target.name, 1, Int::plus)
                    }
                }
                built += done.size
                progress.active.toList().forEach { target -> progress.use(target, nowIn()) { target.afterBatch() } }
                while (!job.cancelled && progress.active.toList().any { target -> progress.use(target, nowIn()) { target.busy() } == true }) Thread.sleep(250)
            }
            job.message = if (!job.cancelled && progress.active.isEmpty()) {
                "Stopped: no mod can take more chunks. ${summary(job)}${progress.afterword()}"
            } else if (!job.cancelled && asked > 0 && found == 0 && progress.unreadable == 0) {
                // Without 3D tiles there is nothing to build from: say so, rather than a quiet 0.
                "Nothing to build: BlueMap map '${job.map}' has no 3D tiles here. This server draws it as a flat map only " +
                    "(as many do for the Nether), so it can be shown on Xaero's map but not filled into ${job.targets.joinToString(", ") { it.name }}."
            } else {
                (if (job.cancelled) "Stopped. " else "Done. ") + summary(job) +
                    (if (empty > 0) " ($empty chunks BlueMap never drew)" else "") + progress.afterword()
            }
            Log.info("Terrain fill: {} chunks built; {}; {} of {} BlueMap 3D tiles found for map '{}'", built, summary(job), found, asked, job.map)
        } catch (e: Exception) {
            if (job.cancelled) {
                // Leaving the world closes the mods' storage under us; that is a stop, not a fault.
                job.message = "Stopped. " + summary(job)
                Log.info("Terrain fill stopped on leaving: {}", summary(job))
            } else {
                job.message = "Failed: ${e.message ?: e.javaClass.simpleName}"
                Log.error("Terrain fill failed", e)
            }
        } finally {
            job.progress.active.forEach { runCatching { it.afterBatch() } }
            downloads.shutdownNow()
            blueMap.close()
            job.finished = true
            job.endedAt = System.currentTimeMillis()
        }
    }

    private fun summary(job: Job): String =
        job.sent.entries.joinToString(", ") { "${it.value} chunks to ${it.key}" }

    /** Deletes cached tiles older than a week, so the cache doesn't grow for ever. */
    private fun prune(folder: Path) {
        if (!Files.isDirectory(folder)) return
        val cutoff = System.currentTimeMillis() - KEEP_TILES_MS
        Files.list(folder).use { files ->
            files.filter { Files.isRegularFile(it) && Files.getLastModifiedTime(it).toMillis() < cutoff }
                .forEach { runCatching { Files.delete(it) } }
        }
    }

    /** Whether tile ([tx], [tz]) is on disk, or known to be missing, recently enough not to be asked for again. */
    private fun fresh(folder: Path, tx: Int, tz: Int, now: Long): Boolean {
        val file = folder.resolve("${tx}_$tz.prbm")
        val empty = folder.resolve("${tx}_$tz.none")
        return (Files.exists(file) && now - Files.getLastModifiedTime(file).toMillis() < KEEP_TILES_MS) ||
            (Files.exists(empty) && now - Files.getLastModifiedTime(empty).toMillis() < KEEP_MISSING_MS)
    }

    /** A tile from the disk cache if it is under a week old (a missing one, a day), else from BlueMap (and kept). Null: BlueMap has none. */
    private fun cached(folder: Path, tx: Int, tz: Int, fetch: () -> ByteArray?): ByteArray? {
        val file = folder.resolve("${tx}_$tz.prbm")
        val empty = folder.resolve("${tx}_$tz.none")
        val now = System.currentTimeMillis()
        for (known in listOf(file, empty)) {
            val keep = if (known == file) KEEP_TILES_MS else KEEP_MISSING_MS
            if (Files.exists(known) && now - Files.getLastModifiedTime(known).toMillis() < keep) {
                return if (known == file) Files.readAllBytes(file) else null
            }
        }
        val bytes = fetch()
        Files.createDirectories(folder)
        if (bytes == null) SafeFiles.write(empty, ByteArray(0)) else SafeFiles.write(file, bytes)
        return bytes
    }
}
