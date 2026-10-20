package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.ShareFormat.Step
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.chat.Component
import java.io.ByteArrayOutputStream
import java.util.concurrent.ThreadLocalRandom

/**
 * Sharing waypoints you pick ([WaypointShare]) with another player of this mod through private
 * messages (`/msg`), so it works on any server without anything installed there, and nobody else
 * sees it. Terrain is far too big for chat; it goes by file ([TerrainFiles]).
 *
 * Each batch ([ShareFormat]) is written as characters chat lets through (15 bits each, from CJK
 * and Hangul blocks that nothing rewrites) and sent as numbered private messages. A vanilla server
 * adds 20 to a player's spam count per message, takes 1 off per server tick and kicks above 200;
 * so messages go out [TICKS_PER_MESSAGE] server ticks apart (by the world's clock, which slows
 * when the server lags), leaving room for the player's own chat. Being muted, or a server without
 * /msg, stops it.
 *
 * Nobody without this mod is sent any of that. A player not yet known to have it first gets one
 * readable line saying what this is; their client, if it has the mod, hides it and answers, and
 * only then does the rest follow. With no answer, nothing more is sent.
 *
 * Receiving: every message carrying [MARKER] is hidden from chat, whoever sent it and wherever the
 * server put it. A button adds the waypoints to yours.
 */
object ChatShare {

    private const val MARKER = "⟪SME⟫"
    /** After [MARKER]: "would you take a map?", readable by anyone; and the hidden answer. */
    private const val ASK = '?'
    private const val ANSWER = '!'
    private const val ANSWER_WAIT = 15_000L
    private const val BITS = 15
    /** The longest command chat sends, `msg <name> ` included. */
    private const val CHAT_LIMIT = 256
    /** Transfer number (two characters), part number, number of parts. */
    private const val HEADER = 4

    private const val MAX_MESSAGES = 5000
    /** Server ticks between messages: a quarter more than the 20 a vanilla server's spam limit needs. */
    private const val TICKS_PER_MESSAGE = 25
    /** Client ticks after which a message goes anyway, if the world's clock is stopped (`/tick freeze`). */
    private const val CLOCK_STOPPED = 100

    private val MUTED = Regex("(you are|you're|you have been) (now )?muted([^a-z]|$)", RegexOption.IGNORE_CASE)
    private val NO_MSG = Regex("unknown (or incomplete )?command", RegexOption.IGNORE_CASE)
    private const val INCOMPLETE_WAIT = 300_000L
    private const val KEEP_RECEIVED = 5

    // ---- sending ----

    private class Outgoing(
        /** Who it goes to, by private message. */
        val to: String,
        val source: ShareFormat.Source,
    ) {
        var messages: List<String>? = null
        var next = 0
        var sent = 0
        var ticks = 0
        var lastSent = -CLOCK_STOPPED
        /** The world's clock at the last message, which follows the server's ticks. */
        var lastSentTime = Long.MIN_VALUE / 2
        /** When [to] was asked whether they have the mod; 0 before. */
        var askedAt = 0L
    }

    private var outgoing: Outgoing? = null

    /** Transfers this client sent, whose echo comes back through chat like anyone else's. */
    private val ownIds = HashSet<Int>()

    /** Transfers already put together, so a part that arrives twice starts nothing new. */
    private val finished = HashSet<Int>()

    /** Players known to have this mod, lower case: they answered, asked, or sent something. */
    private val withMod = HashSet<String>()

    /** When each player who asked was last answered, so a burst of asks gets one answer. */
    private val answered = HashMap<String, Long>()

    val sending: Boolean get() = outgoing != null

    /** The names of everyone else on the server, to share with. */
    fun others(): List<String> {
        val minecraft = Minecraft.getInstance()
        val me = minecraft.player?.gameProfile?.name()
        return minecraft.connection?.onlinePlayers.orEmpty().map { it.profile.name() }.filter { it != me }.sortedBy { it.lowercase() }
    }

    private fun me(): String = Minecraft.getInstance().player?.gameProfile?.name() ?: "?"

