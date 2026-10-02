package com.skystormer.skysmapexposer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.imageio.ImageIO

/** What a BlueMap server sends is checked before it goes into a path or into memory. */
class UntrustedTest {

    @Test
    fun mapIdsAreFolderNamesAndNothingMore() {
        assertTrue(BlueMap.isMapId("world"))
        assertTrue(BlueMap.isMapId("world_the_nether"))
        assertFalse(BlueMap.isMapId(".."))
        assertFalse(BlueMap.isMapId("../../mods"))
        assertFalse(BlueMap.isMapId("a/b"))
        assertFalse(BlueMap.isMapId(""))
        assertThrows(IOException::class.java) { BlueMap.tilePath("../x", 1, 0, 0) }
    }

    private fun png(width: Int, height: Int): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", it) }.toByteArray()

    @Test
    fun imagesAreMeasuredBeforeTheyAreDecoded() {
        assertEquals(16, BlueMap.readImage(png(16, 32), 64).width)
        assertThrows(IOException::class.java) { BlueMap.readImage(png(100, 10), 64) }
        assertThrows(IOException::class.java) { BlueMap.readImage(byteArrayOf(1, 2, 3), 64) }
    }
}
