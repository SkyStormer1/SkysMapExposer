package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.terrain.FloorColumns
import com.skystormer.skysmapexposer.terrain.HiresTile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Experimental, "Sea floor: high res": the bed under the water, from BlueMap's 3D tiles, where the
 * world map shows BlueMap's picture. BlueMap's flat pictures have one colour and one height per
 * block, the water's surface, so open sea comes out one flat blue; the 3D tiles have the bed.
 *
 * Only close up (the world map at a block a pixel or more) and only the 3D tiles on screen whose
 * part of the flat picture looks like water are downloaded, nearest the middle of the screen
 * first, two at a time so the server (BlueMap usually runs on the game server) is not slowed down.
 * Of each, only what the sea floor needs is kept on disk ([FloorColumns]), for a week.
 *
 * Each of BlueMap's tiles being drawn gets a [Build]: the 3D tiles it needs, and, as they come in,
 * its sea floor drawn the way Xaero draws water ([FloorColumns.xaeroWater]) into [TileStore.Tile.floor],
 * which its pictures are then remade with. The rest of the work happens off the client thread.
 */
class SeaFloor(private val blueMap: BlueMap, private val folder: Path, private val tiles: TileStore) {

    private data class Hires(val map: String, val x: Int, val z: Int)

    /** A map's 3D tile layout and its textures' colours, read once. */
    private class Source(val layout: BlueMap.HiresLayout, val textures: List<FloorColumns.Texture>)

    private class Build(val tile: TileStore.Tile, val map: String, val originX: Int, val originZ: Int) {
        @Volatile var wantedAt = 0L

        /** The block area on screen when last drawn: `minX, minZ, maxX, maxZ`. */
        @Volatile var view = DoubleArray(4)

        /** 3D tiles with water in them still to come in. */
        val waiting: MutableSet<Hires> = ConcurrentHashMap.newKeySet()
        val arrived = ConcurrentLinkedQueue<Pair<Hires, FloorColumns>>()

        @Volatile var started = false
        @Volatile var composing = false
        var composedAt = 0L
        var queuedAt = 0L

        // The worker's: everything that has come in, laid out over the tile.
        var bedTop: ShortArray? = null
        var bedColour: IntArray? = null
        var waterTop: ShortArray? = null
        var waterColour: IntArray? = null
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "SkysMapExposer sea floor").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val sources = ConcurrentHashMap<String, Source>()
    private val sourceFailedAt = ConcurrentHashMap<String, Long>()
    private val builds = HashMap<TileStore.Key, Build>()

    /** 3D tiles to download, with the builds waiting on each; the downloaders take the nearest. */
    private val queued = ConcurrentHashMap<Hires, MutableSet<Build>>()
    private val lock = Object()
    @Volatile private var closed = false
    @Volatile private var centreX = 0.0
    @Volatile private var centreZ = 0.0
    private val failures = java.util.concurrent.atomic.AtomicInteger()

    /** Downloaded since joining, for the status line: 3D tiles and bytes. */
    val downloaded = java.util.concurrent.atomic.AtomicInteger()
    val downloadedBytes = java.util.concurrent.atomic.AtomicLong()

    init {
        repeat(DOWNLOADERS) { n ->
            Thread(::download, "SkysMapExposer sea floor download ${n + 1}").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        }
    }

    /** How many 3D tiles are waiting to be downloaded, for the status line. */
    val waiting: Int get() = queued.size

    /**
     * [tile] of BlueMap map [map] is being drawn close up, with the block area [minX]..[maxX],
     * [minZ]..[maxZ] on screen around ([cameraX], [cameraZ]). Client thread.
     */
    fun want(tile: TileStore.Tile, map: String, cameraX: Double, cameraZ: Double, minX: Double, maxX: Double, minZ: Double, maxZ: Double) {
        if (closed || tile.state != TileStore.State.READY) return
        val now = System.currentTimeMillis()
        centreX = cameraX
        centreZ = cameraZ
        var build = builds[tile.key]
        if (build == null || build.tile !== tile) {
            build = Build(tile, map, tile.key.x * tile.tileSize, tile.key.z * tile.tileSize)
            builds[tile.key] = build
        }
        build.wantedAt = now
        build.view = doubleArrayOf(minX - MARGIN, minZ - MARGIN, maxX + MARGIN, maxZ + MARGIN)
        if (!build.started) {
            build.started = true
            val starting = build
            worker.execute { start(starting) }
            return
        }
        // What has come in, drawn every little while; at once when the last of it is in.
        if (!build.composing && build.arrived.isNotEmpty() && (build.waiting.isEmpty() || now - build.composedAt > COMPOSE_MS)) {
            build.composing = true
            build.composedAt = now
            val composing = build
            worker.execute { compose(composing) }
        }
        // Tiles left out because they were off screen, now that the view has moved.
        if (build.waiting.isNotEmpty() && now - build.queuedAt > REQUEUE_MS) {
            build.queuedAt = now
            queue(build)
        }
        if (builds.size > MAX_BUILDS) builds.values.removeIf { now - it.wantedAt > FORGET_MS && it !== build }
    }

