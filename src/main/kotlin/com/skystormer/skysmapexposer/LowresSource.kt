package com.skystormer.skysmapexposer

/**
 * BlueMap's finest lowres tiles, looked up by block, with each block's colour already in Oklab and
 * already sorted into water or not. [BiomeFeatures] reads the ground around a block from here, and
 * a download fills its regions from the same tiles ([tile]).
 *
 * Tiles come from [load] (BlueMap, or files on disk when training) and the last few are kept, as
 * neighbouring regions share tiles and a block's surroundings reach into the next tile. Safe to use
 * from one thread at a time.
 */
class LowresSource(
    private val size: Int,
    private val hasSea: Boolean,
    private val load: (Int, Int) -> BlueMapPicture.Tile?,
) : BiomeFeatures.Ground {

    /** One tile and, for each of its blocks, Oklab and whether it is water. */
    class Lowres(val tile: BlueMapPicture.Tile, hasSea: Boolean) {
        val l = FloatArray(tile.colour.size)
        val a = FloatArray(tile.colour.size)
        val b = FloatArray(tile.colour.size)
        val water = BooleanArray(tile.colour.size)

        init {
            for (i in tile.colour.indices) {
                val colour = tile.colour[i]
                if (colour == 0) continue
                val lab = BlockPalette.toLab(colour and 0xFFFFFF)
                l[i] = lab.l
                a[i] = lab.a
                b[i] = lab.b
                water[i] = XaeroPalette.isWater(lab.l, lab.a, lab.b, tile.height[i].toInt(), hasSea)
            }
        }
    }

    private val cache = object : LinkedHashMap<Long, Lowres?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Lowres?>) = size > KEEP
    }

    private var lastKey = Long.MIN_VALUE
    private var last: Lowres? = null

    /** Tile ([tileX], [tileZ]), or null if BlueMap has nothing there. */
    fun tile(tileX: Int, tileZ: Int): Lowres? {
        val key = (tileX.toLong() shl 32) or (tileZ.toLong() and 0xFFFFFFFFL)
        if (key == lastKey) return last
        val found = if (cache.containsKey(key)) cache[key] else load(tileX, tileZ)?.let { Lowres(it, hasSea) }.also { cache[key] = it }
        lastKey = key
        last = found
        return found
    }

    override fun sample(x: Int, z: Int, out: FloatArray): Boolean {
        val lowres = tile(Math.floorDiv(x, size), Math.floorDiv(z, size)) ?: return false
        val i = Math.floorMod(z, size) * size + Math.floorMod(x, size)
        if (lowres.tile.colour[i] == 0) return false
        out[0] = lowres.l[i]
        out[1] = lowres.a[i]
        out[2] = lowres.b[i]
        out[3] = lowres.tile.height[i].toFloat()
        out[4] = if (lowres.water[i]) 1f else 0f
        return true
    }

    private companion object {
        /** A region and the ground around it reach at most 3 by 3 tiles; a few more for the next region. */
        const val KEEP = 12
    }
}
