package com.skystormer.skysmapexposer

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Everything that belongs to being connected to one server with a BlueMap: its visit record, its
 * tiles, and the threads that fetch and save them.
 *
 * Exists only while connected to a server listed in the config; everywhere else [current] is null
 * and the mod does nothing but say so once.
 */
class Session private constructor(val server: Config.Server, val folder: Path) {

    private val io: ExecutorService = Executors.newSingleThreadExecutor(daemon("SkysMapExposer IO"))
    private val workers: ExecutorService = Executors.newFixedThreadPool(6, daemon("SkysMapExposer fetch"))

    val blueMap = BlueMap(server.url)
    val visits = VisitLog(folder, io)
    val regionAges = RegionAges(io)
    val tiles = TileStore(blueMap, folder.resolve("tiles"), workers)
    val markers = Markers(blueMap, workers)
    val seaFloor = SeaFloor(blueMap, folder.resolve("seafloor"), tiles)
    val locks = Locks(folder.resolve("locks.json"), io)

    val gaps = GapStore(folder, io)

    init {
        io.execute {
            try {
                val size = TileCache.tidy(folder.resolve("tiles"))
                Log.info("Tile cache for {}: {}", server.addresses.first(), size)
            } catch (e: Exception) {
                Log.warn("Could not look over the tile cache for {}: {}", server.addresses.first(), e.toString())
            }
        }
    }

    /**
     * Empties this server's tile cache, in memory and on disk, and runs [done] on the client thread
     * when it has. Background preparation is stopped first and starts over afterwards.
     */
    fun clearTiles(done: (String) -> Unit) {
        MapPrep.cancel()
        tiles.clear()
        io.execute {
            val said = try {
                TileCache.clear(folder.resolve("tiles"))
                "Tile cache cleared"
            } catch (e: Exception) {
                Log.warn("Could not clear the tile cache: {}", e.toString())
                "Could not clear all of the tile cache: ${e.message ?: e.javaClass.simpleName}"
            }
            Minecraft.getInstance().execute {
                if (current === this) {
                    tiles.clear()
                    if (Config.prepareMap) MapPrep.start(this, MarkerElements.worldMapDimension())
                }
                done(said)
            }
        }
    }

    /** Regions Xaero has a file for but cannot show, which BlueMap covers. */
    val unshowable = UnshowableRegions(folder.resolve("unshowable-regions.json"), io)

    /**
     * Chunks Xaero was last seen to have nothing for, per dimension. Remembered, on disk too,
     * because Xaero only answers while it has the region open, which it does not when zoomed out.
     */
    fun xaeroGapsIn(dimension: String): LongOpenHashSet = gaps.gapsIn(dimension)


    private val layouts = ConcurrentHashMap<String, BlueMap.MapLayout>()
    private val layoutAskedAt = HashMap<String, Long>()

    /** How [map] lays out its tiles, or null while that is still being asked (retried each minute). */
    fun layout(map: String): BlueMap.MapLayout? {
        layouts[map]?.let { return it }
        val now = System.currentTimeMillis()
        val asked = layoutAskedAt[map]
        if (asked == null || now - asked > 60_000L) {
            layoutAskedAt[map] = now
            workers.execute {
                try {
                    layouts[map] = blueMap.layout(map)
                } catch (e: Exception) {
                    Log.warn("Could not read BlueMap's settings for map '{}' at {}: {}", map, blueMap.base, e.toString())
                }
            }
        }
        return null
    }

    /** What maps of a dimension with a ceiling show ([MapLayers.View]), remembered from last time until BlueMap is asked again. */
    private val views = ConcurrentHashMap<String, MapLayers.View>()
    private val viewsFile = folder.resolve("map-views.properties")
    private val viewAskedAt = HashMap<String, Long>()

