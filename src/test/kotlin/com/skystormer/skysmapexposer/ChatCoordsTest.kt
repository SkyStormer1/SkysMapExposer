package com.skystormer.skysmapexposer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Positions read from chat keep their coordinates exactly as written, whichever map wrote them. */
class ChatCoordsTest {

    @Test
    fun journeyMapForm() {
        val position = ChatCoords.read("[name:\"Far\", x:2400, y:70, z:-1800, dim:minecraft:overworld]")!!
        assertEquals("Far", position.name)
        assertEquals(2400, position.x)
        assertEquals(70, position.y)
        assertEquals(-1800, position.z)
        assertEquals(Config.OVERWORLD, position.dimension)
    }

    @Test
    fun xaeroLineWrittenHereReadsBack() {
        val waypoint = ShareFormat.SharedWaypoint("Base: north", "B", 2400, 64, -1800, true, 3)
        val position = ChatCoords.read(ChatCoords.xaeroLine(Config.NETHER, waypoint))!!
        assertEquals("Base: north", position.name)
        assertEquals(2400, position.x)
        assertEquals(64, position.y)
        assertEquals(-1800, position.z)
        assertEquals(Config.NETHER, position.dimension)
    }

    @Test
    fun xaeroLineFromXaeroWithUnknownHeightAndOtherDimension() {
        val position = ChatCoords.read("<Sky> xaero-waypoint:Farm:F:-368:~:544:2:false:0:Internal-myserver\$creative")!!
        assertEquals(-368, position.x)
        assertEquals(544, position.z)
        assertEquals("myserver:creative", position.dimension)
    }

    @Test
    fun nothingToRead() {
        assertNull(ChatCoords.read("just chatting about x and z"))
    }
}
