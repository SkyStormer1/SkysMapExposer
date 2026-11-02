package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.terrain.FloorColumns
import com.skystormer.skysmapexposer.terrain.HiresTile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The sea floor from BlueMap's 3D tiles: reading it out of a tile, keeping it, and Xaero's water over it. */
class FloorColumnsTest {

    private class Face(val x: Int, val z: Int, val y: Float, val texture: Int, val tint: Int = 0xFFFFFF)

    /** A 3D tile file of one up-facing block square (two triangles) per face. */
    private fun prbm(faces: List<Face>): ByteArray {
        val triangles = faces.size * 2
        val corners = triangles * 3
        val buffer = ByteBuffer.allocate(64 + corners * 12 + triangles * 9 * 2 + 64 + faces.size * 24).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(1).put(3)
        fun u24(v: Int) { buffer.put(v.toByte()).put((v shr 8).toByte()).put((v shr 16).toByte()) }
        u24(corners)
        u24(0)
        fun attribute(name: String, type: Int) {
            buffer.put(name.toByteArray()).put(0).put(type.toByte())
            while (buffer.position() % 4 != 0) buffer.put(0)
        }
        attribute("position", 0x21)
        for (f in faces) {
            val square = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 0, 1 to 1, 0 to 1)
            for ((dx, dz) in square) buffer.putFloat((f.x + dx).toFloat()).putFloat(f.y).putFloat((f.z + dz).toFloat())
        }
        attribute("normal", 0x23)
        repeat(corners) { buffer.put(0).put(127).put(0) }
        attribute("color", 0x23)
        for (f in faces) repeat(6) { buffer.put((f.tint shr 16).toByte()).put((f.tint shr 8).toByte()).put(f.tint.toByte()) }
        while (buffer.position() % 4 != 0) buffer.put(0)
        faces.forEachIndexed { i, f -> buffer.putInt(f.texture).putInt(i * 6).putInt(6) }
        buffer.putInt(-1)
        return buffer.array().copyOf(buffer.position())
    }

    private val textures = listOf(
        FloorColumns.Texture("minecraft:block/sand", 0xFFDBCFA3.toInt()),
        FloorColumns.Texture("minecraft:block/water_still", 0xB4B1B1B1.toInt()),
    )

    @Test
    fun `water over a bed is read out, land and ice are not`() {
        val tile = HiresTile.read(prbm(listOf(
            Face(0, 0, 58f, 0), Face(0, 0, 62.875f, 1, tint = 0x3F76E4), // sea: sand at 57, water up to 62
            Face(1, 0, 64f, 0), // land
            Face(0, 1, 50f, 0), Face(0, 1, 62.875f, 1), Face(0, 1, 63f, 0), // something solid over the water
        )))
        val columns = FloorColumns.of(tile, textures, 2)
        assertTrue(columns.has(0))
        assertEquals(58, columns.bedTop[0].toInt())
        assertEquals(63, columns.waterTop[0].toInt())
        assertEquals(0xDBCFA3, columns.bedColour[0])
        assertEquals(FloorColumns.tinted(0xB4B1B1B1.toInt(), 0x3F76E4), columns.waterColour[0])
        assertFalse(columns.has(1))
        assertFalse(columns.has(2))
        assertEquals(1, columns.count)

        val again = FloorColumns.decode(columns.encode())
        assertEquals(columns.size, again.size)
        for (i in 0 until 4) {
            assertEquals(columns.has(i), again.has(i))
            if (columns.has(i)) {
                assertEquals(columns.bedTop[i], again.bedTop[i])
                assertEquals(columns.waterTop[i], again.waterTop[i])
                assertEquals(columns.bedColour[i], again.bedColour[i])
                assertEquals(columns.waterColour[i], again.waterColour[i])
            }
        }
    }

    @Test
    fun `xaero's water hides the bed more the deeper it is, down to its minimum light`() {
        val water = 0xB42C5287.toInt()
        val bed = 0xDBCFA3
        val shallow = FloorColumns.xaeroWater(water, bed, 1, 60, 60, 60)
        val deep = FloorColumns.xaeroWater(water, bed, 15, 46, 46, 46)
        val deeper = FloorColumns.xaeroWater(water, bed, 30, 31, 31, 31)
        fun red(c: Int) = (c shr 16) and 0xFF
        assertTrue(red(shallow) > red(deep))
        // Past 15 blocks no sky is left, and the depth's own darkening is already at its 0.9 floor.
        assertEquals(deep, deeper)
        // The water layer alone, at its texture's opacity, then the bed at 9/24 light behind it.
        val alpha = 0xB4 / 255f
        val bedLight = TilePixels.xaeroLight(46, 46, 46) * 9f / 24f * (1 - alpha)
        assertEquals((0x2C * alpha + 0xDB * bedLight).toInt(), red(deep))
    }

    @Test
    fun `the floor is only drawn where there is water over a bed`() {
        val size = 2
        val none = FloorColumns.NONE
        val floor = SeaFloor.draw(
            size,
            bedTop = shortArrayOf(58, none, 40, 58),
            bedColour = intArrayOf(0xDBCFA3, 0, 0x7F7F7F, 0xDBCFA3),
            waterTop = shortArrayOf(63, none, 63, none),
            waterColour = intArrayOf(0xB42C5287.toInt(), 0, 0xB42C5287.toInt(), 0),
        )
        assertTrue(floor[0] != 0)
        assertEquals(0, floor[1])
        assertTrue(floor[2] != 0)
        assertEquals(0, floor[3])
        // Deeper water over a darker bed comes out darker.
        assertTrue((floor[2] and 0xFF) < (floor[0] and 0xFF))
    }
}
