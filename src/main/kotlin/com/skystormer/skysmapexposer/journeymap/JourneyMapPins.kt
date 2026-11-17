package com.skystormer.skysmapexposer.journeymap

import com.mojang.blaze3d.platform.NativeImage
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.LockedPlayers
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapPins
import com.skystormer.skysmapexposer.Markers
import com.skystormer.skysmapexposer.PinDrawing
import com.skystormer.skysmapexposer.Session
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.client.display.IOverlayListener
import journeymap.api.v2.client.display.MarkerOverlay
import journeymap.api.v2.client.display.Overlay
import journeymap.api.v2.client.display.PolygonOverlay
import journeymap.api.v2.client.fullscreen.ModPopupMenu
import journeymap.api.v2.client.model.MapImage
import journeymap.api.v2.client.model.MapPolygon
import journeymap.api.v2.client.model.ShapeProperties
import journeymap.api.v2.client.util.UIState
import journeymap.api.v2.common.Context
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import java.awt.geom.Point2D
import kotlin.math.floor

/**
 * BlueMap's markers, outlines and players, and pings, as JourneyMap overlays: the same pins
 * [MapPins] gives Xaero's maps, by the same settings.
 *
 * Nothing is rebuilt per frame or per tick. Each overlay is kept under a key made from what it
 * shows, so a marker that comes back unchanged from BlueMap's next refresh is left alone, and a
 * player who moves keeps their overlay and only gets a new position. Overlays only change when
 * their data does; everything is dropped on leaving a world ([forget]).
 */
internal object JourneyMapPins {

    private class Shown(val overlay: Overlay, var player: Markers.Player? = null)

    private val shown = HashMap<String, Shown>()

    /** Markers shown with the dot while their icon loads, by key and icon path. */
    private val awaitingIcon = HashMap<String, String>()

    // What the overlays were last built from, by identity: each is a new object when it changes.
    private var lastDimension: String? = null
    private var lastPins: List<Markers.Pin>? = null
    private var lastOutlines: List<Markers.Outline>? = null

    /** Client thread, every tick: brings the overlays up to date with whatever changed. */
    fun tick(api: IClientAPI, dimension: String?) {
        val pins = MapPins.pinsFor(dimension)
        val outlines = outlinesFor(dimension)
        if (dimension != lastDimension || pins !== lastPins || outlines !== lastOutlines) {
            update(api, dimension, pins, outlines)
            lastDimension = dimension
            lastPins = pins
            lastOutlines = outlines
        }
        if (awaitingIcon.isNotEmpty()) giveIcons()
    }

    /** Leaving a world: JourneyMap is told to drop ours ([JourneyMapAdapter.leave]); this lets go of them. */
    fun forget() {
        shown.clear()
        awaitingIcon.clear()
        lastDimension = null
        lastPins = null
        lastOutlines = null
    }

    /** "12 markers, 1 outline, 3 players, 0 pings" shown on JourneyMap now, for `/mapexposer`. */
    fun summary(): String {
        val counts = shown.keys.groupingBy { it.substringBefore('|') }.eachCount()
        return listOf("point" to "marker", "outline" to "outline", "player" to "player", "ping" to "ping")
            .joinToString { (kind, name) -> (counts[kind] ?: 0).let { "$it $name" + if (it == 1) "" else "s" } }
    }

    private fun outlinesFor(dimension: String?): List<Markers.Outline>? {
        if (dimension == null || !Config.showOutlines) return null
        val session = Session.current ?: return null
        val map = session.server.markersFor(dimension) ?: return null
        return session.markers.of(map)?.outlines
    }

    private fun update(api: IClientAPI, dimension: String?, pins: List<Markers.Pin>, outlines: List<Markers.Outline>?) {
        val key = dimension?.let { dimensionKey(it) }
        val wanted = HashSet<String>()
        if (key != null) {
            outlines?.forEach { outline -> keep(api, wanted, outlineKey(outline)) { outlineOverlay(key, outline) } }
            for (pin in pins) {
                when (pin) {
                    is Markers.Player -> player(api, wanted, key, pin)
                    else -> keep(api, wanted, pinKey(pin)) { pinOverlay(key, pin) }
                }
            }
        }
        val gone = shown.keys - wanted
        for (old in gone) {
            shown.remove(old)?.let { api.remove(it.overlay) }
            awaitingIcon.remove(old)
        }
    }

    /** Shows the overlay for [key] unless it already is; either way it is [wanted]. */
    private inline fun keep(api: IClientAPI, wanted: MutableSet<String>, key: String, make: () -> Overlay) {
        wanted.add(key)
        if (key in shown) return
        val overlay = make()
        if (show(api, overlay)) shown[key] = Shown(overlay)
    }

