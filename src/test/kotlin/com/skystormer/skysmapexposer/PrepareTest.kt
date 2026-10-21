package com.skystormer.skysmapexposer

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/** The whole-world scan, background preparation's order and skipping, and the tile cache on disk. */
class PrepareTest {

    /** A BlueMap tile [size] blocks a side, with something everywhere. */
    private fun png(size: Int = 4): ByteArray {
        val image = BufferedImage(size + 1, (size + 1) * 2, BufferedImage.TYPE_INT_ARGB)
        for (z in 0..size) for (x in 0..size) image.setRGB(x, z, 0xFF336699.toInt())
        for (z in size + 1 until (size + 1) * 2) for (x in 0..size) image.setRGB(x, z, 0xFF000040.toInt())
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    // Coarsest tiles 4 pixels of 25 blocks: 100 blocks a side, over finest tiles 4 blocks a side.
    private val layout = BlueMap.MapLayout(tileSize = 4, lodFactor = 5, lodCount = 3)

    @Test
    fun `download everything reaches past the old four tiles, and stops at an empty ring`() {
        val asked = ArrayList<Pair<Int, Int>>()
        val has = { x: Int, z: Int -> maxOf(Math.abs(x), Math.abs(z)) <= 7 }
        val scan = CoarseScan.scan(layout, 0, 0, { false }) { x, z -> asked.add(x to z); if (has(x, z)) png() else null }!!
        // Rings 0 to 7 have something, ring 8 nothing: 9 rings read, and no further.
        assertEquals(9, scan.rings)
        assertTrue(asked.none { (x, z) -> maxOf(Math.abs(x), Math.abs(z)) > 8 })
        assertTrue((7 * 25 to 0) in scan.finest, "the far edge was not found")
        assertFalse(scan.limited)

        // A small world is still looked around a few rings out, for islands.
        val small = CoarseScan.scan(layout, 0, 0, { false }) { x, z -> if (x == 0 && z == 0) png() else null }!!
        assertEquals(CoarseScan.MIN_RINGS, small.rings)
        assertEquals(25 * 25, small.finest.size)

        // An endless world stops at the limit and says so.
        assertTrue(CoarseScan.scan(layout, 0, 0, { false }) { _, _ -> png() }!!.limited)
        assertEquals(8 * 3, CoarseScan.ring(3).size)
        assertEquals(null, CoarseScan.scan(layout, 0, 0, { true }) { _, _ -> png() })
    }

    @Test
    fun `preparation goes from the middle outwards`() {
        val tiles = listOf(5 to 5, 0 to 0, -1 to 0, 3 to -2, 10 to 0, 1 to 1)
        assertEquals(listOf(0 to 0, -1 to 0, 1 to 1, 3 to -2, 5 to 5, 10 to 0), MapPrep.order(tiles, 0, 0))
        assertEquals(10 to 0, MapPrep.order(tiles, 10, 0).first())
    }

    private lateinit var server: HttpServer
    private val tileRequests = AtomicInteger()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val body = when {
                path.endsWith("settings.json") -> """{"lowres":{"tileSize":[4,4],"lodFactor":5,"lodCount":3}}""".toByteArray()
                path.contains("/tiles/1/") -> png().also { tileRequests.incrementAndGet() }
                else -> null
            }
            if (body == null) exchange.sendResponseHeaders(204, -1)
            else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun blueMap() = BlueMap("http://127.0.0.1:${server.address.port}/")

    @Test
    fun `preparing skips what is on disk, and carries on after a restart`(@TempDir folder: Path) {
        val workers = Executors.newSingleThreadExecutor()
        try {
            val keys = (0 until 6).map { TileStore.Key("world", 1, it, 0) }
            val first = TileStore(blueMap(), folder, workers)
            keys.take(3).forEach { assertTrue(first.prepare(it, 1, 4)) }
            assertEquals(3, tileRequests.get())

            // Joined again: the first three are on disk and up to date.
            val again = TileStore(blueMap(), folder, workers)
            assertEquals(listOf(false, false, false, true, true, true), keys.map { again.prepare(it, 1, 4) })
            assertEquals(6, tileRequests.get())
            assertTrue(Files.exists(folder.resolve("world/1/5_0.smet")))
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `the cache is tidied on joining and can be cleared`(@TempDir folder: Path) {
        val here = folder.resolve("play.example.net/tiles")
        val tiles = here.resolve("world/1")
        Files.createDirectories(tiles)
        Files.write(tiles.resolve("0_0.smet"), ByteArray(1000))
        Files.write(tiles.resolve("0_0.meta"), ByteArray(10))
        Files.write(tiles.resolve("1_0.png"), ByteArray(500))
        Files.write(tiles.resolve("1_0.smet.123.tmp"), ByteArray(99))
        val other = folder.resolve("other.example.net/tiles/world/1")
        Files.createDirectories(other)
        Files.write(other.resolve("0_0.smet"), ByteArray(2000))

        val size = TileCache.tidy(here)
        assertEquals(2, size.tiles)
        assertEquals(1510, size.bytes)
        assertFalse(Files.exists(tiles.resolve("1_0.smet.123.tmp")))
        assertEquals(3, TileCache.measureAll(folder).tiles)
        assertEquals(3510, TileCache.measureAll(folder).bytes)

        TileCache.clear(here)
        assertFalse(Files.exists(here))
        assertEquals(0, TileCache.measure(here).tiles)
        assertEquals(1, TileCache.measureAll(folder).tiles)
    }
}
