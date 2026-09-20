package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.gui.PlayerRowWidget
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two bits of layout arithmetic that cannot be checked by looking at the game without several
 * other mods installed: where the Players button lands beside other mods' buttons, and how the
 * player list divides a row into columns.
 */
class MapButtonsTest {

    /** Xaero's own settings button, at the very top of the left edge. */
    private val xaeroCog = intArrayOf(0, 2, 20, 20)

    /** Where Sky's Map Shapes puts its Shapes button. */
    private val shapesButton = intArrayOf(0, 32, 44, 20)

    @Test
    fun `an empty corner takes the first slot`() {
        assertEquals(MapButtons.FIRST, MapButtons.firstFreeSlot(emptyList()))
    }

    @Test
    fun `Xaero's own button is above the first slot and does not push it down`() {
        assertEquals(MapButtons.FIRST, MapButtons.firstFreeSlot(listOf(xaeroCog)))
    }

    @Test
    fun `another mod's button in the first slot pushes this one below it`() {
        assertEquals(54, MapButtons.firstFreeSlot(listOf(xaeroCog, shapesButton)))
    }

    @Test
    fun `two mods' buttons push it below both`() {
        val second = intArrayOf(0, 54, 40, 20)
        assertEquals(76, MapButtons.firstFreeSlot(listOf(xaeroCog, shapesButton, second)))
    }

    @Test
    fun `a button that only half covers a slot still blocks it`() {
        // 40..60 leaves neither 32..52 nor 54..74 clear, so the next free slot is 76.
        assertEquals(76, MapButtons.firstFreeSlot(listOf(intArrayOf(0, 40, 44, 20))))
    }

    @Test
    fun `buttons elsewhere on the screen are ignored`() {
        val rightEdge = intArrayOf(400, 32, 20, 20)
        val middle = intArrayOf(MapButtons.WIDTH, 32, 60, 20)
        assertEquals(MapButtons.FIRST, MapButtons.firstFreeSlot(listOf(rightEdge, middle)))
    }

    @Test
    fun `a widget with no size is not in the way`() {
        assertEquals(MapButtons.FIRST, MapButtons.firstFreeSlot(listOf(intArrayOf(0, 32, 0, 0))))
    }

    @Test
    fun `a corner packed solid falls back to the first slot rather than running off the screen`() {
        val packed = (0 until MapButtons.MAX_SLOTS).map {
            intArrayOf(0, MapButtons.FIRST + it * (MapButtons.HEIGHT + MapButtons.GAP), 44, 20)
        }
        assertEquals(MapButtons.FIRST, MapButtons.firstFreeSlot(packed))
    }

    @Test
    fun `slots never overlap the one before them`() {
        var previous = MapButtons.firstFreeSlot(emptyList())
        val taken = ArrayList<IntArray>()
        repeat(4) {
            taken.add(intArrayOf(0, previous, 44, MapButtons.HEIGHT))
            val next = MapButtons.firstFreeSlot(taken)
            assertTrue(next >= previous + MapButtons.HEIGHT, "slot $next overlaps the one at $previous")
            previous = next
        }
    }

    @Test
    fun `columns stay as wide as their content and no wider`() {
        // Room to spare: the columns must not stretch, or the list is mostly gap.
        val columns = PlayerRowWidget.columns(60, 80, 50, 420)
        assertArrayEquals(intArrayOf(60, 80, 50), columns)
    }

    @Test
    fun `a wider row does not widen the columns`() {
        assertArrayEquals(
            PlayerRowWidget.columns(60, 80, 50, 320),
            PlayerRowWidget.columns(60, 80, 50, 900),
        )
    }

    @Test
    fun `columns shrink in proportion when they do not fit`() {
        val columns = PlayerRowWidget.columns(120, 160, 80, 220)
        val room = 220 - PlayerRowWidget.HEAD - PlayerRowWidget.GAP * 3
        assertEquals(room, columns.sum())
        // Still in the same order of size as what was asked for, so nothing is squeezed to nothing.
        assertTrue(columns[1] > columns[0], "coordinates ${columns[1]} should beat name ${columns[0]}")
        assertTrue(columns[0] > columns[2], "name ${columns[0]} should beat distance ${columns[2]}")
    }

    @Test
    fun `columns always fit inside the row and are never negative`() {
        for (width in intArrayOf(40, 80, 120, 200, 280, 330, 420, 600)) {
            val columns = PlayerRowWidget.columns(120, 160, 80, width)
            val used = PlayerRowWidget.HEAD + PlayerRowWidget.GAP * 3 + columns.sum()
            assertTrue(used <= width || width < 60, "columns for width $width used $used")
            assertTrue(columns.all { it >= 0 }, "negative column for width $width: ${columns.toList()}")
        }
    }

    @Test
    fun `an empty list of players does not produce a broken row`() {
        val columns = PlayerRowWidget.columns(0, 0, 0, 320)
        assertTrue(columns.all { it >= 0 })
        assertEquals(3, columns.size)
    }
}
