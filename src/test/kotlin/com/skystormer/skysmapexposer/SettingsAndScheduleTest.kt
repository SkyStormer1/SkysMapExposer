package com.skystormer.skysmapexposer

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The logic behind screens and schedules that does not need the game: the settings checks, when a
 * tile is asked about again, the Refresh button's cooldown, which Xaero layer BlueMap belongs in,
 * BlueMap's start position, and the pixel and size helpers.
 */
class SettingsAndScheduleTest {

    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private fun parse(text: String): Long? =
        runCatching { LocalDateTime.parse(text.trim(), time).toEpochSecond(ZoneOffset.UTC) * 1000 }.getOrNull()

    private fun entry(addresses: String, url: String, cover: String = "") =
        SettingsCheck.Entry(addresses.split(',').map { it.trim() }.filter { it.isNotEmpty() }, url, cover)

    @Test
    fun `settings with nothing wrong save`() {
        assertNull(SettingsCheck.check("7", listOf(entry("play.example.net", "https://map.example.net/", "2026-10-20 14:00")), ::parse))
        assertNull(SettingsCheck.check("0.5", emptyList(), ::parse))
        assertNull(SettingsCheck.check(" 0 ", listOf(entry("a.net, b.net", "https://map/")), ::parse))
    }

    @Test
    fun `a mistyped or negative Replace after stops saving`() {
        for (bad in listOf("", "seven", "-1", "NaN", "Infinity")) {
            val problem = SettingsCheck.check(bad, emptyList(), ::parse)
            assertEquals(SettingsCheck.Field.STALE, problem?.field, "'$bad' was accepted")
        }
    }

    @Test
    fun `a server missing its BlueMap address or its own address stops saving and says which`() {
        val noUrl = SettingsCheck.check("7", listOf(entry("ok.net", "https://map/"), entry("play.example.net", "  ")), ::parse)!!
        assertEquals(SettingsCheck.Field.URL, noUrl.field)
        assertEquals(1, noUrl.server)
        assertEquals("play.example.net has no BlueMap address", noUrl.message)

        val noAddress = SettingsCheck.check("7", listOf(entry("", "https://map.example.net/")), ::parse)!!
        assertEquals(SettingsCheck.Field.ADDRESS, noAddress.field)
        assertTrue("https://map.example.net/" in noAddress.message)
    }

    @Test
    fun `an empty new server entry is simply left out`() {
        assertNull(SettingsCheck.check("7", listOf(entry("ok.net", "https://map/"), entry("", "   ")), ::parse))
    }

    @Test
    fun `a mistyped season date stops saving`() {
        val problem = SettingsCheck.check("7", listOf(entry("play.example.net", "https://map/", "20/10/2026")), ::parse)!!
        assertEquals(SettingsCheck.Field.COVER, problem.field)
        assertEquals("play.example.net: the season date must look like 2026-10-20 14:00", problem.message)
        // Blank means never: fine.
        assertNull(SettingsCheck.check("7", listOf(entry("play.example.net", "https://map/", "  ")), ::parse))
    }

    @Test
    fun `only a change to the server's own settings counts as a change`() {
        val before = Config.Server(listOf("Play.Example.net:25565", "1.2.3.4"), "https://map/", mapOf("minecraft:overworld" to "world"), setOf("minecraft:the_nether"))
        val same = Config.Server(listOf("1.2.3.4", "play.example.net"), " https://map/ ", mapOf("minecraft:overworld" to "world"), setOf("minecraft:the_nether"))
        assertEquals(SettingsCheck.serverKey(before), SettingsCheck.serverKey(same))
        val otherUrl = Config.Server(before.addresses, "https://other/", before.maps, before.off)
        val netherOn = Config.Server(before.addresses, before.url, before.maps, emptySet())
        val newSeason = Config.Server(before.addresses, before.url, before.maps, before.off, mapOf("minecraft:overworld" to 1L))
        for (changed in listOf(otherUrl, netherOn, newSeason)) assertNotEquals(SettingsCheck.serverKey(before), SettingsCheck.serverKey(changed))
    }

