package com.skystormer.skysmapexposer

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import java.util.Optional
import java.util.concurrent.ThreadLocalRandom

/**
 * Positions written in chat as JourneyMap's Chat Position writes them,
 * `[name:"Base", x:-368, y:64, z:544, dim:minecraft:overworld]` (the name optional), so they can go
 * between players of either map: Copy coordinates puts them on the clipboard in this form ([format]),
 * JourneyMap makes them clickable in its players' chat itself, and this does the same for Xaero's
 * ([decorate]): click to save one as a waypoint, Ctrl+click to show it on the map, as JourneyMap does.
 */
object ChatCoords {

    class Position(val name: String?, val x: Int, val y: Int, val z: Int, val dimension: String)

    /** [x], [y], [z] of [dimension] in JourneyMap's chat form, with [name] if given; a height the map does not know is written as [UNKNOWN_Y]. */
    fun format(name: String?, x: Int, y: Int?, z: Int, dimension: String): String = buildString {
        append('[')
        if (!name.isNullOrBlank()) append("name:\"").append(name.replace("\"", "")).append("\", ")
        append("x:").append(x).append(", y:").append(y ?: UNKNOWN_Y).append(", z:").append(z).append(", dim:").append(dimension).append(']')
    }

    /**
     * [waypoint] of [dimension] as Xaero's Minimap shares one in chat, a line Xaero's Minimap and
     * JourneyMap both read and offer to add, so players without this mod get it too.
     */
    fun xaeroLine(dimension: String, waypoint: ShareFormat.SharedWaypoint): String {
        fun safe(text: String) = text.replace(":", "^col^")
        val where = when (dimension) {
            Config.OVERWORLD -> "overworld"
            Config.NETHER -> "the_nether"
            Config.END -> "the_end"
            else -> dimension.replace(':', '$')
        }
        val initials = waypoint.initials.ifEmpty { waypoint.name.take(1).uppercase() }
        val y = if (waypoint.yIncluded) waypoint.y.toString() else "~"
        return "xaero-waypoint:${safe(waypoint.name)}:${safe(initials)}:${waypoint.x}:$y:${waypoint.z}:${waypoint.color}:false:0:Internal-$where"
    }

