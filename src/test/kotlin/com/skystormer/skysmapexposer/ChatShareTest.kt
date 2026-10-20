package com.skystormer.skysmapexposer

import com.skystormer.skysmapexposer.ShareInbox.Companion.MARKER
import com.skystormer.skysmapexposer.ShareInbox.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Executor

/** Anyone on a server can send chat: only real private messages from the named player are taken in. */
class ChatShareTest {

    private class Client(name: String, vararg online: String) {
        var now = 1_000_000L
        val commands = ArrayList<String>()
        val received = ArrayList<ShareInbox.Received>()
        val failures = ArrayList<Throwable>()
        val players = online.toList()
        val inbox = ShareInbox(
            me = { name },
            online = { wanted -> players.firstOrNull { it.equals(wanted, ignoreCase = true) } },
            command = { commands.add(it) },
            arrived = { received.add(it) },
            failed = { failures.add(it) },
            decode = Executor { it.run() },
            onClient = Executor { it.run() },
            clock = { now },
        )
    }

    private fun ask(from: String) = "$MARKER?$from: would like to share map waypoints with you using the mod Sky's Map Exposer."

    private fun waypoints(from: String, vararg list: ShareFormat.SharedWaypoint): ByteArray {
        val header = ShareFormat.Header(from, "minecraft:overworld", 77, 0, 1, ShareFormat.Area(0, 0, 0, 0))
        return ShareFormat.packWaypoints(header, list.toList())
    }

    private fun waypoint(name: String, color: Int = 3) = ShareFormat.SharedWaypoint(name, "", 10, 64, -20, true, color)

    @Test
    fun `a share in public chat is ignored and stays visible`() {
        val bob = Client("Bob", "Alice")
        val part = ShareInbox.messages("Bob", waypoints("Alice", waypoint("Home")), 5).single()
        assertFalse(bob.inbox.chat(Kind.OTHER, "Alice", part))
        assertFalse(bob.inbox.chat(Kind.OTHER, "Alice", ask("Alice")))
        assertTrue(bob.received.isEmpty())
        assertTrue(bob.commands.isEmpty())
        // A public line printed by a server as a system message is not a whisper either.
        assertFalse(bob.inbox.system("<Alice> $part"))
        assertFalse(bob.inbox.system("Alice: $part"))
        assertTrue(bob.received.isEmpty())
    }

    @Test
    fun `a share whose claimed sender is not the real sender is ignored`() {
        val bob = Client("Bob", "Alice", "Mallory")
        val forged = ShareInbox.messages("Bob", waypoints("Alice", waypoint("Trap")), 5).single()
        assertTrue(bob.inbox.chat(Kind.PRIVATE, "Mallory", forged)) // a valid part, so hidden…
        assertTrue(bob.received.isEmpty()) // …but turned away once read
        assertEquals(1, bob.failures.size)
        // An ask or answer naming someone else stays in chat and gets nothing.
        assertFalse(bob.inbox.chat(Kind.PRIVATE, "Mallory", ask("Alice")))
        assertFalse(bob.inbox.chat(Kind.PRIVATE, "Mallory", "$MARKER!Alice"))
        assertFalse(bob.inbox.hasMod("Alice"))
        assertTrue(bob.commands.isEmpty())
    }

    @Test
    fun `a plugin whisper is taken only from a known format and an online player, and is unverified`() {
        val bob = Client("Bob", "Alice")
        val part = ShareInbox.messages("Bob", waypoints("Alice", waypoint("Home")), 5).single()
        assertFalse(bob.inbox.system("[Ghost -> me] $part")) // not online
        assertFalse(bob.inbox.system("Alice says: $part")) // not a whisper line
        assertTrue(bob.inbox.system("§6[§cAlice§6 -> §cme§6] §r$part"))
        assertFalse(bob.received.single().verified)
        assertEquals("Alice", bob.received.single().from)

        assertEquals("Alice" to "hi", ShareInbox.whisper("Alice whispers to you: hi"))
        assertEquals("Alice" to "hi", ShareInbox.whisper("From [MVP+] Alice: hi"))
        assertNull(ShareInbox.whisper("[Bob] [Alice -> me] hi"))
    }

    @Test
    fun `asks from strangers in public chat get no reply, and answers stop at 3 a minute`() {
        val bob = Client("Bob", "A1x", "B2x", "C3x", "D4x", "E5x")
        assertFalse(bob.inbox.chat(Kind.OTHER, "A1x", ask("A1x")))
        assertTrue(bob.commands.isEmpty())

        for (name in listOf("A1x", "B2x", "C3x", "D4x", "E5x")) assertTrue(bob.inbox.chat(Kind.PRIVATE, name, ask(name)))
        assertEquals(listOf("msg A1x ${MARKER}!Bob", "msg B2x ${MARKER}!Bob", "msg C3x ${MARKER}!Bob"), bob.commands)

        bob.now += 61_000
        assertTrue(bob.inbox.chat(Kind.PRIVATE, "D4x", ask("D4x")))
        assertEquals("msg D4x ${MARKER}!Bob", bob.commands.last())
    }

    @Test
    fun `only valid shares are hidden`() {
        val bob = Client("Bob", "Alice")
        assertFalse(bob.inbox.chat(Kind.PRIVATE, "Alice", "$MARKER this is just talk"))
        assertFalse(bob.inbox.chat(Kind.PRIVATE, "Alice", "look: $MARKER?Alice: hi"))
        assertFalse(bob.inbox.chat(Kind.PRIVATE, "§cAlice", ask("§cAlice")))
        assertTrue(bob.inbox.chat(Kind.PRIVATE, "Alice", "$MARKER!Alice"))
        assertTrue(bob.inbox.hasMod("Alice"))
    }