    @Test
    fun `tiles are asked about every 15 minutes and downloaded whole every 4 hours`() {
        val checkMinutes = Config.tileCheckMinutes
        val hardHours = Config.tileHardRefreshHours
        try {
            Config.tileCheckMinutes = 15
            Config.tileHardRefreshHours = 4
            val now = 100L * 3_600_000L
            val due = TileStore.Schedule::due
            assertEquals(TileStore.Schedule.Due.NO, due(now - 14 * 60_000L, now - 60_000L, 0L, now))
            assertEquals(TileStore.Schedule.Due.CHECK, due(now - 15 * 60_000L, now - 60_000L, 0L, now))
            assertEquals(TileStore.Schedule.Due.DOWNLOAD, due(now, now - 4 * 3_600_000L, 0L, now))
            // Refresh pressed after the last whole download: downloaded again, however recently checked.
            assertEquals(TileStore.Schedule.Due.DOWNLOAD, due(now, now - 1000L, now - 500L, now))
            assertEquals(TileStore.Schedule.Due.NO, due(now, now - 1000L, now - 2000L, now))
        } finally {
            Config.tileCheckMinutes = checkMinutes
            Config.tileHardRefreshHours = hardHours
        }
    }

    @Test
    fun `Refresh can be pressed once every 30 seconds and counts down`() {
        val cooldown = MapRefresh.Cooldown(MapRefresh.COOLDOWN_MS)
        assertEquals(0, cooldown.secondsLeft(1_000_000L))
        assertTrue(cooldown.use(1_000_000L))
        assertFalse(cooldown.use(1_000_001L), "pressed twice in a row")
        assertEquals(23, cooldown.secondsLeft(1_007_000L))
        assertEquals(1, cooldown.secondsLeft(1_029_500L), "rounds up, so it never says 0 while still greyed out")
        assertTrue(cooldown.use(1_030_000L))
    }

    @Test
    fun `BlueMap's roof of the Nether goes over the surface, its inside over the cave layer, never the other way`() {
        val ceiling = { true }
        val open = { false }
        val surface = MapLayers.SURFACE
        // The Overworld: the surface only, whatever the map; its caves BlueMap has nothing for.
        assertEquals(surface, MapLayers.blueMapLayer(surface, open) { null })
        assertNull(MapLayers.blueMapLayer(3, open) { MapLayers.View.INSIDE })
        // The Nether: the roof over Xaero's surface, the inside over the cave layer on screen.
        assertEquals(surface, MapLayers.blueMapLayer(surface, ceiling) { MapLayers.View.ROOF })
        assertNull(MapLayers.blueMapLayer(3, ceiling) { MapLayers.View.ROOF }, "found in game: the roof was drawn over the explored inside")
        assertEquals(3, MapLayers.blueMapLayer(3, ceiling) { MapLayers.View.INSIDE })
        assertNull(MapLayers.blueMapLayer(surface, ceiling) { MapLayers.View.INSIDE })
        // Not known yet: neither.
        assertNull(MapLayers.blueMapLayer(surface, ceiling) { null })
        assertNull(MapLayers.blueMapLayer(3, ceiling) { null })
        assertEquals(MapLayers.SURFACE, XaeroWriter.SURFACE)
    }

    @Test
    fun `a download into the Nether goes where the map belongs, or not at all`() {
        val surface = MapLayers.SURFACE
        assertEquals(surface to null, MapLayers.downloadLayer(3, false, null), "Overworld caves: the surface, as before")
        assertEquals(surface, MapLayers.downloadLayer(3, true, MapLayers.View.ROOF).first)
        assertTrue("roof layer" in MapLayers.downloadLayer(3, true, MapLayers.View.ROOF).second!!)
        assertEquals(surface to null, MapLayers.downloadLayer(surface, true, MapLayers.View.ROOF))
        assertEquals(3 to null, MapLayers.downloadLayer(3, true, MapLayers.View.INSIDE))
        assertNull(MapLayers.downloadLayer(surface, true, MapLayers.View.INSIDE).first)
        assertNull(MapLayers.downloadLayer(3, true, null).first)
    }

    @Test
    fun `a map is of the roof when most of its ground is up at the roof`() {
        assertNull(MapLayers.viewOf(IntArray(0)))
        // Measured on a real server's Nether map: 97% at y 127, a few pillars and builds above.
        assertEquals(MapLayers.View.ROOF, MapLayers.viewOf(IntArray(97) { 127 } + IntArray(3) { 34 }))
        // Cut off at y 100 to show the inside, lava sea and all.
        assertEquals(MapLayers.View.INSIDE, MapLayers.viewOf(IntArray(60) { 31 } + IntArray(40) { 64 + it }))
        assertEquals(MapLayers.View.INSIDE, MapLayers.viewOf(IntArray(10) { 127 } + IntArray(90) { 70 }), "a few pillars up to the roof")
    }

