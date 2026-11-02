package com.skystormer.skysmapexposer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A broken settings file, files written twice at once, and which outline counts as the world border. */
class SafetyTest {

    private fun broken(folder: Path) = Files.list(folder).use { files ->
        files.toList().map { it.fileName.toString() }.filter { it.startsWith("skysmapexposer.json.broken-") }
    }

    @Test
    fun `a typo in one server keeps the other servers and copies the file aside`(@TempDir folder: Path) {
        val file = folder.resolve("skysmapexposer.json")
        val text = """
            {
              "staleDays": "seven",
              "barRows": 9,
              "servers": [
                { "addresses": ["one.example.com"], "url": "http://one.example.com/", "maps": {} },
                { "addresses": ["two.example.com"], "maps": {} },
                { "addresses": ["three.example.com"], "url": "http://three.example.com/", "maps": {} }
              ]
            }
        """.trimIndent()
        Files.writeString(file, text)
        Config.staleDays = 7.0
        Config.load(file)
        assertEquals(listOf("one.example.com", "three.example.com"), Config.servers.map { it.addresses.single() })
        assertEquals(9, Config.barRows)
        assertEquals(7.0, Config.staleDays)
        val copies = broken(folder)
        assertEquals(1, copies.size)
        assertEquals(text, Files.readString(folder.resolve(copies.single())))
        assertTrue(Config.takeLoadProblem()!!.contains("server 2"))
    }

    @Test
    fun `a file that is not JSON is copied aside and nothing in memory changes`(@TempDir folder: Path) {
        val file = folder.resolve("skysmapexposer.json")
        Files.writeString(file, "{ \"servers\": [ oops")
        val before = listOf(Config.Server(listOf("kept.example.com"), "http://kept.example.com/", emptyMap()))
        Config.servers = before
        Config.load(file)
        assertTrue(Config.servers === before)
        assertEquals(1, broken(folder).size)
        assertTrue(Config.takeLoadProblem() != null)
    }

    @Test
    fun `a good file is not copied aside`(@TempDir folder: Path) {
        val file = folder.resolve("skysmapexposer.json")
        Files.writeString(file, """{ "barRows": 5, "servers": [] }""")
        Config.load(file)
        assertEquals(5, Config.barRows)
        assertTrue(broken(folder).isEmpty())
        assertEquals(null, Config.takeLoadProblem())
    }

    @Test
    fun `writes of the same file at once never mix and leave nothing behind`(@TempDir folder: Path) {
        val path = folder.resolve("tile.png")
        val pool = Executors.newFixedThreadPool(8)
        val contents = (0 until 200).map { i -> ByteArray(20_000) { i.toByte() } }
        contents.forEach { bytes -> pool.execute { SafeFiles.write(path, bytes) } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        val written = Files.readAllBytes(path)
        assertTrue(contents.any { it.contentEquals(written) })
        assertEquals(listOf("tile.png"), Files.list(folder).use { files -> files.toList().map { it.fileName.toString() } })
    }

    private fun square(label: String, half: Float) =
        Markers.Outline(label, floatArrayOf(-half, -half, half, -half, half, half, -half, half), true, 0, 1f)

    @Test
    fun `a town called Border Town is not taken for the world border`() {
        val town = square("Border Town", 200f)
        val border = square("World Border", 10_000f)
        assertEquals("World Border", MapClear.worldBorder(listOf(town, border))?.label)
        assertEquals("World Border", MapClear.worldBorder(listOf(border, town))?.label)
        assertEquals(null, MapClear.worldBorder(listOf(square("Spawn", 50f))))
    }
}
