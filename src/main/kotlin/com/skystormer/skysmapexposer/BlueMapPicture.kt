package com.skystormer.skysmapexposer

import java.io.IOException

/**
 * BlueMap's finest tiles read as data rather than as a picture: for every block, its colour, the
 * height of the ground and the block light there. This is what gets written into Xaero's map.
 *
 * BlueMap's tiles are 500 blocks square and Xaero's regions 512, so the two grids never line up; a
 * region is filled from the (up to four) tiles it overlaps, by [RegionPicture.fill].
 */
object BlueMapPicture {

    /** Blocks along one side of one of Xaero's regions. */
    const val REGION = 512

    /**
     * One finest-level tile, decoded. [colour] is 0xRRGGBB with the top byte set wherever BlueMap
     * has anything and 0 where it has nothing; [height] and [light] mean nothing where [colour] is 0.
     */
    class Tile(val tileX: Int, val tileZ: Int, val size: Int, val colour: IntArray, val height: ShortArray, val light: ByteArray) {
        val originX: Int get() = tileX * size
        val originZ: Int get() = tileZ * size
    }

    /**
     * Decodes BlueMap's PNG: `(size + 1)` wide and twice that high, colour on top and, below it,
     * the height in the low 16 bits (signed) and the block light in bits 16 to 23. The extra
     * column and row belong to the next tile and are dropped.
     */
    fun decode(tileX: Int, tileZ: Int, size: Int, bytes: ByteArray): Tile {
        val image = BlueMap.readImage(bytes, BlueMap.MAX_TILE_IMAGE)
        val width = image.width
        val half = image.height / 2
        if (width != half || width < size) throw IOException("unexpected tile shape ${width}x${image.height}")
        val source = image.getRGB(0, 0, width, image.height, null, 0, width)
        val colour = IntArray(size * size)
        val height = ShortArray(size * size)
        val light = ByteArray(size * size)
        for (z in 0 until size) {
            for (x in 0 until size) {
                val pixel = source[z * width + x]
                if (pixel ushr 24 == 0) continue
                val below = source[(half + z) * width + x]
                val i = z * size + x
                colour[i] = PRESENT or (pixel and 0xFFFFFF)
                height[i] = below.toShort()
                light[i] = ((below shr 16) and 0x0F).toByte()
            }
        }
        return Tile(tileX, tileZ, size, colour, height, light)
    }

    /**
     * What BlueMap has for one of Xaero's regions, block by block, `z * 512 + x` from the region's
     * north-west corner. [colour] follows [Tile.colour]'s rule: 0 means BlueMap has nothing there.
     */
    class RegionPicture(val regionX: Int, val regionZ: Int) {
        val colour = IntArray(REGION * REGION)
        val height = ShortArray(REGION * REGION)
        val light = ByteArray(REGION * REGION)

        /** Guessed biomes for the blocks nobody has mapped ([BiomeGuide]), or null to use a placeholder. */
        @Volatile
        var guess: BiomeGuide.Guess? = null

        /** How many blocks BlueMap has anything for. */
        var count = 0
            private set

        val originX: Int get() = regionX * REGION
        val originZ: Int get() = regionZ * REGION

        fun has(x: Int, z: Int): Boolean = colour[z * REGION + x] != 0

        /**
         * Copies what [tile] has inside this region and inside the block rectangle
         * [clipMinX]..[clipMaxX], [clipMinZ]..[clipMaxZ] (inclusive, world blocks).
         */
        fun fill(tile: Tile, clipMinX: Int, clipMinZ: Int, clipMaxX: Int, clipMaxZ: Int) {
            val minX = maxOf(originX, tile.originX, clipMinX)
            val maxX = minOf(originX + REGION - 1, tile.originX + tile.size - 1, clipMaxX)
            val minZ = maxOf(originZ, tile.originZ, clipMinZ)
            val maxZ = minOf(originZ + REGION - 1, tile.originZ + tile.size - 1, clipMaxZ)
            if (minX > maxX || minZ > maxZ) return
            for (z in minZ..maxZ) {
                val from = (z - tile.originZ) * tile.size - tile.originX
                val into = (z - originZ) * REGION - originX
                for (x in minX..maxX) {
                    val c = tile.colour[from + x]
                    if (c == 0) continue
                    if (colour[into + x] == 0) count++
                    colour[into + x] = c
                    height[into + x] = tile.height[from + x]
                    light[into + x] = tile.light[from + x]
                }
            }
        }

        /** How many of the 256 blocks of chunk ([chunkX], [chunkZ]) of this region BlueMap has, 0 to 256. */
        fun countInChunk(chunkX: Int, chunkZ: Int): Int {
            var n = 0
            for (z in chunkZ * 16 until chunkZ * 16 + 16) {
                for (x in chunkX * 16 until chunkX * 16 + 16) if (colour[z * REGION + x] != 0) n++
            }
            return n
        }
    }

    /** The finest tiles ([tileX] to [tileX], [tileZ] likewise) a block rectangle overlaps. */
    fun tilesOver(minX: Int, minZ: Int, maxX: Int, maxZ: Int, size: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (tx in Math.floorDiv(minX, size)..Math.floorDiv(maxX, size)) {
            for (tz in Math.floorDiv(minZ, size)..Math.floorDiv(maxZ, size)) out.add(tx to tz)
        }
        return out
    }

    /** Xaero's regions a block rectangle overlaps. */
    fun regionsOver(minX: Int, minZ: Int, maxX: Int, maxZ: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (rx in Math.floorDiv(minX, REGION)..Math.floorDiv(maxX, REGION)) {
            for (rz in Math.floorDiv(minZ, REGION)..Math.floorDiv(maxZ, REGION)) out.add(rx to rz)
        }
        return out
    }

    /**
     * Which finest tiles have anything in them, read off one coarse tile: each of its pixels covers
     * [blocksPerPixel] blocks square, and every finest tile any present pixel touches is counted.
     * [coarse] is decoded at the coarse tile's own size, so its origin is in coarse pixels.
     */
    fun finestTilesIn(coarse: Tile, blocksPerPixel: Int, finestSize: Int, into: MutableSet<Pair<Int, Int>>) {
        for (z in 0 until coarse.size) {
            for (x in 0 until coarse.size) {
                if (coarse.colour[z * coarse.size + x] == 0) continue
                val blockX = (coarse.originX + x) * blocksPerPixel
                val blockZ = (coarse.originZ + z) * blocksPerPixel
                into.addAll(tilesOver(blockX, blockZ, blockX + blocksPerPixel - 1, blockZ + blocksPerPixel - 1, finestSize))
            }
        }
    }

    private const val PRESENT = 0xFF shl 24
}
