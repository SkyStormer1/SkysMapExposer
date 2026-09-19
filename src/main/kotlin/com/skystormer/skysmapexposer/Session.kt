package com.skystormer.skysmapexposer

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import net.minecraft.client.Minecraft
import java.nio.file.Path
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
    private val workers: ExecutorService = Executors.newFixedThreadPool(4, daemon("SkysMapExposer fetch"))

    val blueMap = BlueMap(server.url)
    val visits = VisitLog(folder, io)
    val regionAges = RegionAges(io)
    val tiles = TileStore(blueMap, folder.resolve("tiles"), workers)
    val markers = Markers(blueMap, workers)

    private val xaeroGaps = HashMap<String, LongOpenHashSet>()

    /**
     * Chunks Xaero was last seen to have nothing for, per dimension. Remembered because Xaero only
     * answers while it has the region open, which it does not when zoomed out.
     */
    fun xaeroGapsIn(dimension: String): LongOpenHashSet = xaeroGaps.getOrPut(dimension) { LongOpenHashSet() }

    /** The chunk has just been loaded, so Xaero is about to map it. */
    fun forgetGap(dimension: String, chunkX: Int, chunkZ: Int) {
        xaeroGaps[dimension]?.remove(chunkKey(chunkX, chunkZ))
    }

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

    private fun close() {
        visits.save()
        tiles.clear()
        markers.close()
        workers.shutdownNow()
        blueMap.close()
        io.shutdown() // not Now: let the last visit save finish
    }

    companion object {
        fun chunkKey(chunkX: Int, chunkZ: Int): Long = (chunkX.toLong() and 0xFFFFFFFFL) or (chunkZ.toLong() shl 32)

        var current: Session? = null
            private set

        fun start(address: String?) {
            end()
            val server = Config.serverFor(address)
            if (server == null) return
            val name = Config.normalise(server.addresses.first()).replace(Regex("[^a-z0-9._-]"), "_")
            val folder = Minecraft.getInstance().gameDirectory.toPath().resolve("skysmapexposer").resolve(name)
            current = Session(server, folder)
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
