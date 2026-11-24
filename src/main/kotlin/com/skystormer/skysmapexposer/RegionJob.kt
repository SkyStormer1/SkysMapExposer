package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What every job that changes a map region by region has in common, a download, clear or shared
 * terrain file into Xaero's map (`xaero.BlueMapDownload`) or JourneyMap's (`journeymap.JourneyMapEdits`):
 *  - regions are found on a thread of their own ([findOff]), from BlueMap ([findOnBlueMap]) or a
 *    file, and put in order nearest to you first ([nearestFirst]);
 *  - they are handed over a few at a time ([hand]) to be written on the client thread, in whatever
 *    way that map writes ([T] is one region's work, as that map needs it);
 *  - it can be cancelled, counts the regions done, keeps those it had to leave out ([skipped]) and
 *    names them at the end ([endLine]).
 */
open class RegionJob<T>(
    /** "Download from BlueMap", "Clear of JourneyMap's map", for the messages and the log. */
    val title: String,
    val dimensionId: String,
    /** How many regions may wait for the client thread at once: a region's pixels take memory. */
    capacity: Int,
) {
    /** Where you were when it started, which the regions are ordered around. */
    val centreX: Int
    val centreZ: Int

    init {
        val player = Minecraft.getInstance().player
        centreX = player?.blockX ?: 0
        centreZ = player?.blockZ ?: 0
        last = this
    }

    val name: String = Ui.dimensionName(dimensionId) ?: dimensionId
    val startedAt = System.currentTimeMillis()
    @Volatile var cancelled = false
    @Volatile var total = 0
    /** True until the regions to look at have been found and counted. */
    @Volatile var finding = true
    /** Set once the finding thread has handed over every region it will, or stopped. */
    @Volatile var found = false
    /** Set by the finding thread too, when it fails or is cancelled before writing. */
    @Volatile var endedAt = 0L
    val done = AtomicInteger()
    val ready = LinkedBlockingQueue<T>(capacity)
    val skipped = SkippedRegions()

    /** [regions] in order of distance from where you were when it started, nearest first. */
    fun nearestFirst(regions: Collection<Pair<Int, Int>>): List<Pair<Int, Int>> = regions.sortedBy { (rx, rz) ->
        val dx = rx * BlueMapPicture.REGION + 256L - centreX
        val dz = rz * BlueMapPicture.REGION + 256L - centreZ
        dx * dx + dz * dz
    }

    /** Hands one region's work to the client thread, waiting while it is behind; false once cancelled. Finding thread. */
    fun hand(work: T): Boolean {
        while (!ready.offer(work, 200, TimeUnit.MILLISECONDS)) if (cancelled) return false
        return true
    }

    /**
     * Runs [find] on a thread of its own. If it throws, [failed] is told what to say; either way
     * [found] is set at the end, then [after] runs.
     */
    fun findOff(thread: String, failed: (String) -> Unit, after: () -> Unit = {}, find: () -> Unit) = background(thread) {
        try {
            find()
        } catch (e: Throwable) {
            Log.error("$title failed", e)
            failed("$title stopped: ${e.message ?: e.javaClass.simpleName}. The log says more.")
        } finally {
            finding = false
            found = true
            after()
        }
    }

    /** What [findOnBlueMap] found: BlueMap's finest tiles worth reading, and the regions over them, nearest first. */
    class Found(val tileSize: Int, val finest: Set<Pair<Int, Int>>, val regions: List<Pair<Int, Int>>)

    /**
     * Asks BlueMap what it has of map [mapId]: every finest tile under [area], or, with no area, the
     * tiles its coarsest level shows anything in. Null if cancelled part way. Finding thread.
     */
    fun findOnBlueMap(blueMap: BlueMap, mapId: String, area: BlockArea?): Found? {
        val layout = blueMap.layout(mapId)
        val size = layout.tileSize
        val finest: Set<Pair<Int, Int>>
        val regions: Collection<Pair<Int, Int>>
        if (area != null) {
            finest = BlueMapPicture.tilesOver(area.minX, area.minZ, area.maxX, area.maxZ, size).toSet()
            regions = BlueMapPicture.regionsOver(area.minX, area.minZ, area.maxX, area.maxZ)
        } else {
            // The coarsest level is a few tiles for a whole world, and says where the finest ones
            // have anything, out to wherever the world ends.
            val scan = CoarseScan.scan(layout, centreX, centreZ, { cancelled }) { tx, tz -> blueMap.tile(mapId, layout.lodCount, tx, tz) } ?: return null
            Log.info("{}: BlueMap map '{}': {}", title, mapId, scan)
            finest = scan.finest
            val found = HashSet<Pair<Int, Int>>()
            for ((tx, tz) in finest) found.addAll(BlueMapPicture.regionsOver(tx * size, tz * size, tx * size + size - 1, tz * size + size - 1))
            regions = found
        }
        return Found(size, finest, nearestFirst(regions))
    }

    /** [endedAt] less [startedAt], as "4.2s", "3m 10s" or "1h 5m". */
    fun took(): String {
        val millis = endedAt - startedAt
        val seconds = millis / 1000
        return when {
            seconds >= 3600 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
            seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${"%.1f".format(millis / 1000.0)}s"
        }
    }

    /** The end message for chat: [head], the regions skipped and a button to list them all, then [button]. */
    fun endLine(head: String, button: Component?): Component {
        val line = Component.literal(head).append("  ")
        val count = skipped.size
        if (count > 0) {
            line.append("\n" + skipped.summary() + "  ")
            if (count > SkippedRegions.SHOWN) {
                line.append(ChatButton.of("[List all $count]", "/mapexposer skipped", skipped.all().take(40).joinToString("\n")))
                line.append("  ")
            }
        }
        button?.let(line::append)
        return line
    }

    companion object {
        /** The last job started in either map, for `/mapexposer skipped`; null after leaving a server. */
        @Volatile
        var last: RegionJob<*>? = null
    }
}

/** A block rectangle, inclusive at both ends. */
class BlockArea(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int) {
    fun contains(x: Int, z: Int) = x in minX..maxX && z in minZ..maxZ

    companion object {
        /** The blocks of chunks [area]. */
        fun ofChunks(area: ShareFormat.Area) = BlockArea(area.left * 16, area.top * 16, area.right * 16 + 15, area.bottom * 16 + 15)
    }
}
