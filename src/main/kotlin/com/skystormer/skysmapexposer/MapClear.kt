package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import xaero.map.MapProcessor
import xaero.map.WorldMapSession
import xaero.map.region.MapBlock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Taking parts of your Xaero map off it, in the dimension and layer the world map is showing:
 *  - [Kind.OLD_SEASON]: every chunk mapped before the season began ([MapAge]), whose land and
 *    biomes are no longer there;
 *  - [Kind.BORDER]: every chunk wholly beyond the world border, as the server has it now.
 *
 * Asked for in two steps: the first says what would go and offers a button, the second does it.
 * It runs as a [BlueMapDownload] job that writes nothing and clears instead, region by region, each
 * region copied aside just before it is changed, so `/mapexposer restore` puts it all back.
 *
 * Also keeps the season start itself ([setSeason]), the "Cover map before" date of each dimension.
 */
object MapClear {

    enum class Kind(val word: String) { OLD_SEASON("oldseason"), BORDER("border") }

    /**
     * A square border given by hand, [radius] blocks each way from ([centerX], [centerZ]), for a
     * server whose border is a plugin's: the game only knows a border of its own.
     */
    class Box(val radius: Int, val centerX: Int, val centerZ: Int) {
        val words get() = "$radius $centerX $centerZ"
    }

    private class Plan(
        val dimensionId: String,
        val layer: Int,
        val regions: List<Pair<Int, Int>>,
        /** Your map from before the season / beyond the border, for the messages. */
        val what: String,
        val clears: (Int, Int) -> Boolean,
        /** What to say when [regions] is empty. */
        val nothing: String = "Nothing to clear: you have no $what",
    )