    /** Leaves BlueMap's flat picture everywhere again: the setting was switched off. Client thread. */
    fun clear() {
        for (build in builds.values) {
            if (build.tile.floor != null) {
                build.tile.floor = null
                build.tile.requested.clear()
            }
        }
        builds.clear()
        queued.clear()
    }

    fun close() {
        closed = true
        worker.shutdownNow()
        synchronized(lock) { lock.notifyAll() }
    }

    // ---- off the client thread from here on ----

    /** Finds the 3D tiles under water in the tile, takes the ones already on disk and asks for the rest. */
    private fun start(build: Build) {
        try {
            val source = source(build.map) ?: return
            val picture = tiles.picture(build.tile) ?: return
            val size = build.tile.tileSize
            val layout = source.layout
            val firstX = layout.tileOf(build.originX, layout.offsetX)
            val lastX = layout.tileOf(build.originX + size - 1, layout.offsetX)
            val firstZ = layout.tileOf(build.originZ, layout.offsetZ)
            val lastZ = layout.tileOf(build.originZ + size - 1, layout.offsetZ)
            for (hz in firstZ..lastZ) {
                for (hx in firstX..lastX) {
                    if (!looksWet(picture, size, build, layout, hx, hz)) continue
                    val hires = Hires(build.map, hx, hz)
                    when (val kept = readKept(hires)) {
                        null -> build.waiting.add(hires)
                        NOTHING -> {}
                        else -> build.arrived.add(hires to kept)
                    }
                }
            }
            queue(build)
        } catch (e: Exception) {
            if (failures.getAndIncrement() < LOGGED_FAILURES) Log.warn("Sea floor: could not start on BlueMap tile {}: {}", build.tile.key, e.toString())
        }
    }

    /**
     * Whether any of the flat picture under 3D tile ([hx], [hz]) looks like water. The picture is
     * lit as Xaero lights it; water is flat, so dividing the light back out gives BlueMap's colour.
     */
    private fun looksWet(picture: IntArray, size: Int, build: Build, layout: BlueMap.HiresLayout, hx: Int, hz: Int): Boolean {
        val minX = maxOf(hx * layout.tileSize + layout.offsetX - build.originX, 0)
        val maxX = minOf((hx + 1) * layout.tileSize + layout.offsetX - build.originX, size)
        val minZ = maxOf(hz * layout.tileSize + layout.offsetZ - build.originZ, 0)
        val maxZ = minOf((hz + 1) * layout.tileSize + layout.offsetZ - build.originZ, size)
        for (z in minZ until maxZ) {
            for (x in minX until maxX) {
                val lit = picture[z * size + x]
                if (lit == 0) continue
                fun channel(shift: Int) = (((lit shr shift) and 0xFF) / FLAT_WATER_LIGHT).toInt().coerceIn(0, 255)
                val lab = BlockPalette.toLab((channel(16) shl 16) or (channel(8) shl 8) or channel(0))
                if (XaeroPalette.isWater(lab.l, lab.a, lab.b, SEA_LEVEL, true)) return true
            }
        }
        return false
    }

    /** Puts the build's 3D tiles that are on screen on the download list. */
    private fun queue(build: Build) {
        var added = false
        val view = build.view
        for (hires in build.waiting) {
            if (!onScreen(hires, view)) continue
            queued.computeIfAbsent(hires) { ConcurrentHashMap.newKeySet() }.add(build)
            added = true
        }
        if (added) synchronized(lock) { lock.notifyAll() }
    }

    private fun onScreen(hires: Hires, view: DoubleArray): Boolean {
        val layout = sources[hires.map]?.layout ?: return false
        val minX = hires.x * layout.tileSize + layout.offsetX
        val minZ = hires.z * layout.tileSize + layout.offsetZ
        return minX + layout.tileSize > view[0] && minX < view[2] && minZ + layout.tileSize > view[1] && minZ < view[3]
    }

