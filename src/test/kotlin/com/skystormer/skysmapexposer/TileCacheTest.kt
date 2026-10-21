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
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/** BlueMap tiles in memory and on disk: bad replies, empty tiles, and what is let go of when. */
class TileCacheTest {

    /** Runs everything at once on the calling thread, so a load is finished when [TileStore.get] returns. */
    private class Direct : AbstractExecutorService() {
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() {}
        override fun shutdownNow() = mutableListOf<Runnable>()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    /** Never runs anything: what it is given stays on its way. */
    private class Never : AbstractExecutorService() {
        override fun execute(command: Runnable) {}
        override fun shutdown() {}
        override fun shutdownNow() = mutableListOf<Runnable>()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }

    /** With an [etag], a request that sends it back is answered 304. */
    private class Reply(val status: Int, val body: ByteArray = ByteArray(0), val etag: String? = null)

    private lateinit var server: HttpServer
    private val replies = HashMap<String, Reply>()
    private val tileRequests = AtomicInteger()
    private var checkMinutes = 0
    private var hardHours = 0
    private val plainRequests = AtomicInteger()

    private val key = TileStore.Key("world", 1, 0, 0)
    private val tilePath = "/" + BlueMap.tilePath("world", 1, 0, 0)
    private val settingsPath = "/maps/world/settings.json"

    @BeforeEach
    fun start() {
        checkMinutes = Config.tileCheckMinutes
        hardHours = Config.tileHardRefreshHours
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val asked = exchange.requestHeaders.getFirst("If-None-Match")
            if (path == tilePath) {
                tileRequests.incrementAndGet()
                if (asked == null) plainRequests.incrementAndGet()
            }
            val reply = synchronized(replies) { replies[path] } ?: Reply(404)
            if (reply.etag != null && asked == reply.etag) {
                exchange.sendResponseHeaders(304, -1)
                exchange.close()
                return@createContext
            }
            reply.etag?.let { exchange.responseHeaders.add("ETag", it) }
            exchange.sendResponseHeaders(reply.status, if (reply.body.isEmpty()) -1 else reply.body.size.toLong())
            if (reply.body.isNotEmpty()) exchange.responseBody.use { it.write(reply.body) }
            exchange.close()
        }
        server.start()
        serve(settingsPath, Reply(200, """{"lowres":{"tileSize":[4,4],"lodFactor":5,"lodCount":3}}""".toByteArray()))
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        Config.tileCheckMinutes = checkMinutes
        Config.tileHardRefreshHours = hardHours
    }

    private fun serve(path: String, reply: Reply) = synchronized(replies) { replies[path] = reply }

    private fun blueMap() = BlueMap("http://127.0.0.1:${server.address.port}/")

