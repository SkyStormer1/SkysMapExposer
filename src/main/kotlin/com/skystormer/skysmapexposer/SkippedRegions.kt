package com.skystormer.skysmapexposer

/**
 * The regions a download, clear or terrain file import left out, and why, so the end message can
 * name them rather than point at the log. Each region is named once, with its last reason.
 */
class SkippedRegions {

    private val regions = LinkedHashMap<Pair<Int, Int>, String>()

    val size get() = regions.size

    fun add(x: Int, z: Int, why: String) {
        regions.remove(x to z)
        regions[x to z] = why
    }

    /** One region, in region and block coordinates, and why. */
    private fun line(region: Pair<Int, Int>, why: String) =
        "${region.first},${region.second} at ${region.first * 512}, ${region.second * 512} ($why)"

    /** "2 regions skipped: -3,5 at -1536, 2560 (…), …", the first [SHOWN] of them; empty for none. */
    fun summary(): String {
        if (regions.isEmpty()) return ""
        val named = regions.entries.take(SHOWN).joinToString(", ") { line(it.key, it.value) }
        val more = regions.size - SHOWN
        return "${regions.size} region${if (regions.size == 1) "" else "s"} skipped: $named" +
            (if (more > 0) " and $more more" else "") +
            (if (NOT_LOADED in regions.values) ". $ADVICE" else "")
    }

    /** Every one, a line each. */
    fun all(): List<String> = regions.entries.map { line(it.key, it.value) }

    companion object {
        const val SHOWN = 5
        const val NOT_LOADED = "Xaero didn't load it"
        const val ADVICE = "Open the world map there and try again; if it keeps happening, the region file may be damaged"

        fun notBackedUp(why: String) = "couldn't back it up: $why"
    }
}

/** Runs a tick's work, turning an error into [stop] instead of a crash. */
object TickGuard {
    fun run(what: String, stop: (String) -> Unit, work: () -> Unit) {
        try {
            work()
        } catch (e: Throwable) {
            Log.error("$what hit an error and stopped", e)
            try {
                stop("$what stopped after an error: ${e.message ?: e.javaClass.simpleName}. What was written before is kept; the log says more.")
            } catch (again: Throwable) {
                Log.error("Could not stop $what cleanly", again)
            }
        }
    }
}
