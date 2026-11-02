package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Util
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pings between players who trust each other: a spot on the map or in the world, sent by private
 * message ([ChatShare]) to everyone on this server's trusted list who is online. It shows as a beam
 * on the HUD ([PlayerBeacon]), the world map and the minimap, flashes [FLASHES] times and goes.
 *
 * Trust is by player and per server ([Config.trusted]). A ping from anyone else is dropped, hidden.
 * Someone's newest ping replaces their last, so nobody can stack them up, and [MIN_GAP_MILLIS]
 * limits how often anyone's can arrive at all.
 */
object PlayerPings {

    private const val FLASHES = 5
    private const val FLASH_MILLIS = 1200L
    private const val MIN_GAP_MILLIS = 2000L
    /** How far the ping key reaches, in blocks; only what the game has loaded can be hit. */
    private const val KEY_REACH = 1024.0
    private const val SHARE_NAME_LIMIT = 24

    private class Active(val from: String, val pin: Markers.Ping, val dimension: String, val startedAt: Long)

    /** The ping on show from each sender, by lower case name. */
    private val active = LinkedHashMap<String, Active>()
    private val lastArrived = HashMap<String, Long>()

    /** Each dimension's pings as pins, the Overworld's and the Nether's in each other too; replaced when they change. */
    private var byDimension: Map<String, List<Markers.Ping>> = emptyMap()

    /** The pings to show on [dimension]'s map or sky: a new list only when they changed. */
    fun pinsIn(dimension: String?): List<Markers.Ping> = byDimension[dimension] ?: emptyList()

    val showing: Boolean get() = active.isNotEmpty()

    /** How lit a ping's beam is right now, 0 to 1: [FLASHES] smooth pulses. */
    fun brightness(ping: Markers.Ping): Float {
        val phase = (Util.getMillis() - ping.startedAt) % FLASH_MILLIS / FLASH_MILLIS.toDouble()
        return sin(PI * phase).let { (it * it).toFloat() }
    }

    fun tick() {
        val now = Util.getMillis()
        if (active.values.removeIf { now - it.startedAt >= FLASHES * FLASH_MILLIS }) rebuild()
    }

    /** Leaving the server. */
    fun forget() {
        active.clear()
        lastArrived.clear()
        rebuild()
    }

    private fun rebuild() {
        val built = HashMap<String, MutableList<Markers.Ping>>()
        for (ping in active.values) {
            built.getOrPut(ping.dimension) { ArrayList() }.add(ping.pin)
            when (ping.dimension) {
                Config.OVERWORLD -> built.getOrPut(Config.NETHER) { ArrayList() }.add(ping.pin.scaled(1.0 / Waypoints.NETHER_SCALE))
                Config.NETHER -> built.getOrPut(Config.OVERWORLD) { ArrayList() }.add(ping.pin.scaled(Waypoints.NETHER_SCALE.toDouble()))
            }
        }
        byDimension = built
    }

    private fun show(from: String, uuid: UUID, dimension: String, x: Int, y: Int, z: Int) {
        val now = Util.getMillis()
        active.remove(from.lowercase())
        active[from.lowercase()] = Active(from, Markers.Ping(uuid, from, x + 0.5, y.toDouble(), z + 0.5, now), dimension, now)
        rebuild()
    }

    // ---- who is trusted ----

    private fun server(): String? = MapExposerClient.addressOf(Minecraft.getInstance())?.let(Config::normalise)

    fun isTrusted(uuid: UUID): Boolean = server()?.let { Config.trusted[it]?.contains(uuid) } == true

    /** Trusts [uuid] on this server, or stops. */
    fun setTrusted(uuid: UUID, trusted: Boolean) {
        val server = server() ?: return
        val list = Config.trusted.getOrPut(server) { LinkedHashSet() }
        if (if (trusted) list.add(uuid) else list.remove(uuid)) {
            if (list.isEmpty()) Config.trusted.remove(server)
            Config.save()
        }
    }