    /** Shares [waypoints] of [dimensionId] with player [to]. */
    fun shareWaypoints(to: String, dimensionId: String, waypoints: List<ShareFormat.SharedWaypoint>): String {
        if (outgoing != null) return busy()
        val name = others().firstOrNull { it.equals(to, ignoreCase = true) } ?: return "$to is not on the server"
        if (waypoints.isEmpty()) return "No waypoints picked"
        if (waypoints.size > ShareFormat.MAX_WAYPOINTS) return "That is ${waypoints.size} waypoints; pick at most ${ShareFormat.MAX_WAYPOINTS}"
        val area = ShareFormat.Area(
            Math.floorDiv(waypoints.minOf { it.x }, 16), Math.floorDiv(waypoints.minOf { it.z }, 16),
            Math.floorDiv(waypoints.maxOf { it.x }, 16), Math.floorDiv(waypoints.maxOf { it.z }, 16),
        )
        val header = ShareFormat.Header(me(), dimensionId, ThreadLocalRandom.current().nextInt(1 shl 30), 0, 1, area.takeIf {
            it.width <= ShareFormat.MAX_SIDE && it.height <= ShareFormat.MAX_SIDE
        } ?: ShareFormat.Area(area.left, area.top, area.left, area.top))
        val source = ShareFormat.WaypointSource(ShareFormat.packWaypoints(header, waypoints), waypoints.size)
        outgoing = Outgoing(name, source)
        return "Sharing ${source.label} with $name…"
    }

    private fun busy() = "Already sharing with someone; /mapexposer share cancel stops it"

    /** Watches the server's own messages while sharing: being muted or the server having no /msg stops it. */
    private fun watch(text: String) {
        if (outgoing == null) return
        if (MARKER in text || MapMenus.saying) return
        when {
            MUTED.containsMatchIn(text) -> stop("Sharing stopped: the server muted you (\"$text\")")
            NO_MSG.containsMatchIn(text) && "msg" in text.lowercase() -> stop("Sharing stopped: the server has no /msg to share through")
        }
    }

    fun cancel(): Boolean {
        val out = outgoing ?: return false
        outgoing = null
        Log.info("Sharing with {} cancelled after {} messages", out.to, out.sent)
        return true
    }

    /** Leaving the server: nothing more can be sent, and what was half received is useless. */
    fun forget() {
        outgoing = null
        incoming.clear()
        ownIds.clear()
        finished.clear()
        receivedWaypoints.clear()
        withMod.clear()
        answered.clear()
    }

    fun tick() {
        outgoing?.let { tickOutgoing(it) }
        val now = System.currentTimeMillis()
        incoming.entries.removeIf { (id, inc) ->
            (now - inc.lastSeen > INCOMPLETE_WAIT).also {
                if (it) Log.warn("A batch shared in chat (transfer {}) stopped at {} of {} parts; dropped it", id, inc.have, inc.total)
            }
        }
    }

    private fun stop(message: String) {
        outgoing = null
        MapMenus.say(message)
        Log.info("Sharing: {}", message)
    }

    private fun tickOutgoing(out: Outgoing) {
        val minecraft = Minecraft.getInstance()
        val connection = minecraft.player?.connection ?: return
        val source = out.source
        out.ticks++
        val time = minecraft.level?.gameTime ?: 0L
        val since = out.ticks - out.lastSent
        val mayTalk = since >= TICKS_PER_MESSAGE && (time - out.lastSentTime >= TICKS_PER_MESSAGE || since >= CLOCK_STOPPED)
        if (others().none { it == out.to }) return stop("Sharing stopped: ${out.to} left the server")

        // First: does the other end have this mod? Asked once, before anything is read.
        if (out.to.lowercase() !in withMod) {
            val now = System.currentTimeMillis()
            if (out.askedAt == 0L && mayTalk) {
                // The one line someone without the mod may see, so it says what it is.
                connection.sendCommand(
                    "msg ${out.to} $MARKER$ASK${me()}: would like to share map ${source.label} with you using the mod " +
                        "Sky's Map Exposer. You can ignore this."
                )
                out.askedAt = now
                out.lastSent = out.ticks
                out.lastSentTime = time
                MapMenus.say("Checking that ${out.to} has Sky's Map Exposer…")
            } else if (out.askedAt != 0L && now - out.askedAt > ANSWER_WAIT) {
                stop("${out.to} did not answer, so they probably do not have Sky's Map Exposer; nothing was sent")
            }
            return
        }

        val messages = out.messages
        if (messages == null) {
            when (val step = source.next()) {
                is Step.Waiting -> {}
                is Step.Failed -> stop("Sharing stopped: ${step.message}")
                is Step.Done -> stop("Shared ${source.count} ${source.label} with ${out.to} in ${out.sent} messages")
                is Step.Ready -> {
                    val command = "msg ${out.to} "
                    val parts = toChars(step.bytes).chunked(CHAT_LIMIT - command.length - MARKER.length - HEADER)
                    if (parts.size > MAX_MESSAGES) return stop("A batch would take ${parts.size} messages; pick fewer")
                    val id = ThreadLocalRandom.current().nextInt(1 shl (2 * BITS))
                    ownIds.add(id)
                    out.messages = parts.mapIndexed { i, text ->
                        command + MARKER + char(id shr BITS) + char(id and (1 shl BITS) - 1) + char(i) + char(parts.size) + text
                    }
                    Log.info(
                        "Sharing {} with {}: batch {} of {}, {} in it, {} bytes, {} messages",
                        source.label, out.to, step.batch + 1, source.batches, step.count, step.bytes.size, parts.size,
                    )
                }
            }
            return
        }
        if (!mayTalk) return
        send(out, connection, messages[out.next++], time)
        if (out.next < messages.size) {
            if (minecraft.gui.screen() == null || out.next % 4 == 0) {
                MapMenus.say("Sharing ${source.label} with ${out.to}: message ${out.next} of ${messages.size}")
            }
            return
        }
        // That batch is out; on to the next.
        out.messages = null
        out.next = 0
    }