    /** A BlueMap tile 4 blocks a side: 5 × 10 pixels, colours over heights. */
    private fun png(colour: Int = 0xFF336699.toInt()): ByteArray {
        val image = BufferedImage(5, 10, BufferedImage.TYPE_INT_ARGB)
        for (z in 0 until 5) for (x in 0 until 5) image.setRGB(x, z, colour)
        for (z in 5 until 10) for (x in 0 until 5) image.setRGB(x, z, 0xFF000040.toInt())
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun picture(folder: Path) = folder.resolve("world/1/0_0.smet")

    @Test
    fun `a junk 200 reply is not cached, and a later good one loads`(@TempDir folder: Path) {
        serve(tilePath, Reply(200, "<html>Cloudflare says hello</html>".toByteArray()))
        val first = TileStore(blueMap(), folder, Direct()).get(key, 1, 4)
        assertEquals(TileStore.State.FAILED, first.state)
        assertFalse(Files.exists(picture(folder)))

        serve(tilePath, Reply(200, png()))
        val second = TileStore(blueMap(), folder, Direct()).get(key, 1, 4)
        assertEquals(TileStore.State.READY, second.state)
        assertTrue(Files.exists(picture(folder)))
    }

    @Test
    fun `a saved picture that no longer reads is fetched again`(@TempDir folder: Path) {
        serve(tilePath, Reply(200, png()))
        TileStore(blueMap(), folder, Direct()).get(key, 1, 4)
        Files.writeString(picture(folder), "half a file")
        val requests = tileRequests.get()
        val tile = TileStore(blueMap(), folder, Direct()).get(key, 1, 4)
        assertEquals(TileStore.State.READY, tile.state)
        assertEquals(requests + 1, tileRequests.get())
        assertTrue(Files.size(picture(folder)) > 20)
    }

    @Test
    fun `a tile BlueMap empties loses its picture on the second check, and is then left alone`(@TempDir folder: Path) {
        serve(tilePath, Reply(200, png()))
        Config.tileCheckMinutes = 0
        val store = TileStore(blueMap(), folder, Direct())
        val tile = store.get(key, 1, 4)
        assertEquals(TileStore.State.READY, tile.state)

        serve(tilePath, Reply(204))
        Thread.sleep(5)
        store.get(key, 1, 4) // first "empty": the picture stays
        assertEquals(TileStore.State.READY, tile.state)
        assertTrue(Files.exists(picture(folder)))
        Thread.sleep(5)
        store.get(key, 1, 4) // second: it goes
        assertEquals(TileStore.State.EMPTY, tile.state)
        assertFalse(Files.exists(picture(folder)))

        Config.tileCheckMinutes = 15
        val requests = tileRequests.get()
        repeat(20) { store.get(key, 1, 4) }
        assertEquals(requests, tileRequests.get(), "an empty tile is asked about every frame")
    }

    @Test
    fun `a 404 is only believed while the map's settings load`(@TempDir folder: Path) {
        serve(tilePath, Reply(200, png()))
        Config.tileCheckMinutes = 0
        val store = TileStore(blueMap(), folder, Direct())
        val tile = store.get(key, 1, 4)

        // BlueMap down behind a proxy: every tile 404, and its settings too.
        serve(tilePath, Reply(404))
        serve(settingsPath, Reply(502))
        repeat(3) { Thread.sleep(5); store.get(key, 1, 4) }
        assertEquals(TileStore.State.READY, tile.state)
        assertTrue(Files.exists(picture(folder)))

        // A real 404 with BlueMap up: empty, from a fresh load.
        val fresh = TileStore(blueMap(), folder.resolve("other"), Direct())
        serve(settingsPath, Reply(200, """{"lowres":{"tileSize":[4,4],"lodFactor":5,"lodCount":3}}""".toByteArray()))
        assertEquals(TileStore.State.EMPTY, fresh.get(key, 1, 4).state)
    }

    @Test
    fun `a failed check still counts as checked`(@TempDir folder: Path) {
        serve(tilePath, Reply(200, png()))
        Config.tileCheckMinutes = 1
        val store = TileStore(blueMap(), folder, Direct())
        val tile = store.get(key, 1, 4)
        tile.checkedAt = 0 // due for a check
        serve(tilePath, Reply(500))
        store.get(key, 1, 4)
        val requests = tileRequests.get()
        repeat(20) { store.get(key, 1, 4) }
        assertEquals(requests, tileRequests.get())
        assertEquals(TileStore.State.READY, tile.state)
    }

    @Test
    fun `a 304 downloads nothing and keeps the tile, and a changed tile is rebuilt`(@TempDir folder: Path) {
        serve(tilePath, Reply(200, png(), etag = "\"one\""))
        Config.tileCheckMinutes = 0
        val store = TileStore(blueMap(), folder, Direct())
        val tile = store.get(key, 1, 4)
        val built = Files.readAllBytes(picture(folder))
        val plain = plainRequests.get()
        repeat(3) { store.get(key, 1, 4) }
        assertEquals(plain, plainRequests.get(), "a check downloaded the tile")
        assertEquals(TileStore.State.READY, tile.state)
        assertTrue(built.contentEquals(Files.readAllBytes(picture(folder))))

        serve(tilePath, Reply(200, png(0xFF993366.toInt()), etag = "\"two\""))
        store.get(key, 1, 4)
        assertFalse(built.contentEquals(Files.readAllBytes(picture(folder))), "the change was not taken")
        // Flat ground at height 64 (0x40), lit as Xaero lights it.
        val lit = TilePixels.xaeroLight(64, 64, 64)
        val expected = listOf(0x99, 0x33, 0x66).map { (it * lit).toInt().coerceAtMost(255) }.let { (r, g, b) -> (r shl 16) or (g shl 8) or b }
        assertEquals(expected, TileFile.pixels(picture(folder), 1)!![0] and 0xFFFFFF)
    }

    @Test
    fun `the hard refresh downloads even when checks say unchanged`(@TempDir folder: Path) {
        serve(tilePath, Reply(200, png(), etag = "\"one\""))
        Config.tileCheckMinutes = 0
        val store = TileStore(blueMap(), folder, Direct())
        val tile = store.get(key, 1, 4)
        val plain = plainRequests.get()
        tile.hardAt = System.currentTimeMillis() - Config.tileHardRefreshHours * 3_600_000L - 1
        store.get(key, 1, 4)
        assertEquals(plain + 1, plainRequests.get())
        // The refresh button: whole downloads again, as they are drawn, for tiles already in memory.
        store.refetchBefore = System.currentTimeMillis() + 1
        store.get(key, 1, 4)
        assertEquals(plain + 2, plainRequests.get())
    }

    @Test
    fun `zoomed far out, tiles on screen are not asked about`(@TempDir folder: Path) {
        serve(tilePath, Reply(200, png()))
        Config.tileCheckMinutes = 0
        val store = TileStore(blueMap(), folder, Direct())
        store.get(key, 1, 4)
        val requests = tileRequests.get()
        repeat(5) { store.get(key, 1, 4, mayCheck = false) }
        assertEquals(requests, tileRequests.get())
    }

    @Test
    fun `empty tiles are checked again and show what BlueMap renders later`(@TempDir folder: Path) {
        serve(tilePath, Reply(204))
        Config.tileCheckMinutes = 0
        val store = TileStore(blueMap(), folder, Direct())
        val tile = store.get(key, 1, 4)
        assertEquals(TileStore.State.EMPTY, tile.state)
        serve(tilePath, Reply(200, png()))
        store.get(key, 1, 4)
        assertEquals(TileStore.State.READY, tile.state)
        assertTrue(Files.exists(picture(folder)))
    }

    @Test
    fun `the tile file reads back every size, and a damaged one is fetched again`(@TempDir folder: Path) {
        val colours = IntArray(101 * 101) { i -> if (i % 7 == 0) 0 else TilePixels.OPAQUE or ((i * 2654435761L).toInt() and 0xFFFFFF) }
        colours[5] = TilePixels.OPAQUE // black, which must not turn into "nothing"
        val factors = TilePixels.factorsFor(100)
        val file = folder.resolve("t.smet")
        Files.write(file, TileFile.encode("abc", colours, 101, 100, factors))
        assertEquals("abc", TileFile.header(file).hash)
        assertEquals(TilePixels.presence(colours), TileFile.presence(file).second)
        for (factor in factors) {
            val expected = TilePixels.shrink(colours, 101, 100, factor).map { if (it == TilePixels.OPAQUE) TilePixels.OPAQUE or 1 else it }
            assertEquals(expected, TileFile.pixels(file, factor)!!.toList(), "1:$factor")
        }
        // Another version, or cut short: refused, so the store deletes it and downloads again.
        val bytes = Files.readAllBytes(file)
        bytes[7] = 9
        Files.write(file, bytes)
        assertTrue(runCatching { TileFile.header(file) }.isFailure)
        Files.write(file, Files.readAllBytes(file).copyOf(200).also { it[7] = 1 })
        assertTrue(runCatching { TileFile.pixels(file, 1) }.isFailure)

        serve(tilePath, Reply(200, png()))
        TileStore(blueMap(), folder.resolve("store"), Direct()).get(key, 1, 4)
        Files.write(picture(folder.resolve("store")), bytes)
        val requests = tileRequests.get()
        val tile = TileStore(blueMap(), folder.resolve("store"), Direct()).get(key, 1, 4)
        assertEquals(TileStore.State.READY, tile.state)
        assertEquals(requests + 1, tileRequests.get())
    }

    @Test
    fun `an old PNG cache is converted without downloading it again`(@TempDir folder: Path) {
        val old = folder.resolve("world/1/0_0.png")
        Files.createDirectories(old.parent)
        Files.write(old, png())
        Files.writeString(folder.resolve("world/1/0_0.meta"), "fetched=${System.currentTimeMillis()}\nsince=1234\nhash=old\n")
        val tile = TileStore(blueMap(), folder, Direct()).get(key, 1, 4)
        assertEquals(TileStore.State.READY, tile.state)
        assertEquals(1234, tile.contentMinute)
        assertEquals(0, tileRequests.get())
        assertFalse(Files.exists(old))
        assertEquals("old", TileFile.header(picture(folder)).hash)
    }

    @Test
    fun `trim keeps tiles drawn in the last five minutes and any still on their way`(@TempDir folder: Path) {
        serve(tilePath, Reply(200, png()))
        val store = TileStore(blueMap(), folder, Direct())
        store.get(key, 1, 4)
        val now = System.currentTimeMillis()
        store.trim(Long.MAX_VALUE, now + 4 * 60_000L)
        assertEquals(1, store.size)
        store.trim(Long.MAX_VALUE, now + 6 * 60_000L)
        assertEquals(0, store.size)

        val waiting = TileStore(blueMap(), folder, Never())
        waiting.get(key, 1, 4)
        waiting.trim(0, now + 60 * 60_000L)
        assertEquals(1, waiting.size)
    }

    @Test
    fun `the budget counts real bytes and keeps tiles on screen`() {
        assertEquals(500L * 500 * 4, TileStore.textureBytes(500))
        val now = 1_000_000L
        val mb = 1_000_000L
        val entries = listOf(
            Eviction.Entry("idle", mb, now - Eviction.IDLE_MS - 1, busy = false),
            Eviction.Entry("old", 4 * mb, now - 60_000, busy = false),
            Eviction.Entry("older", 4 * mb, now - 120_000, busy = false),
            Eviction.Entry("loading", 4 * mb, now - 120_000, busy = true),
            Eviction.Entry("on screen", 4 * mb, now, busy = false),
        )
        val result = Eviction.choose(entries, now, budget = 9 * mb)
        assertEquals(listOf("idle", "older", "old"), result.drop)
        assertEquals(8 * mb, result.kept)
        assertFalse(result.inViewOverBudget)
    }

    @Test
    fun `tiles on screen over the budget are drawn coarser, not dropped`() {
        val now = 1_000_000L
        val entries = (1..10).map { Eviction.Entry(it, 1_000_000L, now, busy = false) }
        val result = Eviction.choose(entries, now, budget = 4_000_000L)
        assertTrue(result.drop.isEmpty())
        assertTrue(result.inViewOverBudget)
        assertEquals(1, Eviction.coarser(0, result.inViewOverBudget, result.kept, 4_000_000L))
        assertEquals(0, Eviction.coarser(1, false, 1_000_000L, 4_000_000L))
        assertEquals(2, Backfill.coarser(500, 1, 1))
        assertEquals(5, Backfill.coarser(500, 2, 2))
        assertEquals(100, Backfill.coarser(500, 50, 3))
        assertEquals(4, Backfill.coarser(500, 4, 0))
    }

    @Test
    fun `the blocks BlueMap leaves unset at its edge are not drawn, so they are not black`() {
        // A 32-block tile, all wanted; BlueMap has nothing in the two westmost columns of chunk 1.
        val missing = { x: Int, z: Int -> x in 16..17 && z in 0..15 }
        val mask = Backfill.rectanglesOf(0, 0, 32, 0, 0, { _, _ -> true }) { x, z -> !missing(x, z) }
        val drawn = HashSet<Pair<Int, Int>>()
        for (i in 0 until mask.count) {
            val r = mask.rectangles
            for (x in r[i * 4].toInt() until r[i * 4 + 2].toInt()) for (z in r[i * 4 + 1].toInt() until r[i * 4 + 3].toInt()) {
                assertTrue(drawn.add(x to z), "drawn twice at $x, $z")
            }
        }
        for (x in 0 until 32) for (z in 0 until 32) assertEquals(!missing(x, z), (x to z) in drawn, "block $x, $z")
        // Whole chunks are still one rectangle per run: three whole chunks, then 16 rows of the partial one.
        assertEquals(1 + 1 + 16, mask.count)
    }

    @Test
    fun `slow frames halve uploads and pause background work, fast ones bring them back`() {
        var clock = 1L
        val pacer = FramePacer(clock = { clock }, freeHeap = { 0.5 })
        repeat(60) { clock += 40_000_000L; pacer.frame() } // 25 fps for 2.4 s
        assertTrue(pacer.slow && pacer.paused)
        assertTrue(pacer.uploads <= 2, "${pacer.uploads}")
        repeat(600) { clock += 10_000_000L; pacer.frame() } // 100 fps for 6 s
        assertFalse(pacer.paused)
        assertEquals(FramePacer.MAX_UPLOADS, pacer.uploads)

        // Two maps drawn in one frame count once.
        val twice = FramePacer(clock = { clock }, freeHeap = { 0.5 })
        repeat(200) { clock += 15_000_000L; twice.frame(); clock += 100_000L; twice.frame() }
        assertFalse(twice.slow)
    }

    @Test
    fun `little Java heap halves the budget and pauses background work`() {
        var clock = 1L
        val pacer = FramePacer(clock = { clock }, freeHeap = { 0.1 })
        repeat(80) { clock += 15_000_000L; pacer.frame() }
        assertTrue(pacer.heapLow && pacer.paused)
        assertEquals(Config.textureBudgetMb * 1024L * 1024L / 2, pacer.budgetBytes())
    }

    @Test
    fun `a region whose file is there but which Xaero never loads is covered, one still loading is not`(@TempDir folder: Path) {
        val regions = UnshowableRegions(null, Direct())
        var now = 0L
        repeat(20) { regions.observe(folder, 1, 2, 500, UnshowableRegions.Seen.NOT_LOADED, now); now += 1000 }
        assertFalse(regions.covers(folder, 1, 2, 500), "covered while it may only be loading")
        repeat(15) { regions.observe(folder, 1, 2, 500, UnshowableRegions.Seen.NOT_LOADED, now); now += 1000 }
        assertTrue(regions.covers(folder, 1, 2, 500))
        assertEquals(1, regions.count)

        // Never tried: left alone however long it is on screen.
        repeat(60) { regions.observe(folder, 3, 3, 500, UnshowableRegions.Seen.NOTHING, now); now += 1000 }
        assertFalse(regions.covers(folder, 3, 3, 500))
        // Off screen in between: the wait starts again.
        regions.observe(folder, 4, 4, 500, UnshowableRegions.Seen.NOT_LOADED, now)
        regions.observe(folder, 4, 4, 500, UnshowableRegions.Seen.NOT_LOADED, now + 60_000)
        assertFalse(regions.covers(folder, 4, 4, 500))
    }

    @Test
    fun `once the region file changes it is asked about again`(@TempDir folder: Path) {
        val file = folder.resolve("unshowable.json")
        val regions = UnshowableRegions(file, Direct())
        var now = 0L
        repeat(35) { regions.observe(folder, 1, 2, 500, UnshowableRegions.Seen.NOT_LOADED, now); now += 1000 }
        assertTrue(regions.covers(folder, 1, 2, 500))
        regions.save()
        assertTrue(UnshowableRegions(file, Direct()).covers(folder, 1, 2, 500), "not kept on disk")

        assertFalse(regions.covers(folder, 1, 2, 600)) // Xaero saved the region again
        regions.observe(folder, 1, 2, 600, UnshowableRegions.Seen.LOADED, now)
        assertEquals(0, regions.count)
    }
}
