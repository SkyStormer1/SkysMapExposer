package com.skystormer.skysmapexposer

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/** Tooltips, marker icons and colours, and damaged terrain files. */
class SmallFixesTest {

    @Test
    fun `no tooltip is made or placed outside the helpers`() {
        val helpers = setOf("SafeTooltip.kt", "DockPanel.kt", "WidgetTooltipHolderMixin.java")
        val offenders = Files.walk(Path.of("src/main")).use { files ->
            files.toList()
                .filter { it.extension == "kt" || it.extension == "java" }
                .filter { it.name !in helpers }
                .filter { file -> Files.readString(file).let { "Tooltip.create" in it || "setTooltipForNextFrame" in it } }
                .map { it.name }
        }
        assertEquals(emptyList<String>(), offenders, "Use SafeTooltip.of or DockPanel.tooltip, which keep tooltips on screen")
    }

    @Test
    fun `a big icon is kept at 64 pixels, keeping its shape`() {
        assertEquals(64 to 64, shrinkIcon(BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB)).let { it.width to it.height })
        assertEquals(64 to 32, shrinkIcon(BufferedImage(1024, 512, BufferedImage.TYPE_INT_ARGB)).let { it.width to it.height })
        val small = BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB)
        assertTrue(shrinkIcon(small) === small)
    }

    @Test
    fun `a shrunk icon keeps its colour`() {
        val red = BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 512) for (x in 0 until 512) red.setRGB(x, y, 0xFFFF0000.toInt())
        val small = shrinkIcon(red)
        assertEquals(0xFFFF0000.toInt(), small.getRGB(31, 31))
    }

    @Test
    fun `an outline colour past 255 is full, not spilled into the next colour`() {
        val colour = lineColour(JsonParser.parseString("""{ "r": 300, "g": 0, "b": -5, "a": 1 }""").asJsonObject)
        assertEquals(0xFFFF0000.toInt(), colour)
        assertEquals(0x7F102030, lineColour(JsonParser.parseString("""{ "r": 16, "g": 32, "b": 48, "a": 0.5 }""").asJsonObject))
    }

    @Test
    fun `waypoint initials keep the label's colour but never take a code for a letter`() {
        assertEquals("§cSH", Waypoints.initials("§cShop"))
        assertEquals("§c§lMS", Waypoints.initials("§c§lMain §eSpawn"))
        assertEquals("FB", Waypoints.initials("Farm Base"))
    }

    @Test
    fun `an out of range block index says the file is damaged`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            ShareFormat.varint(out, 0) // no blocks
            ShareFormat.varint(out, 0) // no biomes
            out.writeInt(0)
            out.writeInt(0)
            ShareFormat.varint(out, 3) // block 3 of none
        }
        val error = runCatching {
            ShareFormat.readTerrain(DataInputStream(ByteArrayInputStream(bytes.toByteArray())), 1, HashMap())
        }.exceptionOrNull()
        assertTrue(error?.message?.startsWith("the file is damaged") == true, "got $error")
    }
}
