package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.ShareFormat.Step
import com.skystormer.skysmapexposer.ShareInbox.Companion.ASK
import com.skystormer.skysmapexposer.ShareInbox.Companion.ANSWER_WAIT
import com.skystormer.skysmapexposer.ShareInbox.Companion.MARKER
import com.skystormer.skysmapexposer.ShareInbox.Companion.MAX_MESSAGES
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import java.util.concurrent.Executors
import java.util.concurrent.ThreadLocalRandom

/**
 * Sharing waypoints you pick ([WaypointShare]) with another player of this mod through private
 * messages (`/msg`), so it works on any server without anything installed there, and nobody else
 * sees it. Terrain is far too big for chat; it goes by file ([TerrainFiles]).
 *
 * Each batch ([ShareFormat]) is written as characters chat lets through ([ChatChars]) and sent as
 * numbered private messages. A vanilla server adds 20 to a player's spam count per message, takes
 * 1 off per server tick and kicks above 200; so messages go out [TICKS_PER_MESSAGE] server ticks
 * apart (by the world's clock, which slows when the server lags), leaving room for the player's
 * own chat. Being muted, or a server without /msg, stops it.
 *
 * Nobody without this mod is sent any of that. A player not yet known to have it first gets one
 * readable line saying what this is; their client, if it has the mod, hides it and answers, and
 * only then does the rest follow. With no answer, nothing more is sent.
 *
 * Receiving ([ShareInbox]): only private messages count, from the player the share names. Valid
 * parts are hidden from chat; a button adds the waypoints to yours.
 */
object ChatShare {

    /** Server ticks between messages: a quarter more than the 20 a vanilla server's spam limit needs. */
    private const val TICKS_PER_MESSAGE = 25
    /** Client ticks after which a message goes anyway, if the world's clock is stopped (`/tick freeze`). */
    private const val CLOCK_STOPPED = 100

    private val MUTED = Regex("(you are|you're|you have been) (now )?muted([^a-z]|$)", RegexOption.IGNORE_CASE)
    private val NO_MSG = Regex("unknown (or incomplete )?command", RegexOption.IGNORE_CASE)
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
        inbox.forget()
        receivedWaypoints.clear()
    }

    fun tick() {
        outgoing?.let { tickOutgoing(it) }
        inbox.tick()
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
        if (!inbox.hasMod(out.to)) {
            val now = System.currentTimeMillis()
            if (out.askedAt == 0L && mayTalk) {
                // The one line someone without the mod may see, so it says what it is.
                val body = "$MARKER$ASK${me()}: would like to share map ${source.label} with you using the mod " +
                    "Sky's Map Exposer. You can ignore this."
                inbox.sent(body)
                connection.sendCommand("msg ${out.to} $body")
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
                    val parts = ShareInbox.messages(out.to, step.bytes, ThreadLocalRandom.current().nextInt(1 shl (2 * ChatChars.BITS)))
                    if (parts.isEmpty()) return stop("A batch would take more than $MAX_MESSAGES messages; pick fewer")
                    out.messages = parts
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

    private fun send(out: Outgoing, connection: ClientPacketListener, body: String, time: Long) {
        inbox.sent(body)
        connection.sendCommand("msg ${out.to} $body")
        out.lastSent = out.ticks
        out.lastSentTime = time
        out.sent++
    }

    // ---- receiving ----

    private class ReceivedWaypoints(val from: String, val dimensionId: String, val waypoints: List<ShareFormat.SharedWaypoint>)

    private val receivedWaypoints = LinkedHashMap<Int, ReceivedWaypoints>()

    private val unpacking = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SkysMapExposer chat share").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    private val inbox = ShareInbox(
        me = { Minecraft.getInstance().player?.gameProfile?.name() },
        online = { name -> others().firstOrNull { it.equals(name, ignoreCase = true) } },
        command = { Minecraft.getInstance().player?.connection?.sendCommand(it) },
        arrived = ::waypointsArrived,
        failed = { e ->
            Log.error("Could not read a batch shared in chat", e)
            Minecraft.getInstance().player?.sendSystemMessage(Component.literal("§7Part of something shared with you could not be read; the log says why"))
        },
        decode = unpacking,
        onClient = { Minecraft.getInstance().execute(it) },
    )

    fun register() {
        // Vanilla private messages arrive as player chat, whose sender the game knows.
        ClientReceiveMessageEvents.ALLOW_CHAT.register { _, signed, sender, params, _ ->
            val kind = if (params.chatType().`is`(ChatType.MSG_COMMAND_INCOMING)) ShareInbox.Kind.PRIVATE else ShareInbox.Kind.OTHER
            !guarded { inbox.chat(kind, sender?.name(), signed?.signedContent()) }
        }
        // A chat plugin's /msg, or a server without signed chat, arrives as a system message.
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            if (overlay) return@register true
            val text = message.string
            if (!guarded { inbox.system(whisperText(message) ?: text) }) {
                watch(text)
                true
            } else false
        }
    }

    private inline fun guarded(take: () -> Boolean): Boolean = try {
        take()
    } catch (e: Throwable) {
        Log.error("Could not read something shared in chat", e)
        false
    }

    /** Vanilla's incoming whisper sent as a system message, in English whatever the game's language. */
    private fun whisperText(message: Component): String? {
        val contents = message.contents as? TranslatableContents ?: return null
        if (contents.key != "commands.message.display.incoming") return null
        val args = contents.args
        if (args.size != 2) return null
        fun text(arg: Any?) = (arg as? Component)?.string ?: arg?.toString() ?: ""
        return "${text(args[0])} whispers to you: ${text(args[1])}"
    }

    private fun waypointsArrived(received: ShareInbox.Received) {
        val waypoints = received.waypoints
        receivedWaypoints[received.shareId] = ReceivedWaypoints(received.from, received.dimensionId, waypoints)
        while (receivedWaypoints.size > KEEP_RECEIVED) receivedWaypoints.remove(receivedWaypoints.keys.first())
        Log.info(
            "Received {} waypoints of the {} from {} ({}), share {}", waypoints.size, received.dimensionId, received.from,
            if (received.verified) "verified" else "unverified", received.shareId,
        )
        val name = MapMenus.dimensionName(received.dimensionId) ?: received.dimensionId
        val list = waypoints.take(15).joinToString("\n") { "${it.name}  ${it.x}, ${if (it.yIncluded) "${it.y}, " else ""}${it.z}" } +
            if (waypoints.size > 15) "\n…and ${waypoints.size - 15} more" else ""
        val unverified = if (received.verified) "" else " §7(unverified)§r"
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal("§b${received.from}§r$unverified is sharing ${waypoints.size} waypoint${if (waypoints.size == 1) "" else "s"} in the $name  ")
                .append(ChatButton.of("[Add waypoints]", "/mapexposer shared ${received.shareId} waypoints", list))
        )
    }

    /** Adds shared waypoints [id] to yours. Returns what to tell the player. */
    fun addWaypoints(id: Int): String {
        val shared = receivedWaypoints[id] ?: return "Those shared waypoints are no longer here"
        if (!Waypoints.available()) return "Adding waypoints needs Xaero's Minimap"
        return WaypointShare.add(shared.dimensionId, shared.from, shared.waypoints)
    }
}