    @Test
    fun `BlueMap's picture is lit the way Xaero lights its own map`() {
        // Flat ground at sea level, as Xaero draws it (worked through by hand from its sums).
        assertEquals(1.1714f, TilePixels.xaeroLight(63, 63, 63), 0.0005f)
        // Rising towards the light a block each way: Xaero's brightest.
        assertEquals(0.7f + 2f / 3, TilePixels.xaeroLight(63, 62, 62), 0.0005f)
        // Falling away from it: ambient light only.
        assertEquals(0.7f, TilePixels.xaeroLight(63, 64, 64), 0.0005f)
        // Deep ground is darker, but never below 0.9 of it; high ground no brighter than at sea level.
        assertEquals(1.1714f * 0.9f, TilePixels.xaeroLight(20, 20, 20), 0.0005f)
        assertEquals(TilePixels.xaeroLight(63, 63, 63), TilePixels.xaeroLight(120, 120, 120))
        // Found in game: BlueMap's deep ocean (31, 43, 102) came out darker than Xaero's (42, 56, 119).
        val ocean = TilePixels.xaeroLight(62, 62, 62)
        assertTrue((102 * ocean).toInt() in 115..121, "near Xaero's 119")
    }

    @Test
    fun `heights are read from the lower half of a lowres tile, where there is ground`() {
        val image = java.awt.image.BufferedImage(3, 6, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0xFF336699.toInt()) // ground at 0, 0 ...
        image.setRGB(0, 3, 0xFF00007F.toInt()) // ... 127 high
        image.setRGB(2, 2, 0xFF336699.toInt())
        image.setRGB(2, 5, 0xFF00FFC0.toInt()) // -64, as BlueMap writes heights below 0
        image.setRGB(1, 4, 0xFF000050.toInt()) // a height under no ground: not counted
        val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        assertEquals(listOf(127, -64), TilePixels.heights(bytes, 2).toList())
    }

    @Test
    fun `BlueMap's start position is read when it is there and sensible`() {
        val bodies = mapOf(
            "/maps/world/settings.json" to """{"startPos":[-1632,2048.7],"lowres":{}}""",
            "/maps/nether/settings.json" to """{"lowres":{}}""",
            "/maps/far/settings.json" to """{"startPos":[1e12,0]}""",
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = bodies[exchange.requestURI.path]?.toByteArray()
            if (body == null) exchange.sendResponseHeaders(404, -1)
            else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        try {
            BlueMap("http://127.0.0.1:${server.address.port}/").use { blueMap ->
                assertEquals(-1632 to 2048, blueMap.startPosition("world"))
                assertNull(blueMap.startPosition("nether"))
                assertNull(blueMap.startPosition("far"))
                assertTrue(runCatching { blueMap.startPosition("missing") }.isFailure)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `shrinking averages only the pixels BlueMap has`() {
        val red = TilePixels.OPAQUE or 0xFF0000
        val blue = TilePixels.OPAQUE or 0x0000FF
        // A 2-block tile, 3 pixels a side with BlueMap's overlap: two red, one blue, one empty.
        val colours = intArrayOf(red, red, 0x7, blue, 0, 0x7, 0x7, 0x7, 0x7)
        val one = TilePixels.shrink(colours, 3, 2, 2)
        assertEquals(1, one.size)
        assertEquals(TilePixels.OPAQUE or (170 shl 16) or 85, one[0])
        assertEquals(0, TilePixels.shrink(IntArray(9), 3, 2, 2)[0], "nothing stays nothing")
        assertEquals(listOf(1, 2, 4, 5, 10, 20, 25, 50, 100), TilePixels.factorsFor(500))
        assertEquals(listOf(1, 2, 4, 8, 16, 32), TilePixels.factorsFor(512).filter { it <= 32 })
        assertEquals(6, TilePixels.presence(intArrayOf(0, 1, 2, 0, 3, 4, 5, 6)).cardinality())
    }

    @Test
    fun `cache sizes read naturally`() {
        assertEquals("%,d MB".format(412), TileCache.megabytes(412L * 1024 * 1024))
        assertEquals("%.1f GB".format(1.5), TileCache.megabytes(1536L * 1024 * 1024))
        assertEquals("%,d MB · %,d tiles".format(0, 3180), TileCache.Size(1000, 3180).toString())
    }
}