    /** One downloader: the queued 3D tile nearest the middle of the screen, again and again. */
    private fun download() {
        while (!closed) {
            val hires = synchronized(lock) {
                var next: Hires? = null
                while (!closed) {
                    next = nearest()
                    if (next != null) break
                    lock.wait(1000L)
                }
                next
            } ?: return
            val waiting = queued.remove(hires) ?: continue
            val now = System.currentTimeMillis()
            // Scrolled away from, or the world map closed: left for when it is back on screen.
            val still = waiting.filter { now - it.wantedAt < STALE_MS && onScreen(hires, it.view) }
            if (still.isEmpty()) continue
            val columns = try {
                fetch(hires)
            } catch (e: Exception) {
                if (failures.getAndIncrement() < LOGGED_FAILURES) Log.warn("Sea floor: could not download BlueMap 3D tile {}: {}", hires, e.toString())
                NOTHING
            }
            for (build in waiting) {
                // In before it is no longer waited for, so a build never looks finished with it still to come.
                if (columns !== NOTHING) build.arrived.add(hires to columns)
                build.waiting.remove(hires)
            }
        }
    }

    private fun nearest(): Hires? {
        val cx = centreX
        val cz = centreZ
        var best: Hires? = null
        var bestDistance = Double.MAX_VALUE
        for (hires in queued.keys) {
            val layout = sources[hires.map]?.layout ?: continue
            val dx = (hires.x + 0.5) * layout.tileSize + layout.offsetX - cx
            val dz = (hires.z + 0.5) * layout.tileSize + layout.offsetZ - cz
            val distance = dx * dx + dz * dz
            if (distance < bestDistance) { best = hires; bestDistance = distance }
        }
        return best
    }

    /** Downloads one 3D tile and keeps what the sea floor needs of it. */
    private fun fetch(hires: Hires): FloorColumns {
        val source = sources[hires.map] ?: return NOTHING
        val bytes = blueMap.hiresTile(hires.map, hires.x, hires.z)
        downloaded.incrementAndGet()
        val columns = if (bytes == null) NOTHING else {
            downloadedBytes.addAndGet(bytes.size.toLong())
            FloorColumns.of(HiresTile.read(BlueMap.gunzipIfNeeded(bytes, MAX_TILE_BYTES)), source.textures, source.layout.tileSize)
        }
        try {
            if (columns === NOTHING || columns.count == 0) {
                SafeFiles.write(fileOf(hires, NONE_SUFFIX), ByteArray(0))
                Files.deleteIfExists(fileOf(hires, DATA_SUFFIX))
            } else {
                SafeFiles.write(fileOf(hires, DATA_SUFFIX), columns.encode())
                Files.deleteIfExists(fileOf(hires, NONE_SUFFIX))
            }
        } catch (e: IOException) {
            if (failures.getAndIncrement() < LOGGED_FAILURES) Log.warn("Sea floor: could not keep {} on disk: {}", hires, e.toString())
        }
        return if (columns.count == 0) NOTHING else columns
    }

    /** What is on disk for [hires] and recent enough: its columns, [NOTHING], or null to download it. */
    private fun readKept(hires: Hires): FloorColumns? {
        val now = System.currentTimeMillis()
        val data = fileOf(hires, DATA_SUFFIX)
        if (Files.exists(data) && now - Files.getLastModifiedTime(data).toMillis() < KEEP_MS) {
            return try {
                FloorColumns.decode(Files.readAllBytes(data))
            } catch (e: Exception) {
                Files.deleteIfExists(data)
                null
            }
        }
        val none = fileOf(hires, NONE_SUFFIX)
        if (Files.exists(none) && now - Files.getLastModifiedTime(none).toMillis() < KEEP_NONE_MS) return NOTHING
        return null
    }

    // The map id came from the server and becomes a folder name, so only ever a safe one.
    private fun fileOf(hires: Hires, suffix: String): Path =
        folder.resolve(if (BlueMap.isMapId(hires.map)) hires.map else "_").resolve("${hires.x}_${hires.z}$suffix")

    private fun source(map: String): Source? {
        sources[map]?.let { return it }
        val failedAt = sourceFailedAt[map]
        if (failedAt != null && System.currentTimeMillis() - failedAt < SOURCE_RETRY_MS) return null
        return try {
            val layout = blueMap.hiresLayout(map)
            val textures = blueMap.textureColours(map).map { (path, colour) -> FloorColumns.Texture(path, colour) }
            Source(layout, textures).also { sources[map] = it }
        } catch (e: Exception) {
            sourceFailedAt[map] = System.currentTimeMillis()
            Log.warn("Sea floor: BlueMap map '{}' has no usable 3D tiles: {}", map, e.toString())
            null
        }
    }