    @Test
    fun `unfinished transfers are capped per sender and in total`() {
        val bob = Client("Bob", "Alice")
        val big = ByteArray(2000) { (it * 31).toByte() }
        // Five transfers from one sender, each missing its last part.
        for (id in 1..5) {
            val parts = ShareInbox.messages("Bob", big, id)
            for (part in parts.dropLast(1)) assertTrue(bob.inbox.chat(Kind.PRIVATE, "Alice", part))
        }
        // The first two were dropped; the last three are kept and can be finished.
        for (id in 5 downTo 1) bob.inbox.chat(Kind.PRIVATE, "Alice", ShareInbox.messages("Bob", big, id).last())
        assertEquals(3, bob.failures.size) // the three kept ones complete (and are not waypoints)
    }

    @Test
    fun `a batch that unpacks past the limit is turned away quickly`() {
        val header = ShareFormat.Header("Alice", "minecraft:overworld", 1, 0, 1, ShareFormat.Area(0, 0, 0, 0))
        val long = "a".repeat(60_000)
        val bomb = ShareFormat.pack(ShareFormat.WAYPOINTS, header, 1000) { out ->
            repeat(1000) {
                out.writeUTF(long)
                out.writeUTF("")
                repeat(3) { ShareFormat.varint(out, 0) }
                out.writeBoolean(false)
                ShareFormat.varint(out, 0)
            }
        }
        assertTrue(bomb.size < ShareFormat.MAX_BATCH)
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            val e = assertThrows(Exception::class.java) { ShareInbox.read(bomb, "Alice", true) }
            assertTrue("unpacks to more than" in e.message.orEmpty(), e.message)
        }
        assertTrue(ShareInbox.MAX_MESSAGES in 500..700, "${ShareInbox.MAX_MESSAGES}")
    }

    @Test
    fun `names come out clean and colours are clamped`() {
        assertEquals("RedBase", ShareFormat.clean("§cRed§lBase\n"))
        assertEquals("ab", ShareFormat.clean("a\u0000‮b\r\n "))
        val bob = Client("Bob", "Alice")
        val parts = ShareInbox.messages(
            "Bob", waypoints("Alice", ShareFormat.SharedWaypoint("§kHi\nthere", "§4X\u0007", 0, 0, 0, false, 999), waypoint("Ok", -4)), 9,
        )
        parts.forEach { assertTrue(bob.inbox.chat(Kind.PRIVATE, "Alice", it)) }
        val got = bob.received.single().waypoints
        assertEquals("Hithere", got[0].name)
        assertEquals("X", got[0].initials)
        assertEquals(ShareFormat.WAYPOINT_COLOURS - 1, got[0].color)
        assertEquals(0, got[1].color)
        assertFalse(ShareInbox.validName("ab"))
        assertFalse(ShareInbox.validName("Way_too_long_name_x"))
        assertFalse(ShareInbox.validName("Bad Name"))
        assertTrue(ShareInbox.validName("Sky_Stormer1"))
    }

    @Test
    fun `waypoints share end to end between two clients`() {
        val alice = Client("Alice", "Bob")
        val bob = Client("Bob", "Alice")
        // Alice asks; Bob answers; Alice now knows Bob has the mod.
        val askLine = ask("Alice")
        alice.inbox.sent(askLine)
        assertTrue(alice.inbox.chat(Kind.OTHER, "Alice", askLine)) // her own echo is hidden
        assertTrue(bob.inbox.chat(Kind.PRIVATE, "Alice", askLine))
        val answer = bob.commands.single().removePrefix("msg Alice ")
        assertTrue(alice.inbox.chat(Kind.PRIVATE, "Bob", answer))
        assertTrue(alice.inbox.hasMod("Bob"))

        val list = (1..1000).map { ShareFormat.SharedWaypoint("Waypoint number $it", "W", it * 7, it, -it * 3, it % 2 == 0, it % 21) }
        val header = ShareFormat.Header("Alice", "minecraft:the_nether", 4242, 0, 1, ShareFormat.Area(0, 0, 0, 0))
        val parts = ShareInbox.messages("Bob", ShareFormat.packWaypoints(header, list), 123456)
        assertTrue(parts.isNotEmpty())
        // Out of order, one twice: still put together once. Over a plugin server too.
        for (part in parts.reversed() + parts.first()) assertTrue(bob.inbox.system("[Alice -> me] $part"))
        val got = bob.received.single()
        assertEquals("Alice", got.from)
        assertEquals("minecraft:the_nether", got.dimensionId)
        assertEquals(4242, got.shareId)
        assertEquals(list.map { listOf(it.name, it.initials, it.x, it.y, it.z, it.yIncluded, it.color) },
            got.waypoints.map { listOf(it.name, it.initials, it.x, it.y, it.z, it.yIncluded, it.color) })
        assertTrue(bob.failures.isEmpty())

        // And with vanilla private messages, verified.
        val again = ShareInbox.messages("Bob", ShareFormat.packWaypoints(header, list.take(3)), 99)
        for (part in again) assertTrue(bob.inbox.chat(Kind.PRIVATE, "Alice", part))
        assertTrue(bob.received.last().verified)
    }
}
