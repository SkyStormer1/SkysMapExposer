package com.skystormer.skysmapexposer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The naming behind the copies kept before anything writes over Xaero's map.
 *
 * Restoring copies files back into Xaero's folder, so the thing deciding which names are regions is
 * also the thing standing between a folder of copies and writing anywhere else on the disk. It is
 * worth being sure about, and it needs no game.
 */
class MapBackupTest {

    @Test
    fun `a region is named the way Xaero names it`() {
        assertEquals("0_0.zip", MapBackup.regionFile(0, 0))
        assertEquals("2_-1.zip", MapBackup.regionFile(2, -1))
        assertEquals("-12_-34.zip", MapBackup.regionFile(-12, -34))
    }

    @Test
    fun `a region name reads back to the region it is for`() {
        assertEquals(0 to 0, MapBackup.regionOf("0_0.zip"))
        assertEquals(2 to -1, MapBackup.regionOf("2_-1.zip"))
        assertEquals(-12 to -34, MapBackup.regionOf("-12_-34.zip"))
    }

    @Test
    fun `every region survives the round trip`() {
        for (x in intArrayOf(-4096, -1, 0, 1, 4096)) {
            for (z in intArrayOf(-4096, -1, 0, 1, 4096)) {
                assertEquals(x to z, MapBackup.regionOf(MapBackup.regionFile(x, z)), "round trip of ${x}_$z")
            }
        }
    }

    @Test
    fun `a name that is not a region is refused`() {
        assertNull(MapBackup.regionOf("dimension_config.txt"))
        assertNull(MapBackup.regionOf(".lock"))
        assertNull(MapBackup.regionOf("0_0.xwmc"))
        assertNull(MapBackup.regionOf("0_0.zip.absent"))
        assertNull(MapBackup.regionOf(""))
    }

    @Test
    fun `a name that tries to walk out of the folder is refused`() {
        // These would be copied somewhere other than Xaero's region folder if they were accepted.
        assertNull(MapBackup.regionOf("../0_0.zip"))
        assertNull(MapBackup.regionOf("..\\0_0.zip"))
        assertNull(MapBackup.regionOf("/etc/0_0.zip"))
        assertNull(MapBackup.regionOf("mods/0_0.zip"))
        assertNull(MapBackup.regionOf("0_0.zip/../../evil.jar"))
    }

    @Test
    fun `a number too long to be a region is refused rather than overflowing`() {
        assertNull(MapBackup.regionOf("999999999999_0.zip"))
        assertNull(MapBackup.regionOf("0_999999999999.zip"))
    }
}
