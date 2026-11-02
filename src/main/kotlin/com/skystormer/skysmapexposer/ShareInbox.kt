package com.skystormer.skysmapexposer

import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/**
 * The receiving end of waypoints shared in chat ([ChatShare]), apart from the game so it can be
 * tested. Anyone on a server can send chat, so nothing is taken on someone's word:
 *
 * - A share counts only as a private message. On a vanilla server that is player chat of the
 *   incoming `/msg` kind, whose real sender the game knows ([chat]); in public chat it is ignored.
 *   Where a chat plugin delivers `/msg` as a system message ([system]), the sender is read from a
 *   whisper line of a known format ([WHISPERS]) and must be online; the game cannot check it, so
 *   such shares are labelled unverified. Either way the name inside the share must be the sender's.
 * - Only a valid part, ask or answer is hidden from chat; anything else carrying [MARKER] stays.
 * - Asks are answered only as private messages, at most [ANSWERS_PER_MINUTE] a minute.
 * - A batch is limited before and after it is unpacked ([MAX_MESSAGES], [ShareFormat.MAX_BATCH]),
 *   few transfers are kept half done, and batches are unpacked off the client thread.
 */
class ShareInbox(
    /** This player's name, or null when not in a world. */
    private val me: () -> String?,
    /** The online player of that name as the tab list spells it, or null if nobody of that name is on. */
    private val online: (String) -> String?,
    /** Sends a command, without the slash. */
    private val command: (String) -> Unit,
    /** Waypoints that arrived, given on [onClient]. */
    private val arrived: (Received) -> Unit,
    /** A batch that could not be read, given on [onClient]. */
    private val failed: (Throwable) -> Unit,
    /** Where batches are unpacked. */
    private val decode: Executor,
    /** The client thread. */
    private val onClient: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Where a chat message came from, as the game tells it. */
    enum class Kind {
        /** Someone's `/msg` to you: the game knows who sent it. */
        PRIVATE,
        /** Anything else: public chat, team messages, `/say`, your own `/msg` echoed. */
        OTHER,
    }

    class Received(
        val from: String,
        /** Whether the game vouched for [from], or it was read from a chat plugin's whisper line. */
        val verified: Boolean,
        val dimensionId: String,
        val shareId: Int,
        val waypoints: List<ShareFormat.SharedWaypoint>,
    )

    private class Incoming(val from: String, val verified: Boolean, val total: Int, now: Long) {
        val parts = arrayOfNulls<String>(total)
        var have = 0
        var lastSeen = now
    }

    /** Unfinished transfers by sender and transfer number, oldest first. */
    private val incoming = LinkedHashMap<String, Incoming>()

    /** Transfers already put together, so a part that arrives twice starts nothing new. */
    private val finished = LinkedHashSet<String>()

    /** What this client sent lately, so its echo (the server's "You whisper to…") is hidden. */
    private val sent = LinkedHashSet<String>()

    /** Players known to have this mod, lower case: they answered, asked, or sent something. */
    private val withMod = HashSet<String>()

    /** When each player who asked was last answered, so a burst of asks gets one answer. */
    private val answered = HashMap<String, Long>()

    /** When the answers of the last minute went out. */
    private val answers = ArrayDeque<Long>()

    fun hasMod(name: String) = name.lowercase() in withMod

    /** Remembers [body] as sent by this client, so its echo is hidden. */
    fun sent(body: String) {
        sent.add(body)
        while (sent.size > KEEP_SENT) sent.remove(sent.first())
    }

    /** Player chat: [content] as [sender] wrote it. True means it was part of a share and is hidden. */
    fun chat(kind: Kind, sender: String?, content: String?): Boolean {
        if (content == null || MARKER !in content) return false
        val me = me()
        if (sender != null && me != null && sender.equals(me, ignoreCase = true) && content in sent) return true
        if (kind != Kind.PRIVATE || sender == null || !validName(sender)) return false
        return take(content, sender, verified = true)
    }

    /** A system message, as shown. True means it was part of a share and is hidden. */
    fun system(text: String): Boolean {
        if (MARKER !in text) return false
        val plain = ShareFormat.stripCodes(text).trim()
        if (sent.any { it in plain }) return true
        val (name, body) = whisper(plain) ?: return false
        val sender = online(name) ?: return false
        if (me()?.equals(sender, ignoreCase = true) == true) return false
        return take(body, sender, verified = false)
    }

    /** Takes in [body] from [from] if it is part of a share; true means it was, and is hidden. */
    private fun take(body: String, from: String, verified: Boolean): Boolean {
        if (!body.startsWith(MARKER)) return false
        val rest = body.substring(MARKER.length).trimEnd()
        when (rest.firstOrNull()) {
            ASK -> {
                if (!rest.drop(1).substringBefore(':').equals(from, ignoreCase = true)) return false
                asked(from)
                return true
            }
            ANSWER -> {
                if (!rest.drop(1).equals(from, ignoreCase = true)) return false
                withMod.add(from.lowercase())
                return true
            }
        }
        if (rest.length <= HEADER || rest.any { ChatChars.value(it) < 0 }) return false
        fun v(i: Int) = ChatChars.value(rest[i])
        val id = (v(0) shl ChatChars.BITS) or v(1)
        val part = v(2)
        val total = v(3)
        if (total == 0 || total > MAX_MESSAGES || part >= total) return false
        val key = "${from.lowercase()}:$id"
        if (key in finished) return true
        val now = clock()
        val inc = incoming[key] ?: run {
            makeRoom(from)
            Incoming(from, verified, total, now).also { incoming[key] = it }
        }
        if (inc.total != total) return false
        inc.lastSeen = now
        if (inc.parts[part] == null) {
            inc.parts[part] = rest.substring(HEADER)
            inc.have++
        }
        if (inc.have == total) {
            incoming.remove(key)
            finished.add(key)
            while (finished.size > KEEP_FINISHED) finished.remove(finished.first())
            withMod.add(from.lowercase())
            unpack(inc)
        }
        return true
    }

    /** Drops the oldest unfinished transfers so a new one from [from] fits. */
    private fun makeRoom(from: String) {
        val theirs = incoming.entries.filter { it.value.from.equals(from, ignoreCase = true) }
        if (theirs.size >= MAX_PER_SENDER) drop(theirs.first().key)
        if (incoming.size >= MAX_INCOMING) drop(incoming.keys.first())
    }

    private fun drop(key: String) {
        val inc = incoming.remove(key) ?: return
        Log.warn("Dropped an unfinished batch shared in chat by {} ({} of {} parts) to make room", inc.from, inc.have, inc.total)
    }

    private fun unpack(inc: Incoming) {
        val chars = inc.parts.joinToString("")
        decode.execute {
            val result = runCatching { read(ChatChars.fromChars(chars), inc.from, inc.verified) }
            onClient.execute { result.fold(arrived, failed) }
        }
    }

    /** [from] asked whether this client has the mod: it has, so it says so, now and then at most. */
    private fun asked(from: String) {
        val me = me() ?: return
        if (from.equals(me, ignoreCase = true)) return
        val key = from.lowercase()
        withMod.add(key)
        val now = clock()
        if (now - (answered[key] ?: Long.MIN_VALUE / 2) < ANSWER_WAIT) return
        while (answers.isNotEmpty() && now - answers.first() >= MINUTE) answers.removeFirst()
        if (answers.size >= ANSWERS_PER_MINUTE) return
        answers.addLast(now)
        answered[key] = now
        val body = "$MARKER$ANSWER$me"
        sent(body)
        command("msg $from $body")
    }

    /** Drops transfers that stopped arriving. */
    fun tick() {
        val now = clock()
        incoming.entries.removeIf { (_, inc) ->
            (now - inc.lastSeen > INCOMPLETE_WAIT).also {
                if (it) Log.warn("A batch shared in chat by {} stopped at {} of {} parts; dropped it", inc.from, inc.have, inc.total)
            }
        }
    }

    /** Leaving the server. */
    fun forget() {
        incoming.clear()
        finished.clear()
        sent.clear()
        withMod.clear()
        answered.clear()
        answers.clear()
    }

    companion object {
        const val MARKER = "⟪SME⟫"
        /** After [MARKER]: "would you take a map?", readable by anyone; and the hidden answer. */
        const val ASK = '?'
        const val ANSWER = '!'
        const val ANSWER_WAIT = 15_000L
        const val ANSWERS_PER_MINUTE = 3
        private const val MINUTE = 60_000L
        /** The longest command chat sends, `msg <name> ` included. */
        const val CHAT_LIMIT = 256
        /** Transfer number (two characters), part number, number of parts. */
        const val HEADER = 4
        /** Characters of a batch in one message to a player of the longest name. */
        private const val PER_MESSAGE = CHAT_LIMIT - "msg ".length - 16 - 1 - MARKER.length - HEADER

        /** Messages the biggest batch can take: [ShareFormat.MAX_BATCH] bytes, plus what packing adds. */
        const val MAX_MESSAGES = ((ShareFormat.MAX_BATCH + 1024) * 8 / ChatChars.BITS + PER_MESSAGE - 1) / PER_MESSAGE

        const val MAX_PER_SENDER = 3
        const val MAX_INCOMING = 10
        private const val INCOMPLETE_WAIT = 300_000L
        private const val KEEP_FINISHED = 64
        private const val KEEP_SENT = 32

        /**
         * Whisper lines of chat plugins that deliver `/msg` as a system message: the sender and
         * what they wrote. Codes are stripped first. The vanilla line, which servers without signed
         * chat send as a system message; EssentialsX's `[Name -> me]` (what this mod's own server
         * prints); and the `From Name:` of proxies and Hypixel-like servers, with an optional rank.
         */
        private val WHISPERS = listOf(
            Regex("""^(\w{3,16}) whispers to you: (.*)$"""),
            Regex("""^\[(\w{3,16}) (?:->|→|»|>>) (?:me|you)] (.*)$""", RegexOption.IGNORE_CASE),
            Regex("""^From (?:\[[^\]]{1,24}] )?(\w{3,16}): (.*)$"""),
        )

        /** The sender and message of a whisper line [plain] (codes stripped), or null if it is not one. */
        fun whisper(plain: String): Pair<String, String>? {
            for (format in WHISPERS) {
                val match = format.matchEntire(plain) ?: continue
                return match.groupValues[1] to match.groupValues[2]
            }
            return null
        }

        private val NAME = Regex("[A-Za-z0-9_]{3,16}")

        /** A real Minecraft name: 3 to 16 letters, digits and underscores. */
        fun validName(name: String) = NAME.matches(name)

        private val DIMENSION = Regex("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,64}")

        /** The messages, without `msg <name> `, that carry [bytes] as transfer [id] to [to]. */
        fun messages(to: String, bytes: ByteArray, id: Int): List<String> {
            val parts = ChatChars.toChars(bytes).chunked(CHAT_LIMIT - "msg $to ".length - MARKER.length - HEADER)
            if (parts.size > MAX_MESSAGES) return emptyList()
            return parts.mapIndexed { i, text ->
                MARKER + ChatChars.char(id shr ChatChars.BITS) + ChatChars.char(id and (1 shl ChatChars.BITS) - 1) +
                    ChatChars.char(i) + ChatChars.char(parts.size) + text
            }
        }

        /** Unpacks a batch of waypoints sent by [from]; anything else, or anyone else's, is turned away. */
        fun read(bytes: ByteArray, from: String, verified: Boolean): Received =
            ShareFormat.open(bytes, ShareFormat.MAX_BATCH) { opened ->
                if (opened.format != ShareFormat.WAYPOINTS) error("only waypoints are shared in chat")
                val header = opened.header
                if (!header.from.equals(from, ignoreCase = true)) error("$from sent it but it says it is from ${ShareFormat.clean(header.from)}")
                if (!DIMENSION.matches(header.dimensionId)) error("not a dimension: ${ShareFormat.clean(header.dimensionId)}")
                Received(from, verified, header.dimensionId, header.shareId, ShareFormat.readWaypoints(opened.input, opened.count))
            }
    }
}

