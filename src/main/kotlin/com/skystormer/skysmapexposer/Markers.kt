package com.skystormer.skysmapexposer

import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.renderer.texture.DynamicTexture
import java.awt.Image
import java.awt.image.BufferedImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * BlueMap's markers for one server, read from each map's `live/markers.json` every
 * [Config.markerRefreshSeconds], so new shops and banners appear without rejoining.
 *
 * Two kinds are used:
 *  - points ("poi" markers): shops, banners, spawn. Shown as icons on the world map and minimap
 *    only, never in the world, and can be saved as Xaero waypoints.
 *  - outlines ("shape", "extrude" and "line" markers): the world border, zones. Drawn into the map
 *    itself, in the marker's own colour and line width.
 *
 * Other marker types (html, BlueMap's own player list) have no place on Xaero's map and are skipped.
 */
class Markers(private val blueMap: BlueMap, private val workers: ExecutorService) {

    /** Something drawn as a pin on the map: a marker or a player. */
    sealed class Pin(val label: String, val x: Double, val y: Double, val z: Double)

    class Point(
        val set: String,
        label: String,
        val detail: String,
        x: Double,
        y: Double,
        z: Double,
        val icon: String?,
    ) : Pin(label, x, y, z)

    /** A player BlueMap reports in this map's dimension. */
    class Player(val uuid: java.util.UUID, label: String, x: Double, y: Double, z: Double) : Pin(label, x, y, z)

    /**
     * @property points x and z of each corner, in order, two floats per corner.
     * @property closed whether the last corner joins back to the first.
     * @property colour ARGB.
     */
    class Outline(val label: String, val points: FloatArray, val closed: Boolean, val colour: Int, val width: Float)

    class Snapshot(val points: List<Point>, val outlines: List<Outline>)

    private val byMap = ConcurrentHashMap<String, Snapshot>()
    private val askedAt = HashMap<String, Long>()
    private val playersByMap = ConcurrentHashMap<String, List<Player>>()
    private val playersAskedAt = HashMap<String, Long>()
    private val icons = ConcurrentHashMap<String, IconSlot>()

    @Volatile
    private var closed = false

    private class IconSlot {
        @Volatile
        var image: NativeImage? = null
        var texture: DynamicTexture? = null
        @Volatile
        var failed = false
        var width = 0
        var height = 0
    }

    /** The markers of [map], as last read. Asks again when due. Client thread. */
    fun of(map: String): Snapshot? {
        val now = System.currentTimeMillis()
        val asked = askedAt[map]
        if (asked == null || now - asked > Config.markerRefreshSeconds * 1000L) {
            askedAt[map] = now
            workers.execute { read(map) }
        }
        return byMap[map]
    }

    /**
     * The players in [map]'s dimension, as BlueMap last reported them. Asked for every couple of
     * seconds, and only while something is showing them. Client thread.
     */
    fun players(map: String): List<Player> {
        val now = System.currentTimeMillis()
        val asked = playersAskedAt[map]
        if (asked == null || now - asked > PLAYER_REFRESH_MS) {
            playersAskedAt[map] = now
            workers.execute { readPlayers(map) }
        }
        return playersByMap[map] ?: emptyList()
    }

    private fun readPlayers(map: String) {
        try {
            val json = blueMap.players(map) ?: return
            val players = json.getAsJsonArray("players")?.take(MAX_POINTS)?.mapNotNull { element ->
                // One entry the server got wrong costs only that player.
                try {
                    val player = element.asJsonObject
                    // BlueMap lists everyone online on every map, marking those elsewhere as foreign.
                    if (player.get("foreign")?.asBoolean == true) return@mapNotNull null
                    val position = player.getAsJsonObject("position") ?: return@mapNotNull null
                    val x = position.get("x").asDouble
                    val y = position.get("y").asDouble
                    val z = position.get("z").asDouble
                    if (!onMap(x) || !onMap(y) || !onMap(z)) return@mapNotNull null
                    Player(
                        uuid = java.util.UUID.fromString(player.get("uuid").asString),
                        label = player.get("name")?.asString?.take(MAX_NAME) ?: "?",
                        x = x,
                        y = y,
                        z = z,
                    )
                } catch (e: RuntimeException) {
                    null
                }
            } ?: emptyList()
            playersByMap[map] = players
        } catch (e: Exception) {
            // Asked every couple of seconds, so said once per map; the last players known stay.
            if (warned.add("players:$map")) Log.warn("Could not read BlueMap's players for {}: {}", map, e.toString())
        }
    }

    /**
     * The texture of a marker icon and its size, or null if it is not loaded (yet) or cannot be
     * (BlueMap's default icon is an SVG, which Minecraft cannot draw). Client thread.
     */
    fun icon(path: String?): Triple<GpuTextureView, Int, Int>? {
        if (path == null || !path.endsWith(".png", ignoreCase = true)) return null
        if (!icons.containsKey(path) && icons.size >= MAX_ICONS) return null
        val slot = icons.getOrPut(path) {
            IconSlot().also { slot -> workers.execute { loadIcon(path, slot) } }
        }
        slot.texture?.let { return Triple(it.textureView, slot.width, slot.height) }
        val image = synchronized(slot) { slot.image.also { slot.image = null } } ?: return null
        slot.width = image.width
        slot.height = image.height
        val texture = DynamicTexture({ "skysmapexposer icon $path" }, image)
        slot.texture = texture
        return Triple(texture.textureView, slot.width, slot.height)
    }

    /** Frees every icon: uploaded textures, and pictures downloaded but not yet uploaded. */
    fun close() {
        closed = true
        icons.values.forEach { slot ->
            slot.texture?.close()
            synchronized(slot) {
                slot.image?.close()
                slot.image = null
            }
        }
        icons.clear()
    }

    private fun loadIcon(path: String, slot: IconSlot) {
        try {
            val bytes = blueMap.file(path) ?: throw IllegalStateException("not found")
            val decoded = shrinkIcon(BlueMap.readImage(bytes, BlueMap.MAX_ICON))
            val image = NativeImage(decoded.width, decoded.height, false)
            for (y in 0 until decoded.height) {
                for (x in 0 until decoded.width) image.setPixel(x, y, decoded.getRGB(x, y))
            }
            // Left the server while this was downloading: nothing will ever upload it. Checked
            // under the same lock as close(), so no image is left behind after it.
            synchronized(slot) { if (closed) image.close() else slot.image = image }
        } catch (e: Exception) {
            slot.failed = true
        }
    }

    private fun read(map: String) {
        try {
            val json = blueMap.markers(map) ?: return
            val points = ArrayList<Point>()
            val outlines = ArrayList<Outline>()
            var skipped = 0
            for ((setId, setElement) in json.entrySet()) {
                val set = setElement.asJsonObject
                val setLabel = set.get("label")?.asString ?: setId
                val markers = set.getAsJsonObject("markers") ?: continue
                for ((_, markerElement) in markers.entrySet()) {
                    // One marker the server got wrong costs only that marker.
                    try {
                        val marker = markerElement.asJsonObject
                        when (marker.get("type")?.asString) {
                            "poi" -> if (points.size < MAX_POINTS) point(setLabel, marker)?.let(points::add)
                            "shape", "extrude" -> if (outlines.size < MAX_OUTLINES) outline(marker, "shape", closed = true)?.let(outlines::add)
                            "line" -> if (outlines.size < MAX_OUTLINES) outline(marker, "line", closed = false)?.let(outlines::add)
                        }
                    } catch (e: RuntimeException) {
                        skipped++
                    }
                }
            }
            byMap[map] = Snapshot(points, outlines)
            if (skipped > 0 && warned.add(map)) Log.warn("Left out {} BlueMap marker(s) on {} that could not be read", skipped, map)
        } catch (e: Exception) {
            // Said once per map, not on every refresh; the markers already shown stay.
            if (warned.add(map)) Log.warn("Could not read BlueMap's markers for {}: {}", map, e.toString())
        }
    }

    private fun point(set: String, marker: JsonObject): Point? {
        val position = marker.getAsJsonObject("position") ?: return null
        val x = position.get("x").asDouble
        val y = position.get("y").asDouble
        val z = position.get("z").asDouble
        if (!onMap(x) || !onMap(y) || !onMap(z)) return null
        return Point(
            set = set,
            label = text(marker.get("label")?.asString ?: return null),
            detail = text(marker.get("detail")?.asString ?: ""),
            x = x,
            y = y,
            z = z,
            icon = marker.get("icon")?.takeIf { !it.isJsonNull }?.asString,
        )
    }

    private fun outline(marker: JsonObject, field: String, closed: Boolean): Outline? {
        val corners = marker.getAsJsonArray(field) ?: return null
        if (corners.size() !in 2..MAX_CORNERS) return null
        val points = FloatArray(corners.size() * 2)
        corners.forEachIndexed { index, element ->
            val corner = element.asJsonObject
            val x = corner.get("x").asFloat
            val z = corner.get("z").asFloat
            if (!onMap(x.toDouble()) || !onMap(z.toDouble())) return null
            points[index * 2] = x
            points[index * 2 + 1] = z
        }
        val colour = lineColour(marker.getAsJsonObject("lineColor"))
        if (colour ushr 24 == 0) return null
        return Outline(
            label = text(marker.get("label")?.asString ?: ""),
            points = points,
            closed = closed,
            colour = colour,
            width = (marker.get("lineWidth")?.asFloat ?: 2f).takeIf { it.isFinite() }?.coerceIn(0.5f, 16f) ?: 2f,
        )
    }

    private companion object {
        const val PLAYER_REFRESH_MS = 2_000L

        // Far more than any real server has; a reply beyond them is cut short, not trusted.
        const val MAX_POINTS = 20_000
        const val MAX_OUTLINES = 5_000
        const val MAX_CORNERS = 10_000
        const val MAX_ICONS = 512
        const val MAX_NAME = 64

        /** Within Minecraft's world border, with room to spare, and a real number. */
        fun onMap(value: Double) = value.isFinite() && value in -40_000_000.0..40_000_000.0
    }

    /** Maps whose markers could not be read, so it is said once rather than every refresh. */
    private val warned: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Marker text as a person would read it: BlueMap labels may carry HTML, and some servers' labels
     * arrive encoded twice ("TomÂ´s"), which this undoes.
     */
    private fun text(raw: String): String {
        var text = raw.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
        if (text.contains('Ã') || text.contains('Â')) {
            val repaired = String(text.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            if (!repaired.contains('�')) text = repaired
        }
        return text
    }
}

/** The most an icon is kept at, each way: drawn at most a few dozen pixels, a bigger one only costs video memory. */
internal const val ICON_SIDE = 64

/** [image] scaled down, keeping its shape, to fit [ICON_SIDE] each way; as it is if it already fits. */
internal fun shrinkIcon(image: BufferedImage): BufferedImage {
    val longest = maxOf(image.width, image.height)
    if (longest <= ICON_SIDE) return image
    val width = (image.width * ICON_SIDE / longest).coerceAtLeast(1)
    val height = (image.height * ICON_SIDE / longest).coerceAtLeast(1)
    val small = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = small.createGraphics()
    try {
        // Area averaging: a big step down with bilinear alone would sample a few pixels and shimmer.
        graphics.drawImage(image.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING), 0, 0, null)
    } finally {
        graphics.dispose()
    }
    return small
}

/** BlueMap's `lineColor` (r, g, b 0–255, a 0–1) as ARGB; red if there is none. */
internal fun lineColour(c: JsonObject?): Int {
    c ?: return 0xFFFF0000.toInt()
    val alpha = ((c.get("a")?.asFloat ?: 1f) * 255).toInt().coerceIn(0, 255)
    // Each clamped, as a value like 300 would otherwise spill into the next colour.
    fun channel(name: String) = c.get(name).asInt.coerceIn(0, 255)
    return (alpha shl 24) or (channel("r") shl 16) or (channel("g") shl 8) or channel("b")
}
