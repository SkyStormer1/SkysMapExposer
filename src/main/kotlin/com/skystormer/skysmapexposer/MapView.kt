package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import xaero.map.WorldMapSession

/**
 * Sending Xaero's world map to someone in another dimension, and putting it back afterwards.
 *
 * "Go to" on a player the map is not currently showing used to be greyed out, which is a strange
 * thing for a list of everyone online to do. Instead the map is switched to their dimension the way
 * Xaero's own dimension button does it — [xaero.map.world.MapWorld.setCustomDimensionId] and then
 * `checkForWorldUpdate` — and switched back to whatever it was on once you leave the map.
 *
 * The switch is not instant: `checkForWorldUpdate` hands the work to Xaero's own thread, and moving
 * the camera before the new dimension is up would be undone. So the move is held until the map says
 * it is showing the right dimension, and dropped if that never happens ([GIVE_UP]).
 *
 * Xaero's minimap reads the same setting, so leaving the map switched would quietly change the
 * minimap too. That is why putting it back matters, and why it is put back on a tick rather than on
 * the screen closing: opening the player list closes the map screen for a moment, and that must not
 * count as leaving it.
 */
object MapView {

    /** How long to wait for Xaero to finish switching dimension before giving up on the move. */
    private const val GIVE_UP = 100

    /**
     * How many ticks to keep putting the camera back after the dimension has changed.
     *
     * On the first frame that the dimension scale changes, Xaero shifts the camera by
     * `-playerX / oldScale + playerX / newScale` and throws away any destination, to keep you
     * looking at the equivalent spot. From the nether that shift is about seven times your own X,
     * so a camera set before that frame ends up thousands of blocks from where it was put. Setting
     * it once is therefore not enough: it is set again for a few ticks, and the last one lands
     * after Xaero's shift and stays.
     */
    private const val SETTLE = 10

    /** Where to send the camera once the map is showing [dimension]. */
    private class Move(val dimension: String, val x: Int, val z: Int) {
        var applied = 0
    }

    private var move: Move? = null
    private var waited = 0

    /** What the map was set to before this mod switched it, and whether it owes a switch back. */
    private var restoreTo: ResourceKey<Level>? = null
    private var owesRestore = false

    /** The dimension the map was showing last tick, so a change to it can be noticed. */
    private var lastViewed: String? = null

    /**
     * Whether each of the two hiding injections has ever run. Both are optional, so this is how an
     * unsupported Xaero version gets noticed rather than quietly doing nothing. `/mapexposer`
     * reports them.
     */
    @Volatile
    var arrowHookRan = false
        private set

    @Volatile
    var radarHookRan = false
        private set

    /**
     * Shows ([x], [z]) of [dimension] on the world map, switching the map to that dimension first
     * if it is showing another one. [screen] is the screen the request came from, so that an open
     * map is moved rather than a second one opened. Returns whether anything could be done.
     */
    fun goTo(dimension: String, x: Int, z: Int, screen: Screen?): Boolean {
        if (dimension == MarkerElements.worldMapDimension()) {
            move = null
            return MapMenus.goTo(screen, x, z)
        }
        if (!switchTo(dimension)) return false
        // The map may not be open yet, and will not be showing the new dimension for a frame or
        // two either way, so the move waits for both.
        move = Move(dimension, x, z)
        waited = 0
        if (screen !is MapCamera) MapMenus.openMap()
        return true
    }

    /** Client thread, every tick: finishes a held move, and puts the dimension back when due. */
    fun tick(minecraft: Minecraft) {
        watchDimension(minecraft)
        finishMove(minecraft)
        restoreIfLeft(minecraft)
    }

    /**
     * When the map is switched to a dimension you are not standing in, start it at the origin
     * rather than where Xaero leaves it.
     *
     * Xaero slides the camera to your own position converted into the new dimension, which for the
     * nether means being dropped eight times further out — somewhere nothing has ever been mapped,
     * so the map looks empty. The origin at least has the world's spawn near it.
     *
     * Switching back to your own dimension is left alone: Xaero putting you back on the player is
     * the right answer there.
     */
    private fun watchDimension(minecraft: Minecraft) {
        val viewed = MarkerElements.worldMapDimension()
        val changed = viewed != null && lastViewed != null && viewed != lastViewed
        lastViewed = viewed
        if (!changed) return
        // A "Go to" is already on its way somewhere better than the origin.
        if (move != null) return
        if (viewed == minecraft.level?.dimension()?.identifier()?.toString()) return
        // Only while you are looking at the map. Putting the dimension back when you leave it also
        // counts as a change, and that must not queue a jump for the next time it is opened.
        if (minecraft.gui.screen() !is MapCamera) return
        // Through the same held move as a "Go to", so that it too survives Xaero's shift.
        move = Move(viewed, 0, 0)
        waited = 0
    }

