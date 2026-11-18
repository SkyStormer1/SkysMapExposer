package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import kotlin.math.roundToLong

/**
 * The world border of a dimension, for clearing what lies past it from either map: a square given
 * by hand ([Box]), else BlueMap's drawn "World Border" outline, else the game's own border.
 */
object MapBorder {

    /**
     * A square border given by hand, [radius] blocks each way from ([centerX], [centerZ]), for a
     * server whose border is a plugin's: the game only knows a border of its own.
     */
    class Box(val radius: Int, val centerX: Int, val centerZ: Int) {
        val words get() = "$radius $centerX $centerZ"
    }

    /**
     * A border, as what lies wholly [beyond] it and what wholly [inside] it, each asked about a box
     * (minX, minZ, maxX, maxZ) in blocks. [from] says where it came from and how big it is, for
     * the preview and the log.
     */
    class Shape(
        val from: String,
        val what: String,
        val nothing: String,
        val beyond: (Double, Double, Double, Double) -> Boolean,
        val inside: (Double, Double, Double, Double) -> Boolean,
    )

    /**
     * The border to clear past in [dimensionId], or why there is none: the square given by hand
     * ([box]) if any, else the "World Border" outline on BlueMap's map (as a border plugin such as
     * Chunky draws it, often a circle), else the game's own world border.
     *
     * Of BlueMap's closed outlines with "border" in their label, the largest is used: a claim
     * called "Border Town" must never be taken for the world border, which would clear everything
     * outside the town.
     */
    fun border(box: Box?, dimensionId: String, name: String): Pair<Shape?, String?> {
        val byHand = "If that is not the server's border, give its size: /mapexposer clear border <radius> [<centerX> <centerZ>]"
        if (box != null) {
            val r = box.radius.toDouble()
            return square(
                "your radius, ${size(2.0 * box.radius, box.centerX.toDouble(), box.centerZ.toDouble())}", "$name map more than ${box.radius} blocks from ${box.centerX}, ${box.centerZ}",
                "Nothing to clear: all your $name map is within ${box.radius} blocks of ${box.centerX}, ${box.centerZ}",
                box.centerX - r, box.centerZ - r, box.centerX + r, box.centerZ + r,
            ) to null
        }
        val session = Session.current
        val map = session?.server?.markersFor(dimensionId)
        if (session != null && map != null) {
            val snapshot = session.markers.of(map)
                ?: return null to "BlueMap's markers for the $name are still being read; try again in a moment"
            val drawn = worldBorder(snapshot.outlines)
            if (drawn != null) return polygon(
                drawn.label, drawn.points, "$name map beyond the world border on BlueMap",
                "Nothing to clear: all your $name map is inside the world border on BlueMap. $byHand",
            ) to null
        }
        val level = Minecraft.getInstance().level ?: return null to "Not in a world"
        if (level.dimension().identifier().toString() != dimensionId) {
            return null to "BlueMap shows no world border for the $name, and the game only knows the border of the dimension you are in; go to the $name first. $byHand"
        }
        val border = level.worldBorder
        val nothing = if (border.size >= 29_000_000.0) {
            "Nothing to clear: BlueMap shows no world border for the $name, and the server has not set one of the game's own. $byHand"
        } else {
            "Nothing to clear: all your $name map is inside the world border, x ${border.minX.toInt()} to ${border.maxX.toInt()}, " +
                "z ${border.minZ.toInt()} to ${border.maxZ.toInt()}. $byHand"
        }
        val across = maxOf(border.maxX - border.minX, border.maxZ - border.minZ)
        val described = "the game's world border, ${size(across, (border.minX + border.maxX) / 2, (border.minZ + border.maxZ) / 2)}"
        return square(described, "$name map beyond the world border", nothing, border.minX, border.minZ, border.maxX, border.maxZ) to null
    }

    private fun square(from: String, what: String, nothing: String, minX: Double, minZ: Double, maxX: Double, maxZ: Double) = Shape(
        from, what, nothing,
        beyond = { x0, z0, x1, z1 -> x1 <= minX || x0 >= maxX || z1 <= minZ || z0 >= maxZ },
        inside = { x0, z0, x1, z1 -> x0 >= minX && x1 <= maxX && z0 >= minZ && z1 <= maxZ },
    )