    /** A player keeps one overlay for as long as they are listed; it moves with them. */
    private fun player(api: IClientAPI, wanted: MutableSet<String>, dimension: ResourceKey<Level>, player: Markers.Player) {
        val key = "player|${player.uuid}"
        wanted.add(key)
        val existing = shown[key]
        if (existing != null && existing.overlay.dimension == dimension) {
            val overlay = existing.overlay as MarkerOverlay
            existing.player = player
            overlay.setPoint(blockOf(player))
            overlay.setActiveUIs(*uisFor(player))
            overlay.setLabel(if (LockedPlayers.isLocked(player.uuid)) player.label else "")
            overlay.flagForRerender()
            return
        }
        existing?.let { api.remove(it.overlay) }
        val shownPlayer = Shown(playerOverlay(dimension, player), player)
        if (show(api, shownPlayer.overlay)) shown[key] = shownPlayer else shown.remove(key)
    }

    private fun show(api: IClientAPI, overlay: Overlay): Boolean = try {
        api.show(overlay)
        true
    } catch (e: Exception) {
        Log.warn("JourneyMap would not show {}: {}", overlay.title, e.toString())
        false
    }

    // ---- making overlays ----

    private fun pinOverlay(dimension: ResourceKey<Level>, pin: Markers.Pin): Overlay {
        val image = when (pin) {
            is Markers.Point -> iconOf(pin) ?: dot(POINT_COLOUR).also { if (pin.icon != null) awaitingIcon[pinKey(pin)] = pin.icon }
            is Markers.Ping -> faceOf(pin.uuid)
            else -> dot(PLAYER_COLOUR)
        }
        val size = if (pin is Markers.Point) MARKER_SIZE * Config.worldMapMarkerScale else HEAD_SIZE * Config.playerHeadScale
        image.setDisplayWidth(size).setDisplayHeight(size).centerAnchors()
        val overlay = MarkerOverlay(JourneyMapAdapter.MOD_ID, blockOf(pin), image)
        overlay.setDimension(dimension)
        overlay.setTitle(if (pin is Markers.Ping) "${pin.label} pinged here" else pin.label)
        overlay.setDisplayOrder(if (pin is Markers.Ping) PING_ORDER else POINT_ORDER)
        overlay.setActiveUIs(Context.UI.Fullscreen, Context.UI.Minimap)
        return overlay
    }

    private fun playerOverlay(dimension: ResourceKey<Level>, player: Markers.Player): MarkerOverlay {
        val image = faceOf(player.uuid)
        val size = HEAD_SIZE * Config.playerHeadScale
        image.setDisplayWidth(size).setDisplayHeight(size).centerAnchors()
        val overlay = MarkerOverlay(JourneyMapAdapter.MOD_ID, blockOf(player), image)
        overlay.setDimension(dimension)
        overlay.setTitle(player.label)
        overlay.setLabel(if (LockedPlayers.isLocked(player.uuid)) player.label else "")
        overlay.setDisplayOrder(PLAYER_ORDER)
        overlay.setActiveUIs(*uisFor(player))
        overlay.setOverlayListener(PlayerMenu(player.uuid))
        return overlay
    }

    /**
     * A BlueMap outline. JourneyMap draws closed shapes only, so an open line goes out and back
     * along itself, with no fill. No title: JourneyMap shows a polygon's title wherever the mouse
     * is inside it, which for a world border is the whole map.
     */
    private fun outlineOverlay(dimension: ResourceKey<Level>, outline: Markers.Outline): Overlay {
        val corners = ArrayList<BlockPos>(outline.points.size / 2)
        for (i in 0 until outline.points.size / 2) {
            corners.add(BlockPos(floor(outline.points[i * 2].toDouble()).toInt(), 0, floor(outline.points[i * 2 + 1].toDouble()).toInt()))
        }
        if (!outline.closed && corners.size > 2) corners.addAll(corners.subList(1, corners.size - 1).reversed())
        val properties = ShapeProperties()
            .setStrokeColor(outline.colour and 0xFFFFFF)
            .setStrokeOpacity(((outline.colour ushr 24) and 0xFF) / 255f)
            .setStrokeWidth(outline.width.coerceIn(1f, MAX_STROKE))
            .setFillOpacity(0f)
        val overlay = PolygonOverlay(JourneyMapAdapter.MOD_ID, dimension, properties, MapPolygon(corners))
        overlay.setDisplayOrder(OUTLINE_ORDER)
        overlay.setActiveUIs(Context.UI.Fullscreen, Context.UI.Minimap)
        return overlay
    }

