package com.skystormer.skysmapexposer

/**
 * What a waypoint made by this mod looks like, whichever map keeps it: its colour, its initials,
 * and where its counterpart in the other dimension is. Colours are Xaero's 21 by number, which is
 * also how shared waypoints carry them ([ShareFormat.SharedWaypoint.color]).
 */
object WaypointRules {

    /**
     * Xaero's waypoint colours in its own order: Minecraft's 16 chat colours, then magenta, light
     * blue, lime, pink and brown. The number is the index here.
     */
    val RGB = intArrayOf(
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF0000, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
        0xC74EBD, 0x3AB3DA, 0x80C71F, 0xF38BAA, 0x835432,
    )

    private val BY_ICON = mapOf(
        "white" to 15, "orange" to 6, "magenta" to 16, "light_blue" to 17, "yellow" to 14, "lime" to 18,
        "pink" to 19, "gray" to 8, "light_gray" to 7, "cyan" to 3, "purple" to 5, "blue" to 9,
        "brown" to 20, "green" to 2, "red" to 12, "black" to 0, "shop" to 6,
    )

    private const val AQUA = 11

    /** A marker's colour: banners in the banner's colour, shops gold, everything else aqua. */
    fun colourFor(pin: Markers.Pin): Int {
        val icon = (pin as? Markers.Point)?.icon?.substringAfterLast('/')?.substringBeforeLast('.')?.lowercase() ?: ""
        return BY_ICON[icon] ?: AQUA
    }

    /** The colour number nearest [rgb], for waypoints from a map that has any colour. */
    fun nearest(rgb: Int): Int = RGB.indices.minBy { i ->
        val c = RGB[i]
        val dr = ((c shr 16) and 0xFF) - ((rgb shr 16) and 0xFF)
        val dg = ((c shr 8) and 0xFF) - ((rgb shr 8) and 0xFF)
        val db = (c and 0xFF) - (rgb and 0xFF)
        dr * dr + dg * dg + db * db
    }

    /**
     * Two letters for [label]. Xaero shows `§` colour codes in waypoint names and initials (checked
     * in game), so the name keeps them and the initials take the colour the label starts with; the
     * codes themselves are never taken for letters (`§cShop` is a red "SH", not "CS").
     */
    fun initials(label: String): String {
        val leading = FORMAT_CODE_AT_START.find(label)?.value ?: ""
        val words = label.replace(FORMAT_CODE, "").split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        return leading + when {
            words.size >= 2 -> "${words[0][0]}${words[1][0]}".uppercase()
            words.size == 1 -> words[0].take(2).uppercase()
            else -> "B"
        }
    }

    private val FORMAT_CODE = Regex("§.")
    private val FORMAT_CODE_AT_START = Regex("^(§.)+")

    /** "Nether" for the Overworld and "Overworld" for the Nether: the dimensions with a counterpart. */
    fun counterpartName(dimension: String?): String? = when (dimension) {
        Config.OVERWORLD -> "Nether"
        Config.NETHER -> "Overworld"
        else -> null
    }

    /** Height of a waypoint put in the Nether from the Overworld: on the bedrock roof, where Nether highways run. */
    const val NETHER_Y = 128

    /** Block ([x], [z]) of [dimension] in the other one, as x, y (null if unknown) and z. */
    fun counterpart(x: Int, z: Int, dimension: String): Triple<Int, Int?, Int> =
        if (dimension == Config.OVERWORLD) Triple(Math.floorDiv(x, Config.NETHER_SCALE), NETHER_Y, Math.floorDiv(z, Config.NETHER_SCALE))
        else Triple(x * Config.NETHER_SCALE, null, z * Config.NETHER_SCALE)

    /** [counterpart], written as the waypoint will have it. */
    fun counterpartCoordinates(x: Int, z: Int, dimension: String): String =
        counterpart(x, z, dimension).let { (cx, cy, cz) -> if (cy == null) "$cx, $cz" else "$cx, $cy, $cz" }

    /** What a counterpart waypoint for this block would be, for the menu's tooltip. */
    fun counterpartTip(x: Int, z: Int, dimension: String): String =
        if (dimension == Config.OVERWORLD) "At ${counterpartCoordinates(x, z, dimension)}, on the Nether roof. It shows when you are in the Nether."
        else "At ${counterpartCoordinates(x, z, dimension)}, height unknown. It shows when you are in the Overworld."
}

/** One of your waypoints, offered to share, and the set or group the map keeps it in. */
class ShareableWaypoint(val set: String, val waypoint: ShareFormat.SharedWaypoint) {
    val name: String get() = waypoint.name
    val label: String get() = "$name  ${waypoint.x}, ${if (waypoint.yIncluded) "${waypoint.y}, " else ""}${waypoint.z}"
}
