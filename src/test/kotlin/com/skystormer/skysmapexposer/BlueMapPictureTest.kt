package com.skystormer.skysmapexposer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Reading BlueMap's tiles as data for Xaero's regions. BlueMap's grid is 500 blocks and Xaero's
 * 512, so every region is stitched from pieces of several tiles, and an off-by-one here writes
 * terrain in the wrong place — worth pinning down without a game.
 */
class BlueMapPictureTest {

    /** A BlueMap-shaped PNG: `(size + 1)` square of colour over the same of height and light. */
    private fun png(size: Int, colourAt: (Int, Int) -> Int, heightAt: (Int, Int) -> Int, lightAt: (Int, Int) -> Int = { _, _ -> 0 }): ByteArray {
        val side = size + 1
        val image = BufferedImage(side, side * 2, BufferedImage.TYPE_INT_ARGB)
        for (z in 0 until side) {
            for (x in 0 until side) {
                image.setRGB(x, z, colourAt(x, z))
                image.setRGB(x, side + z, (0xFF shl 24) or ((lightAt(x, z) and 0xFF) shl 16) or (heightAt(x, z) and 0xFFFF))
            }
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun `decode reads colour, signed height and light, and drops the overlap`() {
        val bytes = png(4, { x, z -> if (x == 1 && z == 2) 0xFF336699.toInt() else 0 }, { _, _ -> -12 }, { _, _ -> 7 })
        val tile = BlueMapPicture.decode(0, 0, 4, bytes)
        assertEquals(16, tile.colour.size)
        val i = 2 * 4 + 1
        assertEquals(0x336699, tile.colour[i] and 0xFFFFFF)
        assertTrue(tile.colour[i] != 0)
        assertEquals(-12, tile.height[i].toInt())
        assertEquals(7, tile.light[i].toInt())
        assertEquals(1, tile.colour.count { it != 0 }, "transparent pixels are nothing")
    }

    @Test
    fun `a region is stitched from the tiles it overlaps, block for block`() {
        // Tile size 500: region 0,0 (blocks 0..511) takes blocks 0..499 from tile 0 and 500..511
        // from tile 1. Colour each tile by its own origin so the seam is checkable.
        val size = 500
        val left = BlueMapPicture.decode(0, 0, size, png(size, { _, _ -> 0xFF111111.toInt() }, { x, _ -> x }))
        val right = BlueMapPicture.decode(1, 0, size, png(size, { _, _ -> 0xFF222222.toInt() }, { x, _ -> 500 + x }))
        val picture = BlueMapPicture.RegionPicture(0, 0)
        picture.fill(left, Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        picture.fill(right, Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        // Rows 0..499 are covered by these two tiles; 500..511 would come from the tiles below.
        assertEquals(512 * 500, picture.count)
        for (x in listOf(0, 250, 499, 500, 511)) {
            assertEquals(x, picture.height[10 * 512 + x].toInt(), "height at x=$x is the block's own x")
        }
        assertEquals(0x111111, picture.colour[499] and 0xFFFFFF)
        assertEquals(0x222222, picture.colour[500] and 0xFFFFFF)
        assertFalse(picture.has(0, 500), "row 500 belongs to the tiles below")
    }

    @Test
    fun `negative regions line up with negative tiles`() {
        val size = 500
        // Tile -1 covers blocks -500..-1. Region -1 covers -512..-1, so its x = 12 is block -500.
        val tile = BlueMapPicture.decode(-1, -1, size, png(size, { _, _ -> 0xFF010203.toInt() }, { x, z -> x + z }))
        val picture = BlueMapPicture.RegionPicture(-1, -1)
        picture.fill(tile, Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        assertFalse(picture.has(11, 12), "block -501 is in tile -2")
        assertTrue(picture.has(12, 12))
        assertEquals(0, picture.height[12 * 512 + 12].toInt(), "block -500, -500 is the tile's first pixel")
        assertEquals(500 * 500, picture.count)
    }

    @Test
    fun `the clip keeps a download inside the selected rectangle`() {
        val size = 500
        val tile = BlueMapPicture.decode(0, 0, size, png(size, { _, _ -> 0xFF445566.toInt() }, { _, _ -> 64 }))
        val picture = BlueMapPicture.RegionPicture(0, 0)
        // Chunk 2, 3 only: blocks 32..47, 48..63.
        picture.fill(tile, 32, 48, 47, 63)
        assertEquals(256, picture.count)
        assertEquals(256, picture.countInChunk(2, 3))
        assertEquals(0, picture.countInChunk(3, 3))
    }

    @Test
    fun `tiles and regions over a rectangle, including negative coordinates`() {
        assertEquals(listOf(-1 to -1, -1 to 0, 0 to -1, 0 to 0), BlueMapPicture.tilesOver(-1, -1, 0, 0, 500))
        assertEquals(listOf(0 to 0), BlueMapPicture.regionsOver(0, 0, 511, 511))
        assertEquals(listOf(-1 to 0, 0 to 0), BlueMapPicture.regionsOver(-1, 0, 511, 0))
    }

    @Test
    fun `a coarse tile says which finest tiles have anything`() {
        // Coarse tile 0,0 at 25 blocks per pixel, one pixel lit at (20, 0): blocks 500..524, 0..24.
        val coarse = BlueMapPicture.decode(0, 0, 500, png(500, { x, z -> if (x == 20 && z == 0) 0xFFFFFFFF.toInt() else 0 }, { _, _ -> 0 }))
        val found = HashSet<Pair<Int, Int>>()
        BlueMapPicture.finestTilesIn(coarse, 25, 500, found)
        assertEquals(setOf(1 to 0), found)
    }
}
