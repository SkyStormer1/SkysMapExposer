package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.terrain.BuiltChunk
import com.skystormer.skysmapexposer.terrain.FillProgress
import com.skystormer.skysmapexposer.terrain.FillTarget
import net.minecraft.world.level.ChunkPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The terrain fill's bookkeeping: tiles skipped rather than ending it, and mods dropping out. */
class TerrainFillTest {

    /** A mod that takes chunks until [closed], then fails as Bobby does once its storage is shut. */
    private class Target(override val name: String) : FillTarget {
        var closed = false
        var taken = 0
        override fun wants(pos: ChunkPos): Boolean {
            if (closed) throw IllegalStateException("storage closed")
            return true
        }
        override fun accept(chunk: BuiltChunk) {
            if (closed) throw IllegalStateException("storage closed")
            taken++
        }
    }

    @Test
    fun `one unreadable tile is skipped and reported, and the fill goes on`() {
        val progress = FillProgress(listOf(Target("Voxy")), "Overworld")
        progress.batch(4, listOf("java.io.IOException: unexpected end of file"))
        progress.batch(4, emptyList())
        progress.batch(4, emptyList())
        progress.batch(4, emptyList())
        assertFalse(progress.hopeless)
        assertEquals(1, progress.unreadable)
        assertEquals(", 1 tile couldn't be read (see the log)", progress.afterword())
    }

    @Test
    fun `when every tile of the first batches fails, the fill gives up`() {
        val progress = FillProgress(listOf(Target("Voxy")), "Overworld")
        repeat(FillProgress.EARLY_BATCHES - 1) { progress.batch(2, listOf("HTTP 500", "HTTP 500")) }
        assertFalse(progress.hopeless, "gave up before the first few batches were in")
        progress.batch(2, listOf("HTTP 500", "HTTP 500"))
        assertTrue(progress.hopeless)
        // Batches with nothing to download do not count either way.
        val quiet = FillProgress(listOf(Target("Voxy")), "Overworld")
        repeat(5) { quiet.batch(0, emptyList()) }
        assertFalse(quiet.hopeless)
    }

    @Test
    fun `going through a portal drops Bobby and Distant Horizons with a note, and Voxy carries on`() {
        val bobby = Target("Bobby")
        val voxy = Target("Voxy")
        val dh = Target("Distant Horizons")
        val progress = FillProgress(listOf(bobby, voxy, dh), "Overworld")
        val pos = ChunkPos(0, 0)
        for (t in progress.active.toList()) assertEquals(true, progress.use(t, "Overworld") { t.wants(pos) })

        bobby.closed = true
        dh.closed = true
        val wanting = progress.active.toList().filter { t -> progress.use(t, "Nether") { t.wants(pos) } == true }
        assertEquals(listOf(voxy), wanting)
        assertEquals(listOf(voxy), progress.active)
        assertEquals(listOf("Bobby: stopped when you left the Overworld", "Distant Horizons: stopped when you left the Overworld"), progress.notes)
        // A dropped mod is not asked again.
        assertEquals(null, progress.use(bobby, "Nether") { bobby.wants(pos) })
        assertTrue(progress.afterword().contains("Bobby: stopped when you left the Overworld"))
    }
}
