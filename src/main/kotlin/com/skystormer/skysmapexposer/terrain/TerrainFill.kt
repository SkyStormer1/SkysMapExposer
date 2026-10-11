package com.skystormer.skysmapexposer.terrain

import com.skystormer.skysmapexposer.BlueMap
import com.skystormer.skysmapexposer.Log
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
 */
object TerrainFill {

    /** Sizes offered, in blocks from you to the edge. */
    val RADII = listOf(250, 500, 1000, 2000, 4000)
    var radius = 500

    private const val BATCH = 4
    private const val KEEP_TILES_MS = 7L * 24 * 60 * 60 * 1000

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
            val cache = job.session.folder.resolve("hires").resolve(SafeFiles.name(job.map))

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
            for ((index, piece) in pieces.withIndex()) {
                if (job.cancelled) break
                val chunks = ArrayList<ChunkPos>()
                val wanted = HashMap<ChunkPos, List<FillTarget>>()
                for (x in piece.first * BATCH until piece.first * BATCH + BATCH) {
                    for (z in piece.second * BATCH until piece.second * BATCH + BATCH) {
                        if (abs(x - c.x()) > r || abs(z - c.z()) > r) continue
                        val pos = ChunkPos(x, z)
                        val wanting = job.targets.filter { it.wants(pos) }
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
                            val faces = HiresTile.read(BlueMap.gunzipIfNeeded(bytes, 256 * 1024 * 1024))
                            ChunkRebuilder.Tile(faces, tx * layout.tileSize + layout.offsetX, tz * layout.tileSize + layout.offsetZ)
                        }
                    }
                }
                val tiles = fetches.mapNotNull { if (job.cancelled) null else it.get(2, TimeUnit.MINUTES) }
                if (job.cancelled) break

                val done = rebuilder.build(chunks, tiles, textures)
                empty += chunks.size - done.size
                for (chunk in done) {
                    for (target in wanted[chunk.pos].orEmpty()) {
                        target.accept(chunk)
                        job.sent.merge(target.name, 1, Int::plus)
                    }
                }
                built += done.size
                job.targets.forEach { it.afterBatch() }
                while (!job.cancelled && job.targets.any { it.busy() }) Thread.sleep(250)
            }
            job.message = (if (job.cancelled) "Stopped. " else "Done. ") + summary(job) +
                if (empty > 0) " ($empty chunks BlueMap never drew)" else ""
            Log.info("Terrain fill: {} chunks built; {}", built, summary(job))
        } catch (e: Exception) {
            job.message = "Failed: ${e.message ?: e.javaClass.simpleName}"
            Log.error("Terrain fill failed", e)
        } finally {
            job.targets.forEach { runCatching { it.afterBatch() } }
            downloads.shutdownNow()
            blueMap.close()
            job.finished = true
            job.endedAt = System.currentTimeMillis()
        }
    }

    private fun summary(job: Job): String =
        job.sent.entries.joinToString(", ") { "${it.value} chunks to ${it.key}" }

    /** A tile from the disk cache if it is under a week old, else from BlueMap (and kept). Null: BlueMap has none. */
    private fun cached(folder: Path, tx: Int, tz: Int, fetch: () -> ByteArray?): ByteArray? {
        val file = folder.resolve("${tx}_$tz.prbm")
        val empty = folder.resolve("${tx}_$tz.none")
        val now = System.currentTimeMillis()
        for (known in listOf(file, empty)) {
            if (Files.exists(known) && now - Files.getLastModifiedTime(known).toMillis() < KEEP_TILES_MS) {
                return if (known == file) Files.readAllBytes(file) else null
            }
        }
        val bytes = fetch()
        Files.createDirectories(folder)
        if (bytes == null) SafeFiles.write(empty, ByteArray(0)) else SafeFiles.write(file, bytes)
        return bytes
    }
}
