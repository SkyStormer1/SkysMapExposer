package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.levelKey
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.SeaFloor
import com.skystormer.skysmapexposer.Ui
import journeymap.api.v2.client.fullscreen.IFullscreen
import journeymap.api.v2.common.Context
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * Showing another dimension on JourneyMap's full-screen map, as Xaero's own dimension button does
 * on its map: picked here, kept through JourneyMap's refreshes by `MapStateMixin`, and put back to
 * yours once you leave the map, as on Xaero's.
 */
object JourneyMapDimension {

    /** The dimension the full-screen map is switched to, or null for the one you are in. */
    @Volatile
    var chosen: ResourceKey<Level>? = null
        private set

    /** For `MapStateMixin`: the dimension JourneyMap's full-screen map should keep showing. */
    @JvmStatic
    fun keep(actual: ResourceKey<Level>): ResourceKey<Level> = chosen ?: actual

    /** Whether the full-screen map shows a dimension other than the one you are in: your arrow and radar are left off it. */
    @JvmStatic
    fun showingOther(): Boolean {
        val picked = chosen ?: return false
        return picked != Minecraft.getInstance().level?.dimension()
    }

    /** How deep the render thread is in JourneyMap's work for the full-screen map: drawing it, its Chat Position key. */
    private var shownDepth = 0

    /** For `FullscreenShownMixin`: entering and leaving that work. */
    @JvmStatic
    fun enterShown() {
        shownDepth++
    }

    @JvmStatic
    fun leaveShown() {
        if (shownDepth > 0) shownDepth--
    }

    /**
     * The dimension the full-screen map shows while JourneyMap draws it or writes its Chat Position,
     * if another than yours; else null. There, waypoints go by it rather than by where you are
     * (`WaypointShownMixin`, `WaypointScaleMixin`): which waypoints show, and at whose scale.
     */
    @JvmStatic
    fun shownForWaypoints(): ResourceKey<Level>? {
        if (!Minecraft.getInstance().isSameThread) return null
        scaleOf?.let { return it }
        if (shownDepth == 0) return null
        return chosen?.takeIf { showingOther() }
    }

    /** The dimension [inScaleOf] reads waypoints as, while it runs. */
    private var scaleOf: ResourceKey<Level>? = null

    /** [block] with JourneyMap's waypoints read as in [dimension] (`WaypointScaleMixin`), wherever you are. Client thread. */
    fun <T> inScaleOf(dimension: ResourceKey<Level>, block: () -> T): T {
        val before = scaleOf
        scaleOf = dimension
        try {
            return block()
        } finally {
            scaleOf = before
        }
    }

    /** Switches [map] to [dimension]; your own dimension clears the choice. */
    fun show(map: IFullscreen, dimension: ResourceKey<Level>) {
        val here = Minecraft.getInstance().level?.dimension()
        chosen = if (dimension == here) null else dimension
        // A dimension with a roof opens on its cave map, as JourneyMap itself shows the Nether.
        val roofed = SeaFloor.hasCeiling(dimension.identifier().toString())
        val type = if (roofed) Context.MapType.Underground else Context.MapType.Day
        // A cave map needs a height to slice at; from another dimension, the Nether's middle.
        map.updateMapType(type, if (roofed) ROOFED_SLICE else null, dimension)
    }

    /** The next of the Overworld, the Nether and the End after the one [map] shows. */
    fun showNext(map: IFullscreen) {
        val shown = map.uiState?.dimension?.identifier()?.toString()
        val next = CYCLE[(CYCLE.indexOf(shown) + 1) % CYCLE.size]
        show(map, levelKey(next))
        Ui.say("Showing the ${Ui.dimensionName(next)}")
    }

    /** Back to your own dimension, next time JourneyMap refreshes the map. */
    fun forget() {
        chosen = null
    }

    /** Whether the map should go to you when it next opens: it has just come back to your dimension. */
    @Volatile
    var backToYou = false

    private val CYCLE = listOf(Config.OVERWORLD, Config.NETHER, Config.END)

    /** The cave slice (16 blocks a slice) a roofed dimension opens on: y 64 to 79. */
    private const val ROOFED_SLICE = 4
}
