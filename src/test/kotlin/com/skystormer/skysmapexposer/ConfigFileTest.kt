package com.skystormer.skysmapexposer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The settings file reads and writes back the same, whatever the code behind it looks like. */
class ConfigFileTest {

    /** The settings as they were before each test, put back after it so other tests see them unchanged. */
    private lateinit var before: JsonObject

    @BeforeEach
    fun keepSettings() {
        before = Config.toJson()
    }

    @AfterEach
    fun putSettingsBack() {
        val file = Files.createTempFile("skysmapexposer-before", ".json")
        try {
            Files.writeString(file, before.toString())
            Config.load(file)
            Config.takeLoadProblem()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** Every setting the file holds, none of them at its default. */
    private val everything = """
        {
          "enabled": false,
          "showMarkers": false,
          "showOutlines": false,
          "showPlayers": false,
          "minimapPlayers": "ALWAYS",
          "matchWaypointsToDimension": false,
          "guessBiomes": false,
          "otherDimensionCoords": false,
          "otherDimensionArrow": false,
          "barOpen": false,
          "barLeft": 140,
          "barTop": 33,
          "barLeftShare": 0.25,
          "barTopShare": 0.5,
          "barRows": 11,
          "minimapBiomes": false,
          "pings": false,
          "pingSound": false,
          "trusted": { "mc.example.com": ["00000000-0000-0000-0000-000000000001"] },
          "pingTo": { "mc.example.com": ["00000000-0000-0000-0000-000000000001"] },
          "fillOff": ["Bobby", "Voxy"],
          "setupDeclined": ["play.example.com"],
          "barScale": 1.5,
          "barExtra": 40,
          "barUnder": "skysstructuremap",
          "panelMaxScale": 3.0,
          "staleDays": 2.5,
          "worldMapMarkerScale": 1.25,
          "playerHeadScale": 0.75,
          "minimapHeadScale": 1.5,
          "minimapMarkerScale": 2.0,
          "minimapShrinkChunks": 64,
          "tileCheckMinutes": 30,
          "fullDownloadHours": 168,
          "markerRefreshSeconds": 60,
          "textureBudgetMb": 512,
          "prepareMap": false,
          "caveNotice": false,
          "seaFloorHires": true,
          "prepareTilesPerSecond": 8,
          "servers": [
            {
              "addresses": ["mc.example.com", "1.2.3.4"],
              "url": "https://map.example.com/",
              "maps": { "minecraft:overworld": "world", "minecraft:the_nether": "nether" },
              "off": ["minecraft:the_nether"],
              "coverBefore": { "minecraft:overworld": 1700000000000 }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `every setting reads and writes back the same`(@TempDir folder: Path) {
        val file = folder.resolve("skysmapexposer.json")
        Files.writeString(file, everything)
        Config.load(file)
        assertEquals(null, Config.takeLoadProblem())
        assertEquals(JsonParser.parseString(everything), Config.toJson())
    }

    @Test
    fun `edits change nothing until applied, then only what they changed`() {
        Config.pings = true
        Config.barRows = 6
        val edits = Config.edits()
        val untouched = edits.toString()
        edits[Config::pings] = false
        assertEquals(true, Config.pings)
        assertEquals(false, edits[Config::pings])
        // Changed elsewhere meanwhile (the map bar, say): not put back by the screen's Done.
        Config.barRows = 12
        edits.apply()
        assertEquals(false, Config.pings)
        assertEquals(12, Config.barRows)
        edits.reset(Config::pings)
        assertEquals(true, edits[Config::pings])
        assertEquals(untouched, edits.toString())
        assertEquals(7.0, edits.default(Config::staleDays))
    }

    @Test
    fun `values out of range are brought in, and the minimap's heads start from the world map's`(@TempDir folder: Path) {
        val file = folder.resolve("skysmapexposer.json")
        Files.writeString(file, """
            { "barRows": 99, "playerHeadScale": 9.0, "minimapShrinkChunks": 1, "fullDownloadHours": 1,
              "textureBudgetMb": 1, "markerRefreshSeconds": 0, "servers": [] }
        """.trimIndent())
        Config.load(file)
        assertEquals(40, Config.barRows)
        assertEquals(Config.MAX_SCALE, Config.playerHeadScale)
        assertEquals(Config.MAX_SCALE, Config.minimapHeadScale)
        assertEquals(Config.MIN_SHRINK_CHUNKS, Config.minimapShrinkChunks)
        assertEquals(4, Config.fullDownloadHours)
        assertEquals(32, Config.textureBudgetMb)
        assertEquals(5, Config.markerRefreshSeconds)
    }
}