    /** The names of everyone trusted who is on the server now. */
    fun trustedOnline(): List<String> {
        val minecraft = Minecraft.getInstance()
        val me = minecraft.player?.uuid
        return minecraft.connection?.onlinePlayers.orEmpty()
            .filter { it.profile.id() != me && isTrusted(it.profile.id()) }
            .map { it.profile.name() }
            .sortedBy { it.lowercase() }
    }

    /** The player of that name on the server, for the trust commands. */
    fun online(name: String): Pair<UUID, String>? = Minecraft.getInstance().connection?.onlinePlayers.orEmpty()
        .firstOrNull { it.profile.name().equals(name, ignoreCase = true) }
        ?.let { it.profile.id() to it.profile.name() }

    // ---- sending ----

    /** Pings block ([x], [z]) of [dimension]; [y] is a guess from what is loaded unless given. Returns what to tell the player. */
    fun send(dimension: String, x: Int, z: Int, y: Int = surface(dimension, x, z)): String {
        val me = Minecraft.getInstance().player ?: return "Not in a world"
        val to = trustedOnline()
        if (to.isEmpty()) return "Nobody you trust is online; /mapexposer trust <player> adds someone"
        if (!ChatShare.ping(to, ShareInbox.pingBody(x, y, z, dimension))) return "Still sending your last ping"
        show(me.gameProfile.name(), me.uuid, dimension, x, y, z)
        return "Pinged ${to.joinToString()}"
    }

    /** Pings the block you are looking at. Returns what to tell the player. */
    fun sendLookedAt(): String {
        val minecraft = Minecraft.getInstance()
        val me = minecraft.player ?: return "Not in a world"
        val hit = me.pick(KEY_REACH, 1f, false)
        if (hit.type != HitResult.Type.BLOCK) return "Nothing to ping there; look at a block"
        val pos = (hit as BlockHitResult).blockPos
        return send(me.level().dimension().identifier().toString(), pos.x, pos.z, pos.y)
    }

    /** The ground at ([x], [z]) if you are in [dimension] with that chunk loaded, else your own height. */
    private fun surface(dimension: String, x: Int, z: Int): Int {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level
        val own = minecraft.player?.blockY ?: 64
        if (level == null || level.dimension().identifier().toString() != dimension) return own
        if (!level.chunkSource.hasChunk(x shr 4, z shr 4)) return own
        return level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, BlockPos(x, 0, z)).y
    }

    // ---- receiving ----

    /** A ping [ShareInbox] took in. Dropped unless pings are on, [from] is trusted, and it has been long enough. */
    fun received(ping: ShareInbox.ReceivedPing) {
        if (!Config.pings) return
        val uuid = online(ping.from)?.first ?: return
        if (!isTrusted(uuid)) return
        val now = Util.getMillis()
        val key = ping.from.lowercase()
        if (now - (lastArrived[key] ?: Long.MIN_VALUE / 2) < MIN_GAP_MILLIS) return
        lastArrived[key] = now
        show(ping.from, uuid, ping.dimensionId, ping.x, ping.y, ping.z)
        val minecraft = Minecraft.getInstance()
        minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.5f))
        val name = MapMenus.dimensionName(ping.dimensionId) ?: ping.dimensionId
        val unverified = if (ping.verified) "" else " §7(unverified)§r"
        val id = ChatShare.offerWaypoints(
            ping.from, ping.dimensionId,
            listOf(ShareFormat.SharedWaypoint("Ping ${ping.from}".take(SHARE_NAME_LIMIT), ping.from.take(1), ping.x, ping.y, ping.z, true, 0)),
        )
        minecraft.player?.sendSystemMessage(
            Component.literal("§b${ping.from}§r$unverified pinged ${ping.x}, ${ping.y}, ${ping.z} in the $name  ")
                .append(ChatButton.of("[Add waypoint]", "/mapexposer shared $id waypoints", "Keeps this spot as a waypoint; the ping itself is about to go."))
        )
    }
}