    /** Says in chat what [kind] would take off the map, with a button to go ahead. */
    fun preview(kind: Kind, box: Box? = null): String? {
        val (plan, why) = plan(kind, box)
        plan ?: return why
        if (plan.regions.isEmpty()) return plan.nothing
        val command = "/mapexposer clear ${kind.word}${box?.let { " " + it.words } ?: ""} confirm"
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal("Clear ${plan.what}? Up to ${plan.regions.size} regions are looked at; each is copied aside first, " +
                "so /mapexposer restore can put it back.  ")
                .append(ChatShare.button("[Clear]", command, "Takes it off your map now."))
        )
        return null
    }

    /** Starts clearing what [kind] covers. Returns what to tell the player. */
    fun confirm(kind: Kind, box: Box? = null): String {
        val (plan, why) = plan(kind, box)
        plan ?: return why!!
        if (plan.regions.isEmpty()) return plan.nothing
        val regions = plan.regions.map { (x, z) -> Triple(x, z) { Clearing(x, z, plan.clears) as XaeroWriter.Pixels? } }
        var label = ""
        label = BlueMapDownload.startLocal(
            regions, plan.dimensionId, plan.layer, false,
            title = "Clearing ${plan.what}", doing = "Clearing ${plan.what}", source = "",
        ) { tally ->
            if (tally.cleared == 0) "Nothing to clear: you have no ${plan.what}"
            else {
                val batch = MapBackup.batchName(label)
                // The action bar is gone in seconds, and the batch name is needed to undo this.
                Minecraft.getInstance().player?.sendSystemMessage(
                    Component.literal("Cleared ${tally.cleared} chunks of ${plan.what}.  ").let { message ->
                        if (batch == null) message
                        else message.append(ChatShare.button("[Restore]", "/mapexposer restore $batch", "Puts back what was cleared: /mapexposer restore $batch"))
                    }
                )
                "Cleared ${tally.cleared} chunks of ${plan.what}" + (batch?.let { "; /mapexposer restore $it puts them back" } ?: "")
            }
        } ?: return BlueMapDownload.lastRefusal
        return "Clearing ${plan.what}…"
    }

    private fun plan(kind: Kind, box: Box?): Pair<Plan?, String?> {
        val (shown, why) = XaeroWriter.shown()
        shown ?: return null to why
        val dimensionId = shown.dimensionId
        val name = shown.name
        val layer = shown.layer
        val folder = shown.folder
        val all = regionsIn(folder)
        return when (kind) {
            Kind.OLD_SEASON -> {
                val age = MapAge.of(dimensionId, folder)
                    ?: return null to "No season start is set for the $name; /mapexposer season set <yyyy-MM-dd HH:mm> sets it"
                Plan(dimensionId, layer, all.filter { (x, z) -> age.mayHoldOld(x, z) }, "$name map from before ${format(age.seasonStart)}", age::isOld) to null
            }
            Kind.BORDER -> {
                val border = border(box, dimensionId, name)
                val shape = border.first ?: return null to border.second
                val regions = all.filter { (x, z) -> !shape.inside(x * 512.0, z * 512.0, x * 512.0 + 512, z * 512.0 + 512) }
                Log.info("Clear past border in the $name, by ${shape.from}: ${regions.size} of ${all.size} regions reach past it")
                Plan(dimensionId, layer, regions, shape.what, { cx, cz -> shape.beyond(cx * 16.0, cz * 16.0, cx * 16.0 + 16, cz * 16.0 + 16) }, shape.nothing) to null
            }
        }
    }

    /**
     * A border, as what lies wholly [beyond] it and what wholly [inside] it, each asked about a box
     * (minX, minZ, maxX, maxZ) in blocks. [from] says where it came from, for the log.
     */
    private class Shape(
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
     */
    private fun border(box: Box?, dimensionId: String, name: String): Pair<Shape?, String?> {
        val byHand = "If that is not the server's border, give its size: /mapexposer clear border <radius> [<centerX> <centerZ>]"
        if (box != null) {
            val r = box.radius.toDouble()
            return square(
                "your radius", "$name map more than ${box.radius} blocks from ${box.centerX}, ${box.centerZ}",
                "Nothing to clear: all your $name map is within ${box.radius} blocks of ${box.centerX}, ${box.centerZ}",
                box.centerX - r, box.centerZ - r, box.centerX + r, box.centerZ + r,
            ) to null
        }
        val session = Session.current
        val map = session?.server?.markersFor(dimensionId)
        if (session != null && map != null) {
            val snapshot = session.markers.of(map)
                ?: return null to "BlueMap's markers for the $name are still being read; try again in a moment"
            val drawn = snapshot.outlines.firstOrNull { it.closed && it.points.size >= 6 && it.label.contains("border", ignoreCase = true) }
            if (drawn != null) return polygon(
                drawn.points, "$name map beyond the world border on BlueMap",
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
        return square("the game's world border", "$name map beyond the world border", nothing, border.minX, border.minZ, border.maxX, border.maxZ) to null
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
    private fun polygon(points: FloatArray, what: String, nothing: String): Shape {
        val n = points.size / 2
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
            "BlueMap's world border marker", what, nothing,
            beyond = { x0, z0, x1, z1 ->
                !contains((x0 + x1) / 2, (z0 + z1) / 2) && !touches(x0 - MARGIN, z0 - MARGIN, x1 + MARGIN, z1 + MARGIN)
            },
            inside = { x0, z0, x1, z1 ->
                contains(x0, z0) && contains(x1, z0) && contains(x0, z1) && contains(x1, z1) && !touches(x0, z0, x1, z1)
            },
        )
    }

    private const val MARGIN = 16.0

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

    /** The regions Xaero has a file for in [folder]. */
    private fun regionsIn(folder: Path): List<Pair<Int, Int>> = try {
        if (Files.notExists(folder)) emptyList()
        else Files.list(folder).use { files -> files.toList().mapNotNull { MapBackup.regionOf(it.fileName.toString()) } }
    } catch (e: Exception) {
        Log.error("Could not list the map's regions in $folder", e)
        emptyList()
    }

    /** Nothing to write, and chunks to take off. */
    private class Clearing(override val regionX: Int, override val regionZ: Int, private val clear: (Int, Int) -> Boolean) : XaeroWriter.Pixels {
        override fun countInChunk(chunkInRegionX: Int, chunkInRegionZ: Int) = 0
        override fun block(processor: MapProcessor, px: Int, pz: Int, old: MapBlock?, tally: XaeroWriter.Tally): MapBlock? = null
        override fun clears(chunkX: Int, chunkZ: Int) = clear(chunkX, chunkZ)
    }

    // ---- the season start ----

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun format(minute: Int): String =
        TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(minute * 60_000L), ZoneId.systemDefault()))

    /** The dimension the world map is showing, or else the one you are in. */
    private fun shownDimension(): String? =
        WorldMapSession.getCurrentSession()?.mapProcessor?.mapWorld?.currentDimension?.dimId?.identifier()?.toString()
            ?: Minecraft.getInstance().level?.dimension()?.identifier()?.toString()

    /** Each dimension's season start on this server, as words. */
    fun seasons(): String {
        val server = Config.serverFor(MapExposerClient.addressOf(Minecraft.getInstance()))
            ?: return "This server is not set up in Sky's Map Exposer's settings, so it has no season start"
        if (server.coverBefore.isEmpty()) return "No season start is set; /mapexposer season set <yyyy-MM-dd HH:mm> sets one for the dimension the map shows"
        return "Season start: " + server.coverBefore.entries.joinToString(", ") { (dimension, millis) ->
            "${MapMenus.dimensionName(dimension) ?: dimension} ${format(Clock.minutesOf(millis))}"
        }
    }

    /**
     * Sets the season start of the dimension the map shows to [text] — `yyyy-MM-dd HH:mm`, a date
     * alone, or `now` — or takes it away when [text] is null. Returns what to tell the player.
     */
    fun setSeason(text: String?): String {
        if (BlueMapDownload.running) return "Wait for what is being written into your map to finish first"
        val address = MapExposerClient.addressOf(Minecraft.getInstance())
        val server = Config.serverFor(address) ?: return "This server is not set up in Sky's Map Exposer's settings; add it there first"
        val dimension = shownDimension() ?: return "Not in a world"
        val name = MapMenus.dimensionName(dimension) ?: dimension
        val millis = text?.let { parse(it) ?: return "Write the time as yyyy-MM-dd HH:mm (or just the date, or now)" }
        val cover = LinkedHashMap(server.coverBefore)
        if (millis == null) cover.remove(dimension) else cover[dimension] = millis
        Config.servers = Config.servers.map { if (it === server) Config.Server(it.addresses, it.url, it.maps, it.off, cover) else it }
        Config.save()
        Session.start(address)
        return if (millis == null) "The $name has no season start now"
        else "The $name's season started ${format(Clock.minutesOf(millis))}: your map from before then is replaced by downloads and shared maps, and left out when you share"
    }

    private fun parse(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.equals("now", ignoreCase = true)) return System.currentTimeMillis()
        val time = runCatching { LocalDateTime.parse(trimmed, TIME) }.getOrNull()
            ?: runCatching { LocalDate.parse(trimmed).atStartOfDay() }.getOrNull()
            ?: return null
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