    /** Positions in chat lately, by the number their click asks for. Client thread. */
    private val offered = object : LinkedHashMap<Int, Position>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Position>?) = size > KEEP
    }

    /**
     * For `ChatComponentMixin`: [message] with each position in it clickable, when Xaero's map is the
     * one here (JourneyMap does this itself for its players); unchanged if it has none.
     */
    @JvmStatic
    fun decorate(message: Component): Component {
        if (MapMods.journeyMap || !MapMods.xaeroWorldMap) return message
        return try {
            decorated(message) ?: message
        } catch (e: Throwable) {
            Log.warn("Could not make the coordinates in a chat line clickable: {}", e.toString())
            message
        }
    }

    private fun decorated(message: Component): Component? {
        val text = message.string
        if (!text.contains("x:")) return null
        val found = PATTERN.findAll(text).mapNotNull { match -> parse(match.groupValues[1])?.let { match.range to it } }.toList()
        if (found.isEmpty()) return null
        // The line as its styled pieces; each position in it is shown as [shown] instead of its raw text.
        val pieces = ArrayList<Pair<Style, String>>()
        message.visit({ style, piece -> pieces.add(style to piece); Optional.empty<Unit>() }, Style.EMPTY)
        val out = Component.empty()
        var at = 0
        for ((style, piece) in pieces) {
            var start = 0
            while (start < piece.length) {
                val global = at + start
                val inside = found.indexOfFirst { (range, _) -> global in range }
                val end = if (inside >= 0) minOf(piece.length, found[inside].first.last + 1 - at)
                else found.map { it.first.first - at }.filter { it > start }.minOrNull()?.coerceAtMost(piece.length) ?: piece.length
                when {
                    inside < 0 -> out.append(Component.literal(piece.substring(start, end)).withStyle(style))
                    global == found[inside].first.first -> out.append(shown(found[inside].second))
                }
                start = end
            }
            at += piece.length
        }
        return out
    }

    /**
     * How a position in chat looks for Xaero's players: a diamond in its dimension's colour, its name,
     * its coordinates and dimension, one click target, the details on hover.
     */
    private fun shown(position: Position): Component {
        val id = remember(position)
        val dimension = Ui.dimensionName(position.dimension) ?: position.dimension
        val colour = when (position.dimension) {
            Config.NETHER -> ChatFormatting.RED
            Config.END -> ChatFormatting.LIGHT_PURPLE
            else -> ChatFormatting.GREEN
        }
        val hover = Component.empty()
            .append(Component.literal(position.name ?: "Position").withStyle(ChatFormatting.BOLD))
            .append(Component.literal("\n${position.x}, ${position.y}, ${position.z} in the $dimension").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n\nClick to save as a waypoint\nCtrl+Click to show on the map").withStyle(ChatFormatting.YELLOW))
        val line = Component.empty().withStyle { it.withClickEvent(ClickEvent.RunCommand("/mapexposer chatpos $id")).withHoverEvent(HoverEvent.ShowText(hover)) }
            .append(Component.literal("◆ ").withStyle(colour))
        position.name?.let { line.append(Component.literal("$it ").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)) }
        return line
            .append(Component.literal("${position.x}, ${position.y}, ${position.z}").withStyle(ChatFormatting.AQUA, ChatFormatting.UNDERLINE))
            .append(Component.literal(" $dimension").withStyle(ChatFormatting.GRAY))
    }

    private fun remember(position: Position): Int {
        val id = ThreadLocalRandom.current().nextInt(1 shl 30)
        offered[id] = position
        return id
    }

    /** `/mapexposer chatpos <id>`: the position clicked, saved as a waypoint, or shown on the map with Ctrl held. What to tell you. */
    fun clicked(id: Int): String? {
        val position = offered[id] ?: return "That position is from an earlier game; ask for it again"
        val adapter = MapMods.adapter ?: return "No map mod to put it on"
        val minecraft = Minecraft.getInstance()
        if (minecraft.hasControlDown()) {
            return if (adapter.goTo(position.dimension, position.x, position.z, null, null)) null else "The map can't show that just now"
        }
        val name = position.name ?: "${position.x}, ${position.z}"
        val waypoint = ShareFormat.SharedWaypoint(name, WaypointRules.initials(name), position.x, position.y, position.z, true, 0)
        val said = adapter.addWaypoints(position.dimension, "chat", listOf(waypoint))
        val where = Ui.dimensionName(position.dimension) ?: position.dimension
        return when {
            said.startsWith("Added") -> "Saved \"$name\" as a waypoint in the $where"
            said.startsWith("You already have") -> "You already have \"$name\" as a waypoint in the $where"
            else -> said
        }
    }

    /**
     * The inside of one bracketed position: `key:value` pairs split at commas outside quotes; x and z
     * needed, the rest optional (y as [UNKNOWN_Y], the dimension as yours), as JourneyMap reads them.
     */
    private fun parse(inside: String): Position? {
        val values = HashMap<String, String>()
        for (pair in inside.split(Regex(""",(?=(?:[^"]*"[^"]*")*[^"]*$)"""))) {
            val colon = pair.indexOf(':')
            if (colon <= 0) return null
            values[pair.substring(0, colon).trim().lowercase()] = pair.substring(colon + 1).trim()
        }
        val x = values["x"]?.toIntOrNull() ?: return null
        val z = values["z"]?.toIntOrNull() ?: return null
        val y = values["y"]?.toIntOrNull() ?: UNKNOWN_Y
        val dimension = values["dim"]?.takeIf { it.isNotBlank() }
            ?: Minecraft.getInstance().level?.dimension()?.identifier()?.toString() ?: return null
        val name = values["name"]?.trim('"', '\'')?.takeIf { it.isNotBlank() }
        return Position(name, x, y, z, if (':' in dimension) dimension else "minecraft:$dimension")
    }

    /** A bracketed list of `key:value` pairs with an x in it. */
    private val PATTERN = Regex("""\[((?:[^\[\]]*?)\bx\s*:\s*-?\d+(?:[^\[\]]*?))]""")

    /** The height written when the map does not know it, as JourneyMap reads Xaero's `~`. */
    private const val UNKNOWN_Y = 64

    private const val KEEP = 200
}
