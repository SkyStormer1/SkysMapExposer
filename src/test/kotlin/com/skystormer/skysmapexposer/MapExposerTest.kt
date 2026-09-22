package com.skystormer.skysmapexposer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * Checks everything that does not need the game running.
 *
 * The BlueMap tests talk to a live server, given by the `BLUEMAP_URL` environment variable (with a
 * map called `world`), and are skipped without one.
 */
class MapExposerTest {

    private val url = System.getenv("BLUEMAP_URL") ?: ""

    @Test
    fun `tile paths split coordinates into one folder per digit`() {
        assertEquals("maps/world/tiles/1/x0/z0.png", BlueMap.tilePath("world", 1, 0, 0))
        assertEquals("maps/world/tiles/1/x-1/2/z3.png", BlueMap.tilePath("world", 1, -12, 3))
        assertEquals("maps/world/tiles/3/x1/0/z-1/0/5.png", BlueMap.tilePath("world", 3, 10, -105))
    }

    @Test
    fun `layout gives blocks per pixel and per tile at each level`() {
        val layout = BlueMap.MapLayout(tileSize = 500, lodFactor = 5, lodCount = 3)
        assertEquals(listOf(1, 5, 25), (1..3).map(layout::blocksPerPixel))
        assertEquals(listOf(500, 2500, 12500), (1..3).map(layout::tileBlocks))
    }

    @Test
    fun `server addresses match with or without the default port`() {
        Config.servers = listOf(Config.Server(listOf("play.example.com", "203.0.113.7"), "http://example.com/", emptyMap()))
        assertTrue(Config.serverFor("play.example.com") != null)
        assertTrue(Config.serverFor("Play.Example.com:25565") != null)
        assertTrue(Config.serverFor("203.0.113.7") != null)
        assertTrue(Config.serverFor("other.example.com") == null)
    }

    @Test
    fun `visit record survives a save and a reload`(@TempDir folder: Path) {
        val direct = java.util.concurrent.Executor { it.run() }
        val first = VisitLog(folder, direct)
        first.record("minecraft:overworld", -3, 7, 1000)
        first.record("minecraft:overworld", 1_000_000, -1_000_000, 2000)
        first.record("minecraft:the_nether", 0, 0, 3000)
        first.save()

        val second = VisitLog(folder, direct)
        assertEquals(1000, second.lastSeen("minecraft:overworld", -3, 7))
        assertEquals(2000, second.lastSeen("minecraft:overworld", 1_000_000, -1_000_000))
        assertEquals(0, second.lastSeen("minecraft:overworld", 7, -3))
        assertEquals(3000, second.lastSeen("minecraft:the_nether", 0, 0))
    }

    @Test
    fun `remembered Xaero gaps survive a save and a reload`(@TempDir folder: Path) {
        val direct = java.util.concurrent.Executor { it.run() }
        val first = GapStore(folder, direct)
        first.gapsIn("minecraft:overworld").add(Session.chunkKey(-5, 12))
        first.gapsIn("minecraft:overworld").add(Session.chunkKey(40_000, -40_000))
        first.gapsIn("minecraft:the_nether").add(Session.chunkKey(1, 1))
        first.save()

        val second = GapStore(folder, direct)
        assertTrue(second.gapsIn("minecraft:overworld").contains(Session.chunkKey(-5, 12)))
        assertTrue(second.gapsIn("minecraft:overworld").contains(Session.chunkKey(40_000, -40_000)))
        assertTrue(!second.gapsIn("minecraft:overworld").contains(Session.chunkKey(12, -5)))
        assertEquals(1, second.gapsIn("minecraft:the_nether").size)
    }

    @Test
    fun `reads the live server's map layout`() {
        val layout = reachable { BlueMap(url).layout("world") }
        assertEquals(500, layout.tileSize)
        assertTrue(layout.lodCount >= 1)
    }

    @Test
    fun `fetches, decodes, caches and dates a live tile`(@TempDir folder: Path) {
        reachable { BlueMap(url).layout("world") }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val key = TileStore.Key("world", 1, 0, 0)
            val first = TileStore(BlueMap(url), folder, workers).get(key, 1, 500, 0).awaitSettled()
            assertEquals(TileStore.State.READY, first.state)
            assertEquals(501, first.imageSize)
            val present = first.present!!
            assertTrue(present.cardinality() > 501 * 501 / 2, "only ${present.cardinality()} pixels have colour")

            // A second store reading the same folder gets the copy on disk, with the same date.
            val second = TileStore(BlueMap("http://127.0.0.1:1/"), folder, workers).get(key, 1, 500, 0).awaitSettled()
            assertEquals(TileStore.State.READY, second.state)
            assertEquals(first.contentMinute, second.contentMinute)
            assertEquals(present, second.present)

            val nothing = TileStore(BlueMap(url), folder, workers)
                .get(TileStore.Key("world", 1, 999, 0), 1, 500, 0).awaitSettled()
            assertEquals(TileStore.State.EMPTY, nothing.state)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `reads the live server's markers, world border and players`() {
        reachable { BlueMap(url).layout("world") }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val markers = Markers(BlueMap(url), workers)
            markers.of("world")
            markers.players("world")
            val deadline = System.currentTimeMillis() + 20_000
            var snapshot = markers.of("world")
            while (snapshot == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
                snapshot = markers.of("world")
            }
            assertTrue(snapshot != null, "markers never arrived")
            assertTrue(snapshot!!.points.isNotEmpty(), "no markers")
            val border = snapshot.outlines.firstOrNull { it.label == "World Border" }
            assertTrue(border != null, "no world border among ${snapshot.outlines.map { it.label }}")
            assertTrue(border!!.closed && border.points.size >= 8)
            assertEquals(0xFF, border.colour ushr 24)
            assertTrue(snapshot.points.none { it.label.contains('Â') }, "double-encoded labels left in")
            Thread.sleep(1500)
            // Only players in this map's own dimension: none are marked foreign.
            markers.players("world").forEach { assertTrue(it.label.isNotBlank()) }
        } finally {
            workers.shutdownNow()
        }
    }

    private fun TileStore.Tile.awaitSettled(): TileStore.Tile {
        val deadline = System.currentTimeMillis() + 30_000
        while (state == TileStore.State.LOADING && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return this
    }

    private fun <T> reachable(block: () -> T): T {
        assumeTrue(url.isNotBlank(), "set BLUEMAP_URL to run the live BlueMap tests")
        val result = runCatching(block)
        assumeTrue(result.isSuccess, "BlueMap server not reachable: ${result.exceptionOrNull()}")
        return result.getOrThrow()
    }
}
