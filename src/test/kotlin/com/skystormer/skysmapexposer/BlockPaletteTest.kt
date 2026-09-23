package com.skystormer.skysmapexposer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The colour matching behind writing BlueMap's picture into Xaero's own map. It decides whether
 * imported terrain reads as terrain or as mush, and none of it needs a running game, so it is
 * worth pinning down here rather than by squinting at a map.
 */
class BlockPaletteTest {

    private fun lab(vararg colours: Int) = colours.map(BlockPalette::toLab)

    @Test
    fun `an exact colour picks its own entry`() {
        val palette = lab(0xFF0000, 0x00FF00, 0x0000FF)
        assertEquals(1, BlockPalette.nearest(0x00FF00, palette))
    }

    @Test
    fun `a near miss still picks the right entry`() {
        val palette = lab(0x3F76E4, 0x7FB238, 0xC2B280)
        // Shallow water: closer to the water blue than to grass or sand.
        assertEquals(0, BlockPalette.nearest(0x4A7FE0, palette))
    }

    @Test
    fun `two greens a shade apart stay nearer each other than a brown does`() {
        // The case plain RGB distance gets wrong, and the reason for Oklab: grass next to grass.
        val grass = BlockPalette.toLab(0x7FB238)
        val otherGrass = BlockPalette.toLab(0x88BB40)
        val brown = BlockPalette.toLab(0x976D4D)
        assertTrue(
            BlockPalette.distance(grass, otherGrass) < BlockPalette.distance(grass, brown),
            "two greens should be nearer each other than green is to brown",
        )
    }

    @Test
    fun `at a similar lightness the nearer hue wins`() {
        // Blue sea water against a blue and a brown of much the same lightness.
        val palette = lab(0x3F76E4, 0x9C7B4E)
        assertEquals(0, BlockPalette.nearest(0x5A86D0, palette))
    }

    @Test
    fun `lightness counts for more than hue`() {
        // Deep water is nearer a dark block than a bright blue one, and that is what terrain wants:
        // a dark pixel should become a dark block, or shaded ground turns to a patchwork of hues.
        // Worth pinning down, because it is the opposite of what plain RGB distance would do.
        val palette = lab(0x2B2B2B, 0x3F76E4)
        assertEquals(0, BlockPalette.nearest(0x1A2B6B, palette))
    }

    @Test
    fun `black and white are the extremes of lightness`() {
        val black = BlockPalette.toLab(0x000000)
        val white = BlockPalette.toLab(0xFFFFFF)
        assertTrue(black.l < 0.01f, "black should have no lightness, was ${black.l}")
        assertTrue(white.l > 0.99f, "white should be fully light, was ${white.l}")
    }

    @Test
    fun `grey has no colour to it`() {
        val grey = BlockPalette.toLab(0x808080)
        assertTrue(Math.abs(grey.a) < 0.01f && Math.abs(grey.b) < 0.01f, "grey should sit on the axis")
    }

    @Test
    fun `alpha in the top byte is ignored`() {
        val palette = lab(0xFF0000, 0x00FF00)
        assertEquals(BlockPalette.nearest(0x00FF00, palette), BlockPalette.nearest(0xFF00FF00.toInt(), palette))
    }

    @Test
    fun `an empty palette says so rather than throwing`() {
        assertEquals(-1, BlockPalette.nearest(0x123456, emptyList()))
    }

    @Test
    fun `every candidate is a namespaced id and the list has no repeats`() {
        val ids = BlockPalette.CANDIDATES
        assertTrue(ids.all { it.contains(':') }, "candidates must be namespaced")
        assertEquals(ids.size, ids.toSet().size, "candidate list repeats itself")
    }
}