    private fun send(out: Outgoing, connection: ClientPacketListener, command: String, time: Long) {
        connection.sendCommand(command)
        out.lastSent = out.ticks
        out.lastSentTime = time
        out.sent++
    }

    // ---- characters chat lets through ----

    // The 2^15 characters: CJK Extension A, CJK Unified Ideographs, then Hangul syllables. All
    // assigned, none combining, none that Unicode normalisation changes.
    private const val EXT_A = 0x3400
    private const val EXT_A_SIZE = 0x4DC0 - 0x3400
    private const val CJK = 0x4E00
    private const val CJK_SIZE = 0xA000 - 0x4E00
    private const val HANGUL = 0xAC00

    private fun char(value: Int): Char = when {
        value < EXT_A_SIZE -> EXT_A + value
        value < EXT_A_SIZE + CJK_SIZE -> CJK + value - EXT_A_SIZE
        else -> HANGUL + value - EXT_A_SIZE - CJK_SIZE
    }.toChar()

    /** The value of [c], or -1 if it is not one of the characters. */
    private fun value(c: Char): Int = when (c.code) {
        in EXT_A until EXT_A + EXT_A_SIZE -> c.code - EXT_A
        in CJK until CJK + CJK_SIZE -> c.code - CJK + EXT_A_SIZE
        in HANGUL until HANGUL + (1 shl BITS) - EXT_A_SIZE - CJK_SIZE -> c.code - HANGUL + EXT_A_SIZE + CJK_SIZE
        else -> -1
    }