    /**
     * A border drawn as corners ([points], x and z of each, closed). A circle drawn this way cuts
     * a little inside the real one between corners, so a box counts as beyond it only when it is
     * [MARGIN] blocks clear of every side.
     */
    private fun polygon(label: String, points: FloatArray, what: String, nothing: String): Shape {
        val n = points.size / 2
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minZ = Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (i in 0 until n) {
            minX = minOf(minX, points[i * 2].toDouble()); maxX = maxOf(maxX, points[i * 2].toDouble())
            minZ = minOf(minZ, points[i * 2 + 1].toDouble()); maxZ = maxOf(maxZ, points[i * 2 + 1].toDouble())
        }
        val described = "BlueMap marker '$label', ${size(maxOf(maxX - minX, maxZ - minZ), (minX + maxX) / 2, (minZ + maxZ) / 2)}"
        fun contains(x: Double, z: Double): Boolean {
            var inside = false
            var j = n - 1
            for (i in 0 until n) {
                val xi = points[i * 2].toDouble(); val zi = points[i * 2 + 1].toDouble()
                val xj = points[j * 2].toDouble(); val zj = points[j * 2 + 1].toDouble()
                if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside
                j = i
            }
            return inside
        }
        fun touches(x0: Double, z0: Double, x1: Double, z1: Double): Boolean {
            var j = n - 1
            for (i in 0 until n) {
                if (segmentMeetsBox(points[j * 2].toDouble(), points[j * 2 + 1].toDouble(), points[i * 2].toDouble(), points[i * 2 + 1].toDouble(), x0, z0, x1, z1)) return true
                j = i
            }
            return false
        }
        return Shape(
            described, what, nothing,
            beyond = { x0, z0, x1, z1 ->
                !contains((x0 + x1) / 2, (z0 + z1) / 2) && !touches(x0 - MARGIN, z0 - MARGIN, x1 + MARGIN, z1 + MARGIN)
            },
            inside = { x0, z0, x1, z1 ->
                contains(x0, z0) && contains(x1, z0) && contains(x0, z1) && contains(x1, z1) && !touches(x0, z0, x1, z1)
            },
        )
    }

    private const val MARGIN = 16.0

    /** Of BlueMap's closed outlines with "border" in their label, the largest; see [border]. */
    fun worldBorder(outlines: List<Markers.Outline>): Markers.Outline? = outlines
        .filter { it.closed && it.points.size >= 6 && it.label.contains("border", ignoreCase = true) }
        .maxByOrNull { area(it.points) }

    /** The area inside a closed outline ([points], x and z of each), by the shoelace formula. */
    private fun area(points: FloatArray): Double {
        val n = points.size / 2
        var twice = 0.0
        var j = n - 1
        for (i in 0 until n) {
            twice += points[j * 2].toDouble() * points[i * 2 + 1] - points[i * 2].toDouble() * points[j * 2 + 1]
            j = i
        }
        return kotlin.math.abs(twice) / 2
    }

    /** "about 20,000 blocks across, around 0, 0" */
    private fun size(across: Double, centerX: Double, centerZ: Double) =
        "about ${"%,d".format(across.roundToLong())} blocks across, around ${centerX.roundToLong()}, ${centerZ.roundToLong()}"

    /** Whether the line from (ax, az) to (bx, bz) passes through the box, by clipping it to the box. */
    private fun segmentMeetsBox(ax: Double, az: Double, bx: Double, bz: Double, x0: Double, z0: Double, x1: Double, z1: Double): Boolean {
        var t0 = 0.0
        var t1 = 1.0
        val dx = bx - ax
        val dz = bz - az
        for ((p, q) in arrayOf(-dx to ax - x0, dx to x1 - ax, -dz to az - z0, dz to z1 - az)) {
            if (p == 0.0) {
                if (q < 0) return false
            } else {
                val t = q / p
                if (p < 0) { if (t > t1) return false; if (t > t0) t0 = t }
                else { if (t < t0) return false; if (t < t1) t1 = t }
            }
        }
        return true
    }
}
