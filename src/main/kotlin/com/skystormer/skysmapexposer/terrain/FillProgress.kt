package com.skystormer.skysmapexposer.terrain

/**
 * What a terrain fill has met on its way: BlueMap 3D tiles that could not be downloaded or read
 * (skipped, never the end of the fill), and mods that could no longer take chunks (dropped, and the
 * fill goes on with the others). No Minecraft classes, so it can be tested.
 *
 * @param startedIn the name of the dimension the fill started in, for the notes.
 */
class FillProgress(targets: List<FillTarget>, private val startedIn: String) {

    /** The mods still being filled. */
    val active: MutableList<FillTarget> = targets.toMutableList()

    /** Why each dropped mod was dropped, e.g. "Bobby: stopped when you left the Overworld". */
    val notes = ArrayList<String>()

    var unreadable = 0
        private set

    private val reasons = ArrayList<String>()
    private var earlyBatches = 0
    private var earlyAsked = 0
    private var earlyFailed = 0

    /** One batch's tiles: [asked] were tried, and these [failed] (one reason each). */
    fun batch(asked: Int, failed: List<String>) {
        unreadable += failed.size
        for (reason in failed) if (reasons.size < SHOWN_REASONS) reasons.add(reason)
        if (asked > 0 && earlyBatches < EARLY_BATCHES) {
            earlyBatches++
            earlyAsked += asked
            earlyFailed += failed.size
        }
    }

    /**
     * Whether every tile of the first few batches failed: then the server or its format is the
     * problem, not one tile, and going on would only fail the same way.
     */
    val hopeless: Boolean get() = earlyBatches >= EARLY_BATCHES && earlyFailed == earlyAsked

    /** The first few reasons, for the log. */
    fun firstReasons(): List<String> = reasons.toList()

    /**
     * Runs [action] for [target], unless it has been dropped. If it fails, the mod is dropped with
     * a note: after a change of dimension its storage for this one is usually closed. [nowIn] is
     * the name of the dimension you are in now.
     */
    fun <T> use(target: FillTarget, nowIn: String?, action: () -> T): T? {
        if (target !in active) return null
        return try {
            action()
        } catch (e: Exception) {
            active.remove(target)
            notes.add(
                if (nowIn != null && nowIn != startedIn) "${target.name}: stopped when you left the $startedIn"
                else "${target.name}: stopped (${e.message ?: e.javaClass.simpleName})",
            )
            null
        }
    }

    /** Added to the end message: ", 12 tiles couldn't be read (see the log)", and the dropped mods. */
    fun afterword(): String =
        (if (unreadable > 0) ", $unreadable tile${if (unreadable == 1) "" else "s"} couldn't be read (see the log)" else "") +
            (if (notes.isNotEmpty()) ". " + notes.joinToString("; ") else "")

    companion object {
        const val EARLY_BATCHES = 3
        private const val SHOWN_REASONS = 5
    }
}
