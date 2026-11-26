package com.skystormer.skysmapexposer

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.Util
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

/**
 * Pings between players who trust each other: a spot on the map or in the world, sent by private
 * message ([ChatShare]) to those online you picked to ping ([Config.pingTo]). It shows as a beam
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
    /** Where a roofed dimension's roof starts, as the Nether's bedrock ceiling does at 123 to 127. */
    private const val ROOF = 120
    /** How soon a second press of the ping key pings what is within reach. */
    private const val DOUBLE_PRESS_MILLIS = 400L
    /** When the ping key was last pressed with something within reach, left to pick block; 0 after a double press. */
    private var lastNearPress = 0L
    /** The height of a ping in another dimension than yours with nothing better to go on. */
    private const val UNKNOWN_Y = 64

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
                Config.OVERWORLD -> built.getOrPut(Config.NETHER) { ArrayList() }.add(ping.pin.scaled(1.0 / Config.NETHER_SCALE))
                Config.NETHER -> built.getOrPut(Config.OVERWORLD) { ArrayList() }.add(ping.pin.scaled(Config.NETHER_SCALE.toDouble()))
            }
        }
        byDimension = built
    }

    private fun show(from: String, uuid: UUID, dimension: String, x: Int, y: Int, z: Int) {
        val now = Util.getMillis()
        active.remove(from.lowercase())
        // [y] is the block pinged; the beam and head stand on top of it.
        active[from.lowercase()] = Active(from, Markers.Ping(uuid, from, x + 0.5, y + 1.0, z + 0.5, now), dimension, now)
        rebuild()
    }

    // ---- who is trusted ----

    private fun server(): String? = MapExposerClient.addressOf(Minecraft.getInstance())?.let(Config::normalise)

    private fun isIn(lists: Map<String, Set<UUID>>, uuid: UUID): Boolean = server()?.let { lists[it]?.contains(uuid) } == true

    /** Puts [uuid] in this server's list in [lists], or takes them out. */
    private fun setIn(lists: MutableMap<String, MutableSet<UUID>>, uuid: UUID, on: Boolean) {
        val server = server() ?: return
        val list = lists.getOrPut(server) { LinkedHashSet() }
        if (if (on) list.add(uuid) else list.remove(uuid)) {
            if (list.isEmpty()) lists.remove(server)
            Config.save()
        }
    }

    fun isTrusted(uuid: UUID): Boolean = isIn(Config.trusted, uuid)

    /** Trusts [uuid] on this server, or stops, which stops your pings to them too. */
    fun setTrusted(uuid: UUID, trusted: Boolean) {
        if (!trusted) setIn(Config.pingTo, uuid, false)
        setIn(Config.trusted, uuid, trusted)
    }

    /** Whether your pings go to [uuid]: only those you trust and picked. */
    fun isPingedTo(uuid: UUID): Boolean = isTrusted(uuid) && isIn(Config.pingTo, uuid)

    /** Sends your pings to [uuid], whom you trust, or stops. */
    fun setPingedTo(uuid: UUID, on: Boolean) {
        if (!on || isTrusted(uuid)) setIn(Config.pingTo, uuid, on)
    }

    /** The names of everyone online, but you, that [wanted] picks. */
    private fun onlineWhere(wanted: (UUID) -> Boolean): List<String> {
        val minecraft = Minecraft.getInstance()
        val me = minecraft.player?.uuid
        return minecraft.connection?.onlinePlayers.orEmpty()
            .filter { it.profile.id() != me && wanted(it.profile.id()) }
            .map { it.profile.name() }
            .sortedBy { it.lowercase() }
    }

    /** The names of everyone trusted who is on the server now. */
    fun trustedOnline(): List<String> = onlineWhere(::isTrusted)

    /** The names of those online who get your pings. */
    fun recipients(): List<String> = onlineWhere(::isPingedTo)

    /** The player of that name on the server, for the trust commands. */
    fun online(name: String): Pair<UUID, String>? = Minecraft.getInstance().connection?.onlinePlayers.orEmpty()
        .firstOrNull { it.profile.name().equals(name, ignoreCase = true) }
        ?.let { it.profile.id() to it.profile.name() }

    // ---- sending ----

    /** Pings block ([x], [z]) of [dimension]; [y] is a guess from what is loaded unless given. Returns what to tell the player. */
    fun send(dimension: String, x: Int, z: Int, y: Int): String {
        val me = Minecraft.getInstance().player ?: return "Not in a world"
        val to = recipients()
        if (to.isEmpty()) return "Nobody you ping is online; pick who with Ping in the player list or /mapexposer pingto add <player>"
        if (!ChatShare.ping(to, ShareInbox.pingBody(x, y, z, dimension))) return "Still sending your last ping"
        show(me.gameProfile.name(), me.uuid, dimension, x, y, z)
        return "Pinged ${to.joinToString()}"
    }

    /**
     * Pings the block you are looking at. Returns what to tell the player, or null when the key is
     * left to pick block: something within your reach ([Config.pingDoublePressNear]), unless this is
     * the second press within [DOUBLE_PRESS_MILLIS]; not for a command typed ([byKey] false), which
     * always pings.
     */
    fun sendLookedAt(byKey: Boolean = true): String? {
        val minecraft = Minecraft.getInstance()
        val me = minecraft.player ?: return "Not in a world"
        if (byKey && Config.pingDoublePressNear && pickableInReach(minecraft, me)) {
            val now = Util.getMillis()
            val second = now - lastNearPress <= DOUBLE_PRESS_MILLIS
            lastNearPress = if (second) 0L else now
            if (!second) return null
        }
        val hit = me.pick(KEY_REACH, 1f, false)
        if (hit.type != HitResult.Type.BLOCK) return "Nothing to ping there; look at a block"
        val pos = (hit as BlockHitResult).blockPos
        return send(me.level().dimension().identifier().toString(), pos.x, pos.z, pos.y)
    }

    /**
     * Whether pick block (the game's, or Litematica's for its schematic) has something within your
     * reach to take: the game's own target, which it only finds within reach, or a block of
     * Litematica's schematic world along your view as far as Litematica looks, your reach.
     */
    private fun pickableInReach(minecraft: Minecraft, me: LocalPlayer): Boolean {
        if (minecraft.hitResult?.type.let { it != null && it != HitResult.Type.MISS }) return true
        val schematic = try {
            schematicWorld?.invoke(null) as? Level
        } catch (e: ReflectiveOperationException) {
            null
        } ?: return false
        val eye = me.getEyePosition(1f)
        val end = eye.add(me.getViewVector(1f).scale(me.blockInteractionRange()))
        return try {
            schematic.clip(ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, me)).type == HitResult.Type.BLOCK
        } catch (e: RuntimeException) {
            false
        }
    }

    /** Litematica's `SchematicWorldHandler.getSchematicWorld()`, when Litematica is installed; looked up by name, as it is optional. */
    private val schematicWorld: java.lang.reflect.Method? by lazy {
        if (!FabricLoader.getInstance().isModLoaded("litematica")) return@lazy null
        try {
            Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler").getMethod("getSchematicWorld")
        } catch (e: ReflectiveOperationException) {
            Log.warn("Litematica's schematic world could not be found; the ping key will not leave its blocks to pick block: {}", e.toString())
            null
        }
    }

    /**
     * Pings block ([x], [z]) of [dimension], from the map or `/mapexposer ping x z`, at the first height
     * there is to go on: [mapY], the height the map shows there (in the Nether the roof on a map of
     * the roof, the floor on a cave map); else the ground, where you are in that dimension with the
     * chunk loaded ([loadedGround]); else BlueMap's height there, fetched first, as when the map has
     * nothing drawn; else where you stand, or [UNKNOWN_Y]. Returns what to tell the player now.
     */
    fun sendFromMap(dimension: String, x: Int, z: Int, mapY: Int?): String {
        mapY?.takeIf { Minecraft.getInstance().level?.isOutsideBuildHeight(it) == false }?.let { return send(dimension, x, z, it) }
        loadedGround(dimension, x, z)?.let { return send(dimension, x, z, it) }
        val session = Session.current
        val mapId = session?.server?.markersFor(dimension) ?: return send(dimension, x, z, guessedY(dimension))
        background("SkysMapExposer ping height") {
            val y = try {
                blueMapHeight(session.blueMap, mapId, x, z)
            } catch (e: Exception) {
                Log.warn("Could not read BlueMap's height for a ping at {}, {}: {}", x, z, e.toString())
                null
            }
            Minecraft.getInstance().execute { Ui.say(send(dimension, x, z, y ?: guessedY(dimension))) }
        }
        return "Finding the height there on BlueMap…"
    }

    /** BlueMap's ground height at block ([x], [z]) of map [mapId], from its finest tile; null where it has nothing. Off the client thread. */
    private fun blueMapHeight(blueMap: BlueMap, mapId: String, x: Int, z: Int): Int? {
        val size = blueMap.layout(mapId).tileSize
        val tileX = Math.floorDiv(x, size)
        val tileZ = Math.floorDiv(z, size)
        val bytes = blueMap.tile(mapId, 1, tileX, tileZ) ?: return null
        val tile = BlueMapPicture.decode(tileX, tileZ, size, bytes)
        val i = (z - tileZ * size) * size + (x - tileX * size)
        return if (tile.colour[i] == 0) null else tile.height[i].toInt()
    }

    /**
     * The ground at ([x], [z]) if you are in [dimension] with that chunk loaded: its top block, or
     * under a roof (the Nether) the first floor at or below your eyes, the level a cave map shows
     * around you; null otherwise.
     */
    private fun loadedGround(dimension: String, x: Int, z: Int): Int? {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return null
        val player = minecraft.player ?: return null
        if (level.dimension().identifier().toString() != dimension || !level.chunkSource.hasChunk(x shr 4, z shr 4)) return null
        if (!level.dimensionType().hasCeiling()) return level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, BlockPos(x, 0, z)).y - 1
        // Down from your eyes (never above the roof) to the first open space, then on down to what it stands on.
        val pos = BlockPos.MutableBlockPos(x, minOf(ROOF - 1, player.blockY + 1), z)
        while (pos.y > level.minY && !level.getBlockState(pos).isAir) pos.move(0, -1, 0)
        while (pos.y > level.minY && level.getBlockState(pos).isAir) pos.move(0, -1, 0)
        return pos.y.takeIf { it > level.minY }
    }

    /** With nothing to go on: what you stand on, in [dimension]; from another dimension [UNKNOWN_Y]. */
    private fun guessedY(dimension: String): Int {
        val minecraft = Minecraft.getInstance()
        val here = minecraft.level?.dimension()?.identifier()?.toString() == dimension
        return minecraft.player?.blockY?.minus(1)?.takeIf { here } ?: UNKNOWN_Y
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
        // An unverified ping came as a plain server line, which a server or a chat plugin could
        // fake: it shows its beam but plays no sound.
        show(ping.from, uuid, ping.dimensionId, ping.x, ping.y, ping.z)
        val minecraft = Minecraft.getInstance()
        if (ping.verified && Config.pingSound) minecraft.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BELL.value(), 1.5f))
        val name = Ui.dimensionName(ping.dimensionId) ?: ping.dimensionId
        val unverified = if (ping.verified) "" else " §7(unverified)§r"
        val id = ChatShare.offerWaypoints(
            ping.from, ping.dimensionId,
            listOf(ShareFormat.SharedWaypoint("Ping ${ping.from}".take(SHARE_NAME_LIMIT), ping.from.take(1), ping.x, ping.y + 1, ping.z, true, 0)),
        )
        minecraft.player?.sendSystemMessage(
            Component.literal("§b${ping.from}§r$unverified pinged ${ping.x}, ${ping.y}, ${ping.z} in the $name  ")
                .append(ChatButton.of("[Add waypoint]", "/mapexposer shared $id waypoints", "Keeps this spot as a waypoint; the ping itself is about to go."))
        )
    }
}