    init {
        try {
            if (Files.exists(viewsFile)) {
                val saved = Properties().apply { Files.newBufferedReader(viewsFile).use(::load) }
                for (map in saved.stringPropertyNames()) {
                    runCatching { MapLayers.View.valueOf(saved.getProperty(map)) }.getOrNull()?.let { views[map] = it }
                }
            }
        } catch (e: Exception) {
            Log.warn("Could not read {}: {}", viewsFile, e.toString())
        }
    }

    /**
     * Whether [map] shows the roof or the inside of a dimension with a ceiling, or null while that
     * is still unknown. Asked of BlueMap once a session (the server may have changed it), from the
     * heights in the lowres tiles around where its web viewer opens; retried each minute until it
     * answers.
     */
    fun view(map: String): MapLayers.View? {
        val now = System.currentTimeMillis()
        val asked = viewAskedAt[map]
        val known = views[map]
        if (asked == null || (known == null && now - asked > 60_000L)) {
            viewAskedAt[map] = now
            workers.execute { askView(map, known) }
        }
        return known
    }

    private fun askView(map: String, known: MapLayers.View?) {
        try {
            val layout = blueMap.layout(map)
            val tileBlocks = layout.tileBlocks(1)
            val (x, z) = blueMap.startPosition(map) ?: (0 to 0)
            val centreX = Math.floorDiv(x, tileBlocks)
            val centreZ = Math.floorDiv(z, tileBlocks)
            for (ring in 0..VIEW_RINGS) {
                for (dx in -ring..ring) for (dz in -ring..ring) {
                    if (maxOf(Math.abs(dx), Math.abs(dz)) != ring) continue
                    val bytes = blueMap.tile(map, 1, centreX + dx, centreZ + dz) ?: continue
                    val view = MapLayers.viewOf(TilePixels.heights(bytes, layout.tileSize)) ?: continue
                    views[map] = view
                    if (view != known) {
                        Log.info("BlueMap map '{}' shows the {}", map, if (view == MapLayers.View.ROOF) "roof" else "inside")
                        io.execute { saveViews() }
                    }
                    return
                }
            }
            Log.info("BlueMap map '{}' has nothing near where it opens to tell whether it shows the roof or the inside", map)
        } catch (e: Exception) {
            Log.warn("Could not ask BlueMap what map '{}' shows: {}", map, e.toString())
        }
    }

    private fun saveViews() {
        try {
            val text = Properties().apply { views.forEach { (map, view) -> setProperty(map, view.name) } }
            val out = java.io.StringWriter().also { text.store(it, "What each BlueMap map of the Nether shows: ROOF or INSIDE") }
            SafeFiles.write(viewsFile, out.toString().toByteArray())
        } catch (e: Exception) {
            Log.warn("Could not save {}: {}", viewsFile, e.toString())
        }
    }

    private fun close() {
        MapPrep.forget()
        visits.save()
        gaps.save()
        unshowable.save()
        tiles.clear()
        seaFloor.close()
        markers.close()
        workers.shutdownNow()
        blueMap.close()
        io.shutdown() // not Now: let the last visit save finish
    }

    companion object {
        /** Rings of lowres tiles around where a map opens looked at for [view]. */
        private const val VIEW_RINGS = 2

        fun chunkKey(chunkX: Int, chunkZ: Int): Long = (chunkX.toLong() and 0xFFFFFFFFL) or (chunkZ.toLong() shl 32)

        var current: Session? = null
            private set

        fun start(address: String?) {
            end()
            val server = Config.serverFor(address)
            if (server == null) return
            current = Session(server, folderFor(server.addresses.first()))
        }

        private val UNSAFE = Regex("[^a-z0-9._-]")

        /** This mod's folder for the server at [address], whether or not it has a BlueMap. */
        fun folderFor(address: String): Path {
            val name = Config.normalise(address).replace(UNSAFE, "_")
            return Minecraft.getInstance().gameDirectory.toPath().resolve("skysmapexposer").resolve(name)
        }

        fun end() {
            current?.close()
            current = null
        }

        private fun daemon(name: String): ThreadFactory = ThreadFactory { task ->
            Thread(task, name).apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }
    }
}
