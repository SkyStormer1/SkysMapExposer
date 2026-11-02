package com.skystormer.skysmapexposer.terrain

import com.skystormer.skysmapexposer.TilePixels
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.ceil
import kotlin.math.floor

/**
 * What one of BlueMap's 3D tiles says about the water in it, column by column, `z * size + x` from
 * the tile's corner: the top of the bed under the water and its colour, and the top of the water
 * and its colour. This is all the sea floor needs ([SeaFloor]), and a few kilobytes where the 3D
 * tile itself is over a megabyte, so it is what is kept on disk.
 *
 * A column with no water over a bed has [NONE] for both heights and is left to BlueMap's flat picture.
 */
class FloorColumns(val size: Int) {
    /** The y of the bed's top face (one above the bed block). */
    val bedTop = ShortArray(size * size) { NONE }

    /** 0xRRGGBB: the bed's texture, tinted as BlueMap tinted it. */
    val bedColour = IntArray(size * size)

    /** The y of the block above the highest water block, so `waterTop - bedTop` is how many water blocks there are. */
    val waterTop = ShortArray(size * size) { NONE }

    /** 0xAARRGGBB: water's texture, tinted by the biome as BlueMap drew it, with the texture's own opacity. */
    val waterColour = IntArray(size * size)

    fun has(i: Int): Boolean = bedTop[i] != NONE && waterTop[i] != NONE && waterTop[i] > bedTop[i]

    val count: Int get() = (0 until size * size).count(::has)

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(bytes)).use { out ->
            out.writeByte(VERSION)
            out.writeShort(size)
            for (i in 0 until size * size) {
                out.writeShort(bedTop[i].toInt())
                out.writeShort(waterTop[i].toInt())
                if (!has(i)) continue
                out.writeInt(bedColour[i])
                out.writeInt(waterColour[i])
            }
        }
        return bytes.toByteArray()
    }

    /** One BlueMap texture: its resource path and its average colour, 0xAARRGGBB. */
    class Texture(val path: String, val colour: Int) {
        val water: Boolean = path.contains(":block/water")
    }

    companion object {
        const val NONE: Short = Short.MIN_VALUE
        private const val VERSION = 1

        /** A face counts as facing up from this far round (cos of about 45°). */
        private const val UP = 0.7f

        @Throws(IOException::class)
        fun decode(bytes: ByteArray): FloorColumns {
            DataInputStream(InflaterInputStream(ByteArrayInputStream(bytes))).use { input ->
                if (input.readByte().toInt() != VERSION) throw IOException("unknown sea floor file version")
                val size = input.readShort().toInt()
                if (size !in 1..MAX_SIZE) throw IOException("bad sea floor tile size $size")
                val columns = FloorColumns(size)
                for (i in 0 until size * size) {
                    columns.bedTop[i] = input.readShort()
                    columns.waterTop[i] = input.readShort()
                    if (!columns.has(i)) continue
                    columns.bedColour[i] = input.readInt()
                    columns.waterColour[i] = input.readInt()
                }
                return columns
            }
        }

        private const val MAX_SIZE = 512

        /**
         * Reads the water and what is under it out of [tile], [size] blocks a side, whose texture
         * numbers index [textures]. Looks only at faces turned up: per column, the highest that is
         * water and the highest that is not. A column whose highest bed is above its water (ice,
         * a boat, a bridge) is no sea floor.
         */
        fun of(tile: HiresTile, textures: List<Texture>, size: Int): FloorColumns {
            val columns = FloorColumns(size)
            val bedY = FloatArray(size * size) { Float.NEGATIVE_INFINITY }
            val waterY = FloatArray(size * size) { Float.NEGATIVE_INFINITY }
            for (t in 0 until tile.triangles) {
                if (tile.normal(t, 1) < UP) continue
                val texture = textures.getOrNull(tile.texture(t)) ?: continue
                if (!texture.path.contains(":block/")) continue
                var minX = Float.POSITIVE_INFINITY
                var maxX = Float.NEGATIVE_INFINITY
                var minZ = Float.POSITIVE_INFINITY
                var maxZ = Float.NEGATIVE_INFINITY
                var y = Float.NEGATIVE_INFINITY
                for (c in 0..2) {
                    val x = tile.x(t, c)
                    val z = tile.z(t, c)
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (z < minZ) minZ = z
                    if (z > maxZ) maxZ = z
                    if (tile.y(t, c) > y) y = tile.y(t, c)
                }
                val colour = tinted(texture.colour, tile.color(t))
                // BlueMap's top faces are a block square in two triangles: every column under one.
                for (z in maxOf(0, floor(minZ).toInt()) until minOf(size, ceil(maxZ).toInt())) {
                    for (x in maxOf(0, floor(minX).toInt()) until minOf(size, ceil(maxX).toInt())) {
                        val i = z * size + x
                        if (texture.water) {
                            if (y > waterY[i]) { waterY[i] = y; columns.waterColour[i] = colour }
                        } else if (y > bedY[i]) {
                            bedY[i] = y
                            columns.bedColour[i] = colour and 0xFFFFFF
                        }
                    }
                }
            }
            for (i in 0 until size * size) {
                if (waterY[i] == Float.NEGATIVE_INFINITY || bedY[i] == Float.NEGATIVE_INFINITY) continue
                val bed = Math.round(bedY[i])
                // A water surface sits a little below the block's top: 62.875 is the water block at 62.
                val water = ceil(waterY[i]).toInt()
                if (water <= bed) continue
                columns.bedTop[i] = bed.toShort()
                columns.waterTop[i] = water.toShort()
            }
            return columns
        }

        /** [colour] (0xAARRGGBB) times [tint] (0xRRGGBB), keeping the alpha. */
        fun tinted(colour: Int, tint: Int): Int {
            fun channel(shift: Int) = ((colour shr shift) and 0xFF) * ((tint shr shift) and 0xFF) / 255
            return (colour and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        /**
         * What Xaero's World Map draws for water [depth] blocks deep over a bed of [bedColour]
         * (0xRRGGBB) whose block is at [bedY], with the bed north of it at [northY] and north-west
         * at [northWestY]: the water layer ([waterColour], 0xAARRGGBB) at its texture's opacity, and
         * the bed behind it at the rest, lit with Xaero's slopes and depth ([TilePixels.xaeroLight])
         * and as if each block of water took away one level of sky light. Read out of Xaero's
         * `MapPixel.getPixelColours`, where the minimum light is 9 of 24.
         */
        fun xaeroWater(waterColour: Int, bedColour: Int, depth: Int, bedY: Int, northY: Int, northWestY: Int): Int {
            val alpha = ((waterColour ushr 24) and 0xFF) / 255f
            val sky = (15 - depth).coerceAtLeast(0)
            val bedLight = TilePixels.xaeroLight(bedY, northY, northWestY) * (MIN_LIGHT + sky) / (MIN_LIGHT + 15f) * (1 - alpha)
            fun channel(shift: Int): Int =
                (((waterColour shr shift) and 0xFF) * alpha + ((bedColour shr shift) and 0xFF) * bedLight).toInt().coerceIn(0, 255)
            return TilePixels.OPAQUE or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        private const val MIN_LIGHT = 9f
    }
}