    /** For `GuiMapMixin`: hide the player arrow. Running at all proves that injection took. */
    @JvmStatic
    fun hideArrow(): Boolean {
        arrowHookRan = true
        return viewingAnotherDimension()
    }

    /** For `RadarRendererMixin`: hide Xaero's entity radar on the world map. */
    @JvmStatic
    fun hideRadar(): Boolean {
        radarHookRan = true
        return viewingAnotherDimension()
    }

    /**
     * Whether the world map is showing a dimension you are not standing in.
     *
     * Xaero draws your arrow, your waypoints and the entities around you at your own position
     * converted into whatever dimension the map shows — useful for lining a nether tunnel up with
     * the overworld, and misleading the rest of the time, because none of it is where it appears.
     */
    @JvmStatic
    fun viewingAnotherDimension(): Boolean {
        val viewed = MarkerElements.worldMapDimension() ?: return false
        val here = Minecraft.getInstance().level?.dimension()?.identifier()?.toString() ?: return false
        return viewed != here
    }

    /** Nothing survives leaving the server: the next one has its own map and dimensions. */
    fun forget() {
        move = null
        restoreTo = null
        owesRestore = false
        lastViewed = null
    }

    private fun finishMove(minecraft: Minecraft) {
        val wanted = move ?: return
        if (++waited > GIVE_UP) {
            move = null
            Log.warn("The world map did not switch to {} in time, so it was not moved", wanted.dimension)
            return
        }
        val screen = minecraft.gui.screen()
        // Still in the player list, or the map is not up yet: keep waiting.
        if (screen !is MapCamera) return
        if (MarkerElements.worldMapDimension() != wanted.dimension) return
        // Jumped, not glided: Xaero's glide is a slowing animation, and over the tens of thousands
        // of blocks between two dimensions' coordinates it crawls. Repeated for [SETTLE] ticks so
        // that it outlives Xaero's own shift on the frame the dimension scale changes.
        screen.skysmapexposerJumpTo(wanted.x, wanted.z)
        if (++wanted.applied >= SETTLE) move = null
    }

    /**
     * Puts the dimension back once you are neither on the map nor in the player list. The list
     * counts as being on the map: opening it closes the map screen, and switching the dimension
     * back at that moment would undo the thing you just asked for.
     */
    private fun restoreIfLeft(minecraft: Minecraft) {
        if (!owesRestore || move != null) return
        val screen = minecraft.gui.screen()
        if (onTheMap(screen) || screen is com.skystormer.skysmapexposer.gui.PlayerListScreen) return
        owesRestore = false
        val back = restoreTo
        restoreTo = null
        apply(back)
    }

    /** Switches the map to [dimension], remembering what it was on first. */
    private fun switchTo(dimension: String): Boolean {
        val world = WorldMapSession.getCurrentSession()?.mapProcessor?.mapWorld
        if (world == null) {
            Log.warn("Cannot switch the world map to {}: Xaero's world map has no session yet", dimension)
            return false
        }
        val target = keyOf(dimension) ?: return false
        // Xaero uses null for "the dimension I am actually in", and its own toggle does the same.
        val here = Minecraft.getInstance().level?.dimension()
        val wanted = if (target == here) null else target
        if (!owesRestore) {
            restoreTo = world.customDimensionId
            owesRestore = true
        }
        if (world.customDimensionId == wanted) return true
        return apply(wanted)
    }

    private fun apply(dimension: ResourceKey<Level>?): Boolean {
        return try {
            val session = WorldMapSession.getCurrentSession() ?: return false
            val world = session.mapProcessor.mapWorld ?: return false
            if (world.customDimensionId == dimension) return true
            world.customDimensionId = dimension
            session.mapProcessor.checkForWorldUpdate()
            true
        } catch (e: Throwable) {
            Log.error("Could not switch the world map's dimension", e)
            false
        }
    }

    /**
     * Whether [screen] is Xaero's world map. The class name is checked as well as the interface,
     * because the interface is only there while this mod's mixin applied: without that fallback,
     * an unsupported Xaero would look like leaving the map and switch the dimension back underneath
     * someone still looking at it.
     */
    private fun onTheMap(screen: Screen?): Boolean =
        screen is MapCamera || screen?.javaClass?.name == "xaero.map.gui.GuiMap"

    private fun keyOf(dimension: String): ResourceKey<Level>? = try {
        ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension))
    } catch (e: Exception) {
        Log.warn("Not a dimension id: {}", dimension)
        null
    }
}
