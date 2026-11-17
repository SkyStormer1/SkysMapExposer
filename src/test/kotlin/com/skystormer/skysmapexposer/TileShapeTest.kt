package com.skystormer.skysmapexposer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

/** A lowres tile must be exactly its map's shape, checked before it is decoded (alarm A1). */
class TileShapeTest {

    private fun png(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out)
        return out.toByteArray()
    }

    @Test
    fun theMapsOwnShapeIsRead() {
        val image = BlueMap.readTile(png(5, 10), tileSize = 4)
        assertEquals(5, image.width)
        assertEquals(10, image.height)
    }

    @Test
    fun anyOtherShapeIsRefused() {
        // Bigger than the map's tiles: once allowed up to 8194 on each side, whatever the map.
        assertThrows<IOException> { BlueMap.readTile(png(400, 800), tileSize = 4) }
        // Square, or one pixel off.
        assertThrows<IOException> { BlueMap.readTile(png(5, 5), tileSize = 4) }
        assertThrows<IOException> { BlueMap.readTile(png(5, 11), tileSize = 4) }
    }

    @Test
    fun decodersRefuseTheWrongShapeToo() {
        assertThrows<IOException> { TilePixels.decode(png(9, 18), tileSize = 4, blocksPerPixel = 1) }
        assertThrows<IOException> { BlueMapPicture.decode(0, 0, 4, png(9, 18)) }
    }
}
