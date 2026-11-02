package com.skystormer.skysmapexposer

/**
 * Which tiles to let go of ([TileStore.trim]). Both maps draw from one store, so closing the world
 * map must not throw its tiles away (the minimap alone draws a few): a tile goes only once it has
 * not been drawn for [IDLE_MS], or, while the textures held are over the memory budget, the least
 * recently drawn first. A tile on screen is never dropped, nor one being fetched; if the tiles on
 * screen alone are over the budget, [Result.inViewOverBudget] says so and they are drawn coarser.
 */
object Eviction {
    const val IDLE_MS = 5 * 60_000L
    /** Drawn this recently counts as on screen: every frame draws what is on screen. */
    const val IN_VIEW_MS = 2_000L

    class Entry<K>(val key: K, val bytes: Long, val lastUsed: Long, val busy: Boolean)

    class Result<K>(val drop: List<K>, val kept: Long, val inViewOverBudget: Boolean)

    fun <K> choose(entries: Collection<Entry<K>>, now: Long, budget: Long): Result<K> {
        val drop = ArrayList<K>()
        val keep = ArrayList<Entry<K>>()
        var total = 0L
        for (entry in entries) {
            if (!entry.busy && now - entry.lastUsed > IDLE_MS) drop.add(entry.key)
            else {
                keep.add(entry)
                total += entry.bytes
            }
        }
        if (total > budget) {
            for (entry in keep.sortedBy { it.lastUsed }) {
                if (total <= budget) break
                if (entry.busy || entry.bytes == 0L || now - entry.lastUsed <= IN_VIEW_MS) continue
                drop.add(entry.key)
                total -= entry.bytes
            }
        }
        return Result(drop, total, total > budget)
    }

    /**
     * How many zoom factors coarser to draw: one more while the tiles on screen are over the
     * budget, one fewer once they are well under it.
     */
    fun coarser(current: Int, inViewOverBudget: Boolean, kept: Long, budget: Long): Int = when {
        inViewOverBudget -> minOf(current + 1, MAX_COARSER)
        kept < budget * 6 / 10 -> maxOf(current - 1, 0)
        else -> current
    }

    const val MAX_COARSER = 4
}

/**
 * Keeps the game's frame rate ahead of the overlay. A rolling average of frame time: while frames
 * are slow (over [SLOW_NANOS], about 40 fps) the textures uploaded per frame are halved, down to
 * one, and background work is paused ([paused]); once they are fine again uploads step back up one
 * at a time. With little Java heap left ([LOW_HEAP]), the memory budget is halved ([budgetBytes]).
 */
class FramePacer(
    private val clock: () -> Long = System::nanoTime,
    /** The share of the Java heap still free, 0 to 1. */
    private val freeHeap: () -> Double = ::freeHeapShare,
) {
    var uploads = MAX_UPLOADS
        private set
    var slow = false
        private set
    var heapLow = false
        private set

    /** Whether background work should wait: the game is busy or short of memory. */
    val paused: Boolean get() = slow || heapLow

    private var last = 0L
    private var average = 16_000_000.0
    private var judgedAt = 0L

    /** Called by every map drawn; two draws in one frame count once. */
    fun frame() {
        val now = clock()
        if (judgedAt == 0L) judgedAt = now
        if (last != 0L) {
            val took = now - last
            if (took < SAME_FRAME_NANOS) return
            // A long gap is the map having been closed, not a slow frame.
            if (took < PAUSE_NANOS) average = average * 0.9 + took * 0.1
        }
        last = now
        if (now - judgedAt >= JUDGE_NANOS) {
            judgedAt = now
            slow = average > SLOW_NANOS
            uploads = when {
                slow -> maxOf(1, uploads / 2)
                average < FINE_NANOS -> minOf(MAX_UPLOADS, uploads + 1)
                else -> uploads
            }
            heapLow = freeHeap() < LOW_HEAP
        }
    }

    /** The texture memory budget, halved while the heap is low. */
    fun budgetBytes(): Long = Config.textureBudgetMb * 1024L * 1024L / (if (heapLow) 2 else 1)

    companion object {
        const val MAX_UPLOADS = 8
        const val SLOW_NANOS = 25_000_000L
        const val FINE_NANOS = 20_000_000L
        const val LOW_HEAP = 0.15
        private const val SAME_FRAME_NANOS = 1_000_000L
        private const val PAUSE_NANOS = 1_000_000_000L
        private const val JUDGE_NANOS = 1_000_000_000L

        /** The one the maps use. */
        val game = FramePacer()

        fun freeHeapShare(): Double {
            val runtime = Runtime.getRuntime()
            val used = runtime.totalMemory() - runtime.freeMemory()
            return (runtime.maxMemory() - used).toDouble() / runtime.maxMemory()
        }
    }
}