    /** Lays what has come in over the tile, and draws the sea floor from all of it. */
    private fun compose(build: Build) {
        try {
            val size = build.tile.tileSize
            val bedTop = build.bedTop ?: ShortArray(size * size) { FloorColumns.NONE }.also { build.bedTop = it }
            val bedColour = build.bedColour ?: IntArray(size * size).also { build.bedColour = it }
            val waterTop = build.waterTop ?: ShortArray(size * size) { FloorColumns.NONE }.also { build.waterTop = it }
            val waterColour = build.waterColour ?: IntArray(size * size).also { build.waterColour = it }
            while (true) {
                val (hires, columns) = build.arrived.poll() ?: break
                val layout = sources[hires.map]?.layout ?: continue
                val hiresX = hires.x * layout.tileSize + layout.offsetX
                val hiresZ = hires.z * layout.tileSize + layout.offsetZ
                for (z in 0 until columns.size) {
                    val tz = hiresZ + z - build.originZ
                    if (tz !in 0 until size) continue
                    for (x in 0 until columns.size) {
                        val tx = hiresX + x - build.originX
                        if (tx !in 0 until size) continue
                        val from = z * columns.size + x
                        if (!columns.has(from)) continue
                        val into = tz * size + tx
                        bedTop[into] = columns.bedTop[from]
                        bedColour[into] = columns.bedColour[from]
                        waterTop[into] = columns.waterTop[from]
                        waterColour[into] = columns.waterColour[from]
                    }
                }
            }
            build.tile.floor = draw(size, bedTop, bedColour, waterTop, waterColour)
            // Its pictures are made again with the sea floor in, each as it is next drawn.
            build.tile.requested.clear()
            if (build.waiting.isEmpty() && build.arrived.isEmpty()) {
                build.bedTop = null
                build.bedColour = null
                build.waterTop = null
                build.waterColour = null
            }
        } catch (e: Exception) {
            if (failures.getAndIncrement() < LOGGED_FAILURES) Log.warn("Sea floor: could not draw BlueMap tile {}: {}", build.tile.key, e.toString())
        } finally {
            build.composing = false
        }
    }

    companion object {
        /** Blocks a texture pixel, at most, for the sea floor to be fetched: the world map at a block a pixel or closer. */
        const val CLOSE_UP = 1

        private const val DOWNLOADERS = 2
        private const val COMPOSE_MS = 1500L
        private const val REQUEUE_MS = 1000L
        private const val STALE_MS = 10_000L
        private const val FORGET_MS = 5 * 60_000L
        private const val MAX_BUILDS = 64
        private const val SOURCE_RETRY_MS = 10 * 60_000L
        private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000
        private const val KEEP_NONE_MS = 24L * 60 * 60 * 1000
        private const val LOGGED_FAILURES = 5
        private const val MAX_TILE_BYTES = 32 * 1024 * 1024
        private const val DATA_SUFFIX = ".floor"
        private const val NONE_SUFFIX = ".none"

        /** Blocks around the screen's edge also fetched, so scrolling a little finds them there. */
        private const val MARGIN = 32.0

        private const val SEA_LEVEL = 62

        /** The light Xaero gives flat ground at the sea's surface: what the picture's water was lit by. */
        private val FLAT_WATER_LIGHT = TilePixels.xaeroLight(SEA_LEVEL, SEA_LEVEL, SEA_LEVEL)

        /** Nothing there: no 3D tile, or no water over a bed in it. */
        private val NOTHING = FloorColumns(0)

        /** The sea floor over [size]-square tile data: Xaero's water where there is any, 0 elsewhere. */
        fun draw(size: Int, bedTop: ShortArray, bedColour: IntArray, waterTop: ShortArray, waterColour: IntArray): IntArray {
            val out = IntArray(size * size)
            for (z in 0 until size) {
                for (x in 0 until size) {
                    val i = z * size + x
                    if (bedTop[i] == FloorColumns.NONE || waterTop[i] == FloorColumns.NONE) continue
                    val bedY = bedTop[i] - 1
                    // Off the tile, or no sea floor there: level with this one, as BlueMap's picture does.
                    fun yAt(j: Int, ok: Boolean) = if (ok && bedTop[j] != FloorColumns.NONE) bedTop[j] - 1 else bedY
                    val north = yAt(i - size, z > 0)
                    val northWest = yAt(i - size - 1, z > 0 && x > 0)
                    out[i] = FloorColumns.xaeroWater(waterColour[i], bedColour[i], waterTop[i] - bedTop[i], bedY, north, northWest)
                }
            }
            return out
        }
    }
}