    /**
     * Where a player shows: always on the full-screen map; on the minimap by [Config.minimapPlayers],
     * which on far-only leaves the ones your game has loaded to JourneyMap's own radar.
     */
    private fun uisFor(player: Markers.Player): Array<Context.UI> {
        val onMinimap = Config.showPlayers && when (Config.minimapPlayers) {
            Config.MinimapPlayers.OFF -> false
            Config.MinimapPlayers.ALWAYS -> true
            else -> LockedPlayers.loaded(player.uuid) == null
        }
        return if (onMinimap) BOTH else FULLSCREEN
    }

    /** Right-clicking a player: lock on or unlock, as on Xaero's map. */
    private class PlayerMenu(private val uuid: java.util.UUID) : IOverlayListener {
        override fun onOverlayMenuPopup(state: UIState, mouse: Point2D.Double, block: BlockPos, menu: ModPopupMenu) {
            val player = shown["player|$uuid"]?.player ?: return
            val locked = LockedPlayers.isLocked(uuid)
            menu.addMenuItem(if (locked) "Unlock ${player.label}" else "Lock on to ${player.label}") {
                LockedPlayers.set(player, !locked)
                lastPins = null // the label shows whether they are locked
            }
        }
    }

    // ---- pictures ----

    private fun iconOf(point: Markers.Point): MapImage? {
        val (location, width, height) = Session.current?.markers?.iconLocation(point.icon) ?: return null
        return MapImage(location, width, height)
    }

    /** Markers whose icon has loaded since they were shown get it now. */
    private fun giveIcons() {
        val markers = Session.current?.markers ?: return
        val iterator = awaitingIcon.entries.iterator()
        while (iterator.hasNext()) {
            val (key, path) = iterator.next()
            val overlay = shown[key]?.overlay as? MarkerOverlay
            if (overlay == null) { iterator.remove(); continue }
            val (location, width, height) = markers.iconLocation(path) ?: continue
            val size = MARKER_SIZE * Config.worldMapMarkerScale
            overlay.setIcon(MapImage(location, width, height).setDisplayWidth(size).setDisplayHeight(size).centerAnchors())
            overlay.flagForRerender()
            iterator.remove()
        }
    }

    /** A player's face from their skin (the patch the tab list uses), or a plain dot until it arrives. */
    private fun faceOf(uuid: java.util.UUID): MapImage =
        PinDrawing.skin(uuid)?.let { MapImage(it, 8, 8, 8, 8, 0xFFFFFF, 1f) } ?: dot(PLAYER_COLOUR)

    /** A square with a dark edge, tinted [colour]: what Xaero's map draws for a pin with no picture. */
    private fun dot(colour: Int): MapImage = MapImage(dotTexture, DOT, DOT).setColor(colour)

    private val dotTexture: Identifier by lazy {
        val image = NativeImage(DOT, DOT, false)
        for (y in 0 until DOT) for (x in 0 until DOT) {
            val edge = x == 0 || y == 0 || x == DOT - 1 || y == DOT - 1
            image.setPixel(x, y, if (edge) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        val location = Identifier.fromNamespaceAndPath("skysmapexposer", "journeymap_dot")
        Minecraft.getInstance().textureManager.register(location, DynamicTexture({ "skysmapexposer dot" }, image))
        location
    }

    // ---- keys and places ----

    private fun pinKey(pin: Markers.Pin): String = when (pin) {
        is Markers.Point -> "point|${pin.set}|${pin.label}|${pin.x}|${pin.y}|${pin.z}|${pin.icon}"
        is Markers.Ping -> "ping|${pin.uuid}|${pin.startedAt}"
        is Markers.Player -> "player|${pin.uuid}"
    }

    private fun outlineKey(outline: Markers.Outline): String =
        "outline|${outline.label}|${outline.colour}|${outline.width}|${outline.closed}|${outline.points.contentHashCode()}"

    private fun blockOf(pin: Markers.Pin) = BlockPos(floor(pin.x).toInt(), floor(pin.y).toInt(), floor(pin.z).toInt())

    private fun dimensionKey(dimension: String): ResourceKey<Level>? = try {
        ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension))
    } catch (e: Exception) {
        null
    }

    private val FULLSCREEN = arrayOf(Context.UI.Fullscreen)
    private val BOTH = arrayOf(Context.UI.Fullscreen, Context.UI.Minimap)

    private const val DOT = 8
    private const val MARKER_SIZE = 16.0
    private const val HEAD_SIZE = 16.0
    private const val MAX_STROKE = 8f
    private const val POINT_COLOUR = 0x55FFFF
    private const val PLAYER_COLOUR = 0xFFFFFF

    private const val OUTLINE_ORDER = 100
    private const val POINT_ORDER = 200
    private const val PLAYER_ORDER = 300
    private const val PING_ORDER = 400
}
