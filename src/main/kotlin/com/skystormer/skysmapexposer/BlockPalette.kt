package com.skystormer.skysmapexposer

import kotlin.math.cbrt

/**
 * Choosing a block to stand for a colour.
 *
 * Xaero stores a block state and a biome for every pixel of its map and works out the colour when
 * it draws, from the block's texture, the biome tint, the light and the slope. BlueMap hands over
 * the opposite: a finished colour, with no idea which block made it. So writing BlueMap's picture
 * into Xaero's own map means picking, for each pixel, the block whose colour comes closest — and
 * then Xaero draws it natively, with its own shading, at every zoom, without this mod present.
 *
 * The block identities are invented. That is the honest cost of the trade, and the reason
 * [Config.fillMapFromBlueMap] is off until asked for: what you gain is terrain that persists and
 * renders like the rest of your map, and what you lose is any truth about what is actually there.
 *
 * Matching is done in Oklab rather than on the raw bytes. Plain RGB distance thinks a dark blue and
 * a dark brown are neighbours and that two greens a shade apart are miles away, which turns
 * coastlines to mud; Oklab is built so that equal distances look equally different.
 */
object BlockPalette {

    /**
     * The blocks a pixel may be matched to, as identifiers, resolved at run time.
     *
     * Deliberately a short list of the things terrain is actually made of rather than the whole
     * block registry. Matching against everything finds absurdly better colour matches — a field of
     * TNT for red sand, wool for anything — which read as nonsense the moment you hover a pixel or
     * open cave mode. A small honest palette beats an accurate mad one.
     */
    val CANDIDATES: List<String> = listOf(
        "minecraft:water", "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt",
        "minecraft:podzol", "minecraft:mycelium", "minecraft:stone", "minecraft:andesite",
        "minecraft:diorite", "minecraft:granite", "minecraft:deepslate", "minecraft:cobblestone",
        "minecraft:gravel", "minecraft:sand", "minecraft:red_sand", "minecraft:sandstone",
        "minecraft:terracotta", "minecraft:white_terracotta", "minecraft:orange_terracotta",
        "minecraft:yellow_terracotta", "minecraft:red_terracotta", "minecraft:brown_terracotta",
        "minecraft:light_gray_terracotta", "minecraft:snow_block", "minecraft:ice", "minecraft:packed_ice",
        "minecraft:clay", "minecraft:mud", "minecraft:moss_block", "minecraft:oak_leaves",
        "minecraft:spruce_leaves", "minecraft:jungle_leaves", "minecraft:oak_log", "minecraft:spruce_log",
        "minecraft:netherrack", "minecraft:warped_nylium", "minecraft:crimson_nylium",
        "minecraft:soul_sand", "minecraft:basalt", "minecraft:blackstone", "minecraft:lava",
        "minecraft:end_stone", "minecraft:obsidian", "minecraft:bedrock",
    )

    /** A colour in Oklab: lightness, then the two opponent axes. */
    class Lab(val l: Float, val a: Float, val b: Float)

    /**
     * Which entry of [palette] looks closest to [colour], or -1 if the palette is empty. Both are
     * packed 0xRRGGBB; any alpha is ignored, since the map has no transparency to speak of.
     */
    fun nearest(colour: Int, palette: List<Lab>): Int {
        if (palette.isEmpty()) return -1
        val wanted = toLab(colour)
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (index in palette.indices) {
            val distance = distance(wanted, palette[index])
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    /** Squared Oklab distance. Squared because only the ordering matters and a root costs time. */
    fun distance(one: Lab, two: Lab): Float {
        val dl = one.l - two.l
        val da = one.a - two.a
        val db = one.b - two.b
        return dl * dl + da * da + db * db
    }

    /** 0xRRGGBB to Oklab, through linear sRGB as the colour space is defined. */
    fun toLab(colour: Int): Lab {
        val r = linear(((colour shr 16) and 0xFF) / 255f)
        val g = linear(((colour shr 8) and 0xFF) / 255f)
        val b = linear((colour and 0xFF) / 255f)

        val long = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val medium = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val short = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b

        val l = cbrt(long)
        val m = cbrt(medium)
        val s = cbrt(short)

        return Lab(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        )
    }

    /** sRGB's transfer curve undone, so the mixing above happens on light rather than on bytes. */
    private fun linear(channel: Float): Float =
        if (channel <= 0.04045f) channel / 12.92f else Math.pow(((channel + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
}