/**
 * Characters chat lets through, 15 bits each: CJK Extension A, CJK Unified Ideographs, then
 * Hangul syllables. All assigned, none combining, none that Unicode normalisation changes.
 */
object ChatChars {
    const val BITS = 15
    private const val EXT_A = 0x3400
    private const val EXT_A_SIZE = 0x4DC0 - 0x3400
    private const val CJK = 0x4E00
    private const val CJK_SIZE = 0xA000 - 0x4E00
    private const val HANGUL = 0xAC00

    fun char(value: Int): Char = when {
        value < EXT_A_SIZE -> EXT_A + value
        value < EXT_A_SIZE + CJK_SIZE -> CJK + value - EXT_A_SIZE
        else -> HANGUL + value - EXT_A_SIZE - CJK_SIZE
    }.toChar()

    /** The value of [c], or -1 if it is not one of the characters. */
    fun value(c: Char): Int = when (c.code) {
        in EXT_A until EXT_A + EXT_A_SIZE -> c.code - EXT_A
        in CJK until CJK + CJK_SIZE -> c.code - CJK + EXT_A_SIZE
        in HANGUL until HANGUL + (1 shl BITS) - EXT_A_SIZE - CJK_SIZE -> c.code - HANGUL + EXT_A_SIZE + CJK_SIZE
        else -> -1
    }

    fun toChars(bytes: ByteArray): String {
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
    fun fromChars(chars: String): ByteArray {
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
}