    private fun toChars(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 8 / BITS + 1)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            if (bits >= BITS) {
                bits -= BITS
                out.append(char((buffer shr bits) and (1 shl BITS) - 1))
            }
        }
        if (bits > 0) out.append(char((buffer shl (BITS - bits)) and (1 shl BITS) - 1))
        return out.toString()
    }

    /** The bytes back; a padding byte at the end may come along, which the length in the batch cuts off. */
    private fun fromChars(chars: String): ByteArray {
        val out = ByteArrayOutputStream(chars.length * BITS / 8 + 1)
        var buffer = 0
        var bits = 0
        for (c in chars) {
            buffer = (buffer shl BITS) or value(c)
            bits += BITS
            while (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    // ---- receiving ----

    private class Incoming(val total: Int) {
        val parts = arrayOfNulls<String>(total)
        var have = 0
        var lastSeen = System.currentTimeMillis()
    }

    private class ReceivedWaypoints(val from: String, val dimensionId: String, val waypoints: List<ShareFormat.SharedWaypoint>)

    private val incoming = HashMap<Int, Incoming>()
    private val receivedWaypoints = LinkedHashMap<Int, ReceivedWaypoints>()

    fun register() {
        // Private messages arrive as player chat, or as system messages from a server's own /msg.
        ClientReceiveMessageEvents.ALLOW_CHAT.register { message, signed, _, _, _ ->
            !(receive(message.string) || receive(signed?.signedContent()))
        }
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, _ ->
            val text = message.string
            if (!receive(text)) {
                watch(text)
                true
            } else false
        }
    }

    /** Takes in a part of a share if [text] is one; true means it was, and is hidden from chat. */
    private fun receive(text: String?): Boolean {
        if (text == null) return false
        val at = text.indexOf(MARKER)
        if (at < 0) return false
        try {
            val rest = text.substring(at + MARKER.length)
            when (rest.firstOrNull()) {
                ASK -> return true.also { asked(rest.drop(1).substringBefore(':')) }
                ANSWER -> return true.also { name(rest.drop(1))?.let { withMod.add(it.lowercase()) } }
            }
            val digits = rest.takeWhile { value(it) >= 0 }
            if (digits.length <= HEADER) return true
            fun v(i: Int) = value(digits[i])
            val id = (v(0) shl BITS) or v(1)
            val part = v(2)
            val total = v(3)
            if (id in ownIds || id in finished || total == 0 || total > MAX_MESSAGES || part >= total) return true
            val inc = incoming.getOrPut(id) { Incoming(total) }
            if (inc.total != total) return true
            inc.lastSeen = System.currentTimeMillis()
            if (inc.parts[part] == null) {
                inc.parts[part] = digits.substring(HEADER)
                inc.have++
            }
            if (inc.have == total) {
                incoming.remove(id)
                finished.add(id)
                batchArrived(inc)
            }
        } catch (e: Throwable) {
            Log.error("Could not read a part of something shared in chat", e)
        }
        return true
    }

    /** A player name at the start of [text], or null. */
    private fun name(text: String): String? = text.takeWhile { it.isLetterOrDigit() || it == '_' }.takeIf { it.length in 1..16 }

    /** [who] asked whether this client has the mod: it has, so it says so, once in a while at most. */
    private fun asked(who: String) {
        val name = name(who) ?: return
        val minecraft = Minecraft.getInstance()
        val me = minecraft.player?.gameProfile?.name() ?: return
        if (name.equals(me, ignoreCase = true)) return // our own ask, echoed back
        withMod.add(name.lowercase())
        val now = System.currentTimeMillis()
        if (now - (answered[name.lowercase()] ?: 0L) < ANSWER_WAIT) return
        answered[name.lowercase()] = now
        minecraft.player?.connection?.sendCommand("msg $name $MARKER$ANSWER$me")
    }

    private fun batchArrived(inc: Incoming) {
        try {
            ShareFormat.open(fromChars(inc.parts.joinToString(""))) { opened ->
                val header = opened.header
                withMod.add(header.from.lowercase())
                when (opened.format) {
                    ShareFormat.WAYPOINTS -> waypointsArrived(header, ShareFormat.readWaypoints(opened.input, opened.count))
                    else -> error("only waypoints are shared in chat")
                }
            }
        } catch (e: Throwable) {
            Log.error("Could not read a batch shared in chat", e)
            Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§7Part of something shared with you could not be read; the log says why"))
        }
    }

    private fun waypointsArrived(header: ShareFormat.Header, waypoints: List<ShareFormat.SharedWaypoint>) {
        receivedWaypoints[header.shareId] = ReceivedWaypoints(header.from, header.dimensionId, waypoints)
        while (receivedWaypoints.size > KEEP_RECEIVED) receivedWaypoints.remove(receivedWaypoints.keys.first())
        Log.info("Received {} waypoints of the {} from {}, share {}", waypoints.size, header.dimensionId, header.from, header.shareId)
        val name = MapMenus.dimensionName(header.dimensionId) ?: header.dimensionId
        val list = waypoints.take(15).joinToString("\n") { "${it.name}  ${it.x}, ${if (it.yIncluded) "${it.y}, " else ""}${it.z}" } +
            if (waypoints.size > 15) "\n…and ${waypoints.size - 15} more" else ""
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal("§b${header.from}§r is sharing ${waypoints.size} waypoint${if (waypoints.size == 1) "" else "s"} in the $name  ")
                .append(ChatButton.of("[Add waypoints]", "/mapexposer shared ${header.shareId} waypoints", list))
        )
    }

    /** Adds shared waypoints [id] to yours. Returns what to tell the player. */
    fun addWaypoints(id: Int): String {
        val shared = receivedWaypoints[id] ?: return "Those shared waypoints are no longer here"
        if (!Waypoints.available()) return "Adding waypoints needs Xaero's Minimap"
        return WaypointShare.add(shared.dimensionId, shared.from, shared.waypoints)
    }
}
