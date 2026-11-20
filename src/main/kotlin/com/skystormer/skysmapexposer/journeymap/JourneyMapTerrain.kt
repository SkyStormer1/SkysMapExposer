package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.levelKey
import com.skystormer.skysmapexposer.packXZ
import com.mojang.blaze3d.platform.NativeImage
import com.skystormer.skysmapexposer.BlueMap
import com.skystormer.skysmapexposer.Clock
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.SeaFloor
import com.skystormer.skysmapexposer.Session
import com.skystormer.skysmapexposer.TileIndex
import com.skystormer.skysmapexposer.TilePixels
import com.skystormer.skysmapexposer.TileStore
import com.skystormer.skysmapexposer.journeymap.JourneyMapFiles.REGION
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.common.Context
import journeymap.client.model.map.MapType
import journeymap.client.model.region.RegionImageCache
import journeymap.client.model.region.RegionImageSet
import net.minecraft.client.Minecraft
import java.util.BitSet
import java.util.concurrent.Executors

/**
 * BlueMap's terrain on JourneyMap's full-screen map and minimap: pictures over squares of the
 * world, showing BlueMap only where it is needed and see-through everywhere else, so JourneyMap's
 * own map shows through wherever it is good.
 *
 * BlueMap is shown where it has something and JourneyMap is showing nothing just then, or your map
 * there is out of date by the same rules as on Xaero's map: seen before the dimension's
 * [Config.Server.coverBefore], or longer ago than [Config.staleDays] with BlueMap's picture newer.
 *
 * "Showing nothing" is read from the region pictures JourneyMap holds in memory, the very ones it
 * draws ([RegionImageCache]): a region it has not loaded yet is a gap on its map, and BlueMap fills
 * it until JourneyMap has loaded it, as Xaero's map never shows a gap while it loads. Nothing is
 * asked of JourneyMap's one task thread, which it needs for loading its own map.
 *
 * Built to keep up with Xaero's map however far out you zoom, and never to show a gap while zooming:
 *  - A picture covers more of the world the further out you are ([groupFor]): one JourneyMap region
 *    close up, up to 32 by 32 regions far out, so there are tens of pictures on screen, each a few
 *    hundred pixels across.
 *  - The client thread only gathers what a square is made from; which pixels show BlueMap is worked
 *    out with the picture, on threads of their own.
 *  - Zoomed out, BlueMap's coarsest tile is asked where its finest ones have anything before they
 *    are ([TileIndex]), and tiles are not checked with the server, as on Xaero's map.
 *  - Pictures fill in nearest first; a tile still on its way is stood in for by the coarsest one,
 *    blurry, where JourneyMap shows nothing.
 *  - A picture zoomed or panned away from is kept aside, off the map, and goes straight back up when
 *    wanted again; once all on screen is up, the next zoom out is built ahead, kept aside too, over
 *    twice the room. So zooming out shows it at once, as Xaero's map has its tiles ready at every
 *    size, and zooming in shows the coarser pictures until the sharper ones are up.
 *
 * Pictures come from the tiles [TileStore] keeps on disk; nothing is cached here but the pictures on
 * screen, which stay within the texture budget ([Config.textureBudgetMb]) and are let go once off
 * screen or once you leave. Pictures of the last zoom stay up until the new ones over them are
 * ready, so zooming never blanks the map.
 */
internal object JourneyMapTerrain {

    /** [group] by [group] JourneyMap regions starting at region (`x * group`, `z * group`), on the full-screen map or the minimap. */
    private data class Square(val dimension: String, val x: Int, val z: Int, val group: Int, val ui: Context.UI) {
        val span: Int get() = REGION * group
        val westX: Int get() = x * span
        val northZ: Int get() = z * span

        fun overlaps(other: Square): Boolean =
            westX < other.westX + other.span && other.westX < westX + span &&
                northZ < other.northZ + other.span && other.northZ < northZ + other.span
    }

    /** What a picture shows: its cells showing BlueMap, and its scale. */
    private class Made(val wanted: BitSet, val scale: Int)

    /** A square on screen: its picture if it has one, and when and from what it was last decided. */
    private class State {
        var picture: RegionPicture? = null
        var made: Made? = null
        var fingerprint = 0L
        var scale = 0
        var checkedAt = 0L
        var decidedAt = 0L
        var complete = false
        /** Built or kept off the map: a square of the next zoom out made ahead, or one zoomed away from. */
        var hidden = false
        /** When it was last on screen (or built ahead), for letting the oldest kept ones go. */
        var lastWanted = 0L
    }

    private val squares = HashMap<Square, State>()

    /** Squares whose picture is being worked out. */
    private val building = HashSet<Square>()

    /** One map of JourneyMap's: a dimension's day map, or one of its cave slices. */
    private data class Folder(val dimension: String, val type: Context.MapType, val slice: Int?)

    /** A JourneyMap region of one of its maps. */
    private data class Region(val folder: Folder, val x: Int, val z: Int)

    /** What JourneyMap was showing of a region, by chunk, when last read at [at]; [stamp]: its picture then. */
    private class Mapped(val chunks: BitSet, val at: Long, val stamp: Long)

    private val mapped = HashMap<Region, Mapped>()

    /** When you were last seen in each chunk of a region (by chunk, z by x), read at [at]: the date rules run off the client thread from this. */
    private class Seen(val minutes: IntArray, val at: Long)

    private val seen = HashMap<Region, Seen>()

    /** Bumped on leaving, so a picture meant for the last world is thrown away when it arrives. */
    private var generation = 0

    /** Pictures are worked out and built here rather than on the session's workers, so they never wait behind tile downloads. */
    private val builder = Executors.newFixedThreadPool((Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)) { task ->
        Thread(task, "SkysMapExposer JourneyMap terrain").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private val lastActive = HashMap<Context.UI, Long>()

    /** Client thread, every tick. */
    fun tick(api: IClientAPI, session: Session?) {
        tickFor(api, session, Context.UI.Fullscreen)
        tickFor(api, session, Context.UI.Minimap)
        // Tiles neither map has drawn for a while are let go, as on Xaero's map.
        session?.tiles?.trim(budget())
    }

    private fun tickFor(api: IClientAPI, session: Session?, ui: Context.UI) {
        val fullscreen = ui == Context.UI.Fullscreen
        fun say(text: String) { if (fullscreen) note = text else minimapNote = text }
        val now = System.currentTimeMillis()
        val state = api.getUIState(ui)
        val off = when {
            session == null -> "this server has no BlueMap set up"
            !Config.enabled -> "switched off"
            state == null -> "JourneyMap has not said what it shows"
            !state.active -> if (fullscreen) "the full-screen map is closed" else "the minimap is hidden"
            state.mapType != Context.MapType.Day && state.mapType != Context.MapType.Underground -> "BlueMap shows on JourneyMap's day and cave maps"
            else -> null
        }
        say(off ?: "")
        if (off != null || session == null || state == null) {
            // Kept while the map is only closed for a moment, and let go once it has stayed closed;
            // switched off, no BlueMap, or another kind of map: gone at once, as on Xaero's map.
            val closedOnly = session != null && Config.enabled && state != null && !state.active
            if (squares.keys.any { it.ui == ui } && (!closedOnly || now - (lastActive[ui] ?: 0L) > KEEP_CLOSED_MS)) clear(api, ui)
            return
        }
        lastActive[ui] = now
        val dimension = state.dimension?.identifier()?.toString() ?: return say("JourneyMap's map has no dimension")
        val map = session.server.mapFor(dimension) ?: return clear(api, ui).also { say("BlueMap is not used for $dimension") }
        val layout = session.layout(map) ?: return say("waiting for BlueMap's settings for '$map'")
        // Only on the layer BlueMap's picture belongs in, as on Xaero's map: the surface (JourneyMap's
        // day map), or in the Nether whichever of the roof or the inside BlueMap's map shows.
        if (!JourneyMapAdapter.blueMapLayerShown(session, dimension, map, state)) {
            clear(api, ui)
            say(if (state.mapType == Context.MapType.Underground) "BlueMap only has the surface here" else "BlueMap's map here shows the inside; switch to the cave map")
            return
        }
        // What is on screen, worked out here: JourneyMap's own account of it lags a moved full-screen
        // map and is never kept up to date for the minimap.
        val area = (if (fullscreen) JourneyMapMenus.shownArea(state) else JourneyMapMinimap.area)
            ?: return say(if (fullscreen) "JourneyMap has not said what is on screen" else "waiting for the minimap to draw")
        val minX = area[0]
        val minZ = area[1]
        val maxX = area[2]
        val maxZ = area[3]

        // The tile's own size for this zoom, as on Xaero's map ([TilePixels.factorsFor]); coarser
        // only while the pictures on screen would not fit the budget.
        val factors = TilePixels.factorsFor(layout.tileSize)
        val pixelsPerBlock = if (fullscreen) JourneyMapMenus.pixelsPerBlock(state) else state.blockSize
        val blocksPerPixel = if (pixelsPerBlock > 0) 1 / pixelsPerBlock else 1.0
        var step = factors.indexOfLast { it <= blocksPerPixel.coerceAtLeast(1.0) }.coerceAtLeast(0)
        fun bytesAt(scale: Int): Long {
            val span = REGION * groupFor(scale)
            val across = (Math.floorDiv(maxX.toInt(), span) - Math.floorDiv(minX.toInt(), span) + 1).toLong()
            val down = (Math.floorDiv(maxZ.toInt(), span) - Math.floorDiv(minZ.toInt(), span) + 1).toLong()
            return across * down * pictureBytes(span, scale)
        }
        while (step < factors.lastIndex && bytesAt(factors[step]) > budget()) step++
        val scale = factors[step]
        val group = groupFor(scale)
        val span = REGION * group

        val centreX = (minX + maxX) / 2
        val centreZ = (minZ + maxZ) / 2
        val wanted = ArrayList<Square>()
        for (z in Math.floorDiv(minZ.toInt(), span)..Math.floorDiv(maxZ.toInt(), span)) {
            for (x in Math.floorDiv(minX.toInt(), span)..Math.floorDiv(maxX.toInt(), span)) wanted.add(Square(dimension, x, z, group, ui))
        }
        fun distance(square: Square): Double {
            val dx = square.westX + square.span / 2.0 - centreX
            val dz = square.northZ + square.span / 2.0 - centreZ
            return dx * dx + dz * dz
        }
        wanted.sortBy(::distance)
        if (wanted.size > MAX_SQUARES) wanted.subList(MAX_SQUARES, wanted.size).clear()
        val wantedSet = wanted.toHashSet()

        // Off screen: kept aside, or gone. A picture of another zoom stays up until every new one over it is.
        for ((square, held) in squares.entries.filter { (it, held) -> it.ui == ui && it !in wantedSet && !held.hidden }) {
            val onScreen = square.dimension == dimension && square.westX < maxX && square.westX + square.span > minX &&
                square.northZ < maxZ && square.northZ + square.span > minZ
            val replaced = !onScreen || square.group == group || held.picture == null ||
                wanted.none { it.overlaps(square) && squares[it]?.let { new -> new.made == null } ?: true }
            if (replaced) keepAside(api, square, held, dimension)
        }
        // Back on screen: a kept picture goes straight back up, as Xaero's tiles do, and is checked as usual.
        for (square in wanted) {
            val held = squares[square] ?: continue
            held.lastWanted = now
            if (!held.hidden) continue
            held.hidden = false
            if (held.picture?.unhide(api) == false) {
                held.picture?.release()
                held.picture = null
                held.made = null
            }
        }
        if (fullscreen) letGoOfKept(api)

        // What JourneyMap holds of this map just now, by region: what it is drawing.
        val folder = Folder(dimension, state.mapType, if (state.mapType == Context.MapType.Underground) state.chunkY else null)
        val holding = heldRegions(folder) ?: return say("JourneyMap's map folder is not known yet")

        // Around you JourneyMap is mapping as you go.
        val player = Minecraft.getInstance().player
        val near = player != null && player.level().dimension().identifier().toString() == dimension
        val playerRegionX = player?.let { Math.floorDiv(it.blockX, REGION) } ?: 0
        val playerRegionZ = player?.let { Math.floorDiv(it.blockZ, REGION) } ?: 0
        fun isNear(regionX: Int, regionZ: Int) = near && Math.abs(regionX - playerRegionX) <= 1 && Math.abs(regionZ - playerRegionZ) <= 1

        val seaFloor = Config.seaFloorHires && !SeaFloor.hasCeiling(dimension)
        val useIndex = layout.lodCount > 1 &&
            ((maxX - minX) / layout.tileSize + 1) * ((maxZ - minZ) / layout.tileSize + 1) > TileIndex.ABOVE
        val pass = Pass(session, map, layout, scale, holding, mapTypeOf(folder), useIndex, seaFloor, now, ::isNear, minX, maxX, minZ, maxZ, centreX, centreZ)

        // Nearest first, within a slice of each tick, as Xaero spreads its masks over frames.
        val started = System.nanoTime()
        var waiting = 0
        for (square in wanted) {
            val held = squares[square]
            if (square in building) { waiting++; continue }
            val nearYou = (square.x * group until (square.x + 1) * group).any { rx ->
                (square.z * group until (square.z + 1) * group).any { rz -> isNear(rx, rz) }
            }
            val due = held == null || held.scale != scale ||
                (!held.complete && now - held.checkedAt > INCOMPLETE_MS) ||
                now - held.checkedAt > (if (nearYou) RECHECK_MS else HELD_RECHECK_MS) ||
                now - held.decidedAt > (if (nearYou) RECHECK_MS else FAR_RECHECK_MS)
            if (!due) {
                if (!held.complete) waiting++
                continue
            }
            if (System.nanoTime() - started > SLICE_NS) { waiting++; continue }
            decide(api, square, held ?: State().also { squares[square] = it }, pass, folder, nearYou)
            if (squares[square]?.complete != true) waiting++
        }
        say(if (waiting > 0) "filling in $waiting of ${wanted.size} squares" else "")
        // Once everything on screen is up: the next zoom out, over twice the room, built ahead and kept
        // aside, so zooming out shows it at once rather than filling in from the middle.
        if (fullscreen && waiting == 0 && building.isEmpty()) {
            buildAhead(api, session, dimension, map, layout, folder, holding, factors, step, blocksPerPixel, seaFloor, now, ::isNear,
                minX, maxX, minZ, maxZ, centreX, centreZ, started)
        }
        if (mapped.size > MAX_REMEMBERED) mapped.entries.removeIf { now - it.value.at > FORGET_MS }
        if (seen.size > MAX_REMEMBERED) seen.entries.removeIf { now - it.value.at > FORGET_MS }
    }

    /** What every square on screen this tick is decided against. */
    private class Pass(
        val session: Session, val map: String, val layout: BlueMap.MapLayout, val scale: Int,
        val holding: Map<Long, RegionImageSet>, val mapType: MapType?, val useIndex: Boolean, val seaFloor: Boolean, val now: Long,
        val isNear: (Int, Int) -> Boolean,
        val minX: Double, val maxX: Double, val minZ: Double, val maxZ: Double, val centreX: Double, val centreZ: Double,
    )

    /**
     * The tiles under a square for its picture: a grid of the finest tiles from ([firstX], [firstZ]),
     * each READY, or the coarsest one standing in for it where [standIn], or null.
     */
    private class Sources(val firstX: Int, val firstZ: Int, val across: Int, val down: Int, val tiles: Array<TileStore.Tile?>, val standIn: BooleanArray) {
        fun at(blockX: Int, blockZ: Int, tileSize: Int): Int {
            val x = Math.floorDiv(blockX, tileSize) - firstX
            val z = Math.floorDiv(blockZ, tileSize) - firstZ
            return if (x !in 0 until across || z !in 0 until down) -1 else z * across + x
        }
    }

    /** Everything a square's picture is worked out from, gathered on the client thread for a builder. */
    private class Job(
        val square: Square, val sources: Sources, val tileSize: Int, val scale: Int,
        /** What JourneyMap shows of each region of the square (z by x), by chunk. */
        val regions: Array<BitSet>,
        /** When you last saw each chunk, for the regions JourneyMap shows anything of; null for the rest. */
        val seen: Array<IntArray?>,
        val coverBefore: Int, val staleMinutes: Int, val nowMinute: Int,
        /** The square's picture now, if any: a picture the same as it is not built again. */
        val old: Made?,
        val generation: Int,
    )

    /**
     * Gathers what [square]'s picture is made from and, if any of it changed since it was last
     * decided (or the date rules are due again), hands it to a builder.
     */
    private fun decide(api: IClientAPI, square: Square, held: State, pass: Pass, folder: Folder, nearYou: Boolean) {
        val session = pass.session
        val layout = pass.layout
        val size = layout.tileSize
        val scale = pass.scale
        val now = pass.now
        held.checkedAt = now
        var complete = true
        var fingerprint = scale.toLong()
        fun mix(value: Long) { fingerprint = fingerprint * 1_000_003L + value }

        // The tiles.
        val firstX = Math.floorDiv(square.westX, size)
        val firstZ = Math.floorDiv(square.northZ, size)
        val across = Math.floorDiv(square.westX + square.span - 1, size) - firstX + 1
        val down = Math.floorDiv(square.northZ + square.span - 1, size) - firstZ + 1
        val tiles = arrayOfNulls<TileStore.Tile>(across * down)
        val standIn = BooleanArray(across * down)
        for (z in 0 until down) for (x in 0 until across) {
            val tileX = firstX + x
            val tileZ = firstZ + z
            if (pass.useIndex) {
                when (TileIndex.says(session, layout, pass.map, tileX, tileZ)) {
                    null -> { complete = false; continue }
                    false -> continue
                    true -> {}
                }
            }
            val tile = session.tiles.get(TileStore.Key(pass.map, 1, tileX, tileZ), 1, size, mayCheck = scale <= CHECK_UP_TO_FACTOR)
            val slot = z * across + x
            when (tile.state) {
                TileStore.State.READY -> {
                    tiles[slot] = tile
                    mix(tile.contentMinute.toLong())
                    mix(System.identityHashCode(tile.floor).toLong())
                    if (pass.seaFloor) {
                        session.seaFloor.want(tile, pass.map, pass.centreX, pass.centreZ, pass.minX, pass.maxX, pass.minZ, pass.maxZ,
                            closeUp = scale <= SeaFloor.CLOSE_UP)
                    }
                }
                TileStore.State.LOADING -> {
                    complete = false
                    if (layout.lodCount > 1) {
                        val index = TileIndex.over(session, layout, pass.map, tileX, tileZ)
                        if (index.state == TileStore.State.READY) {
                            tiles[slot] = index
                            standIn[slot] = true
                            mix(-index.contentMinute.toLong())
                        }
                    }
                }
                TileStore.State.EMPTY, TileStore.State.FAILED -> mix(1)
            }
        }

        // What JourneyMap shows, region by region, and your visits where it shows anything.
        val group = square.group
        val regions = Array(group * group) { NOTHING }
        val seens = arrayOfNulls<IntArray>(group * group)
        for (z in 0 until group) for (x in 0 until group) {
            val regionX = square.x * group + x
            val regionZ = square.z * group + z
            val region = Region(folder, regionX, regionZ)
            val near = pass.isNear(regionX, regionZ)
            val chunks = mappedIn(region, pass, near)
            regions[z * group + x] = chunks
            mix(System.identityHashCode(chunks).toLong())
            if (!chunks.isEmpty) seens[z * group + x] = seenIn(region, pass, near)
        }

        // Nothing changed since it was last decided, and not due for the date rules: it stands.
        val timeDue = now - held.decidedAt > (if (nearYou) RECHECK_MS else FAR_RECHECK_MS)
        held.complete = complete
        if (held.made != null && held.scale == scale && held.fingerprint == fingerprint && !timeDue) return
        held.fingerprint = fingerprint
        held.scale = scale
        held.decidedAt = now

        val job = Job(
            square, Sources(firstX, firstZ, across, down, tiles, standIn), size, scale, regions, seens,
            session.server.coverBefore[square.dimension]?.let(Clock::minutesOf) ?: 0, Config.staleMinutes, Clock.nowMinutes(),
            held.made.takeIf { held.picture != null }, generation,
        )
        building.add(square)
        try {
            builder.execute { build(api, session, job) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            building.remove(square)
        }
    }

    /**
     * What JourneyMap is showing of [region], by chunk: read from the picture it holds, again
     * whenever that picture changes (and around you every [RECHECK_MS], where it maps); nothing
     * where it holds no picture, which is a gap on its map.
     */
    private fun mappedIn(region: Region, pass: Pass, near: Boolean): BitSet {
        val set = pass.holding[packXZ(region.x, region.z)]
        val holder = try {
            pass.mapType?.let { set?.getExistingHolder(it) }
        } catch (e: LinkageError) {
            null
        }
        if (holder == null) {
            mapped.remove(region)
            return NOTHING
        }
        val known = mapped[region]
        val image = holder.image ?: return NOTHING
        val stamp = holder.imageTimestamp * 31 + System.identityHashCode(image)
        if (known != null && known.stamp == stamp && !(near && pass.now - known.at > RECHECK_MS)) return known.chunks
        val chunks = BitSet(CHUNKS * CHUNKS)
        val width = image.width
        val height = image.height
        if (width >= CHUNKS && height >= CHUNKS) {
            // A chunk counts as shown if the middle of it is not see-through.
            for (chunkZ in 0 until CHUNKS) for (chunkX in 0 until CHUNKS) {
                val x = (chunkX * width + width / 2) / CHUNKS
                val y = (chunkZ * height + height / 2) / CHUNKS
                if (image.getPixel(x, y) ushr 24 != 0) chunks.set(chunkZ * CHUNKS + chunkX)
            }
        }
        // The same picture as before: the same set, so the square's fingerprint stands.
        val result = if (known != null && known.chunks == chunks) known.chunks else if (chunks.isEmpty) NOTHING else chunks
        mapped[region] = Mapped(result, pass.now, stamp)
        return result
    }

    /** When you last saw each chunk of [region], read again every [FAR_RECHECK_MS] (around you, [RECHECK_MS]). */
    private fun seenIn(region: Region, pass: Pass, near: Boolean): IntArray {
        val known = seen[region]
        if (known != null && pass.now - known.at < (if (near) RECHECK_MS else FAR_RECHECK_MS)) return known.minutes
        val minutes = IntArray(CHUNKS * CHUNKS)
        for (chunkZ in 0 until CHUNKS) for (chunkX in 0 until CHUNKS) {
            minutes[chunkZ * CHUNKS + chunkX] = pass.session.visits.lastSeen(region.folder.dimension, region.x * CHUNKS + chunkX, region.z * CHUNKS + chunkZ)
        }
        seen[region] = Seen(minutes, pass.now)
        return minutes
    }

    /**
     * JourneyMap's region pictures of [folder]'s map it holds just now, by region key; null when
     * its folder for the dimension is not known.
     */
    private fun heldRegions(folder: Folder): Map<Long, RegionImageSet>? {
        val dimensionFolder = JourneyMapFiles.dimensionFolder(folder.dimension) ?: return null
        return try {
            val found = HashMap<Long, RegionImageSet>()
            for (set in RegionImageCache.INSTANCE.regionImageSets) {
                val coord = set.regionCoord ?: continue
                if (coord.dimDir == dimensionFolder) found[packXZ(coord.regionX, coord.regionZ)] = set
            }
            found
        } catch (e: LinkageError) {
            if (!warnedMemory) Log.warn("This JourneyMap keeps its map differently; BlueMap's terrain shows wherever BlueMap has something: {}", e.toString())
            warnedMemory = true
            emptyMap()
        }
    }

    private var warnedMemory = false

    /** JourneyMap's own name for [folder]'s map, to find its pictures by; null if this JourneyMap has none. */
    private fun mapTypeOf(folder: Folder): MapType? = try {
        val key = levelKey(folder.dimension)
        if (folder.type == Context.MapType.Underground) MapType.underground(folder.slice ?: 0, key) else MapType.day(key)
    } catch (e: LinkageError) {
        null
    }

    /**
     * Region ([regionX], [regionZ]) of [dimension] was just written into ([JourneyMapEdits]): its
     * squares are decided again at once.
     */
    fun changed(dimension: String, regionX: Int, regionZ: Int) {
        mapped.keys.removeIf { it.folder.dimension == dimension && it.x == regionX && it.z == regionZ }
        squares.forEach { (square, held) ->
            if (square.dimension == dimension && regionX in square.x * square.group until (square.x + 1) * square.group &&
                regionZ in square.z * square.group until (square.z + 1) * square.group) {
                held.checkedAt = 0L
                held.decidedAt = 0L
            }
        }
    }

    /**
     * A square no longer on screen: on the full-screen map its picture is kept aside, off the map, to
     * go straight back up if it is wanted again (zooming back, panning back); anything else is let go.
     */
    private fun keepAside(api: IClientAPI, square: Square, held: State, dimension: String) {
        val picture = held.picture
        if (square.ui != Context.UI.Fullscreen || square.dimension != dimension || picture == null) return forget(api, square)
        building.remove(square)
        picture.hide(api)
        held.hidden = true
    }

    /** Kept-aside pictures beyond half the texture budget are let go, those wanted longest ago first. */
    private fun letGoOfKept(api: IClientAPI) {
        val kept = squares.entries.filter { (square, held) -> held.hidden && square.ui == Context.UI.Fullscreen }
        var bytes = kept.sumOf { (_, held) -> held.picture?.let { it.side.toLong() * it.side * 4 } ?: 0L }
        if (bytes <= budget() / 2) return
        for ((square, held) in kept.sortedBy { it.value.lastWanted }) {
            if (bytes <= budget() / 2) break
            bytes -= held.picture?.let { it.side.toLong() * it.side * 4 } ?: 0L
            forget(api, square)
        }
    }

    /**
     * The squares of the next zoom out, around what is on screen and twice as wide, worked out and
     * built off the map, nearest first within what is left of this tick's slice; skipped if they would
     * not fit half the texture budget.
     */
    private fun buildAhead(
        api: IClientAPI, session: Session, dimension: String, map: String, layout: BlueMap.MapLayout, folder: Folder,
        holding: Map<Long, RegionImageSet>, factors: List<Int>, step: Int, blocksPerPixel: Double, seaFloor: Boolean, now: Long,
        isNear: (Int, Int) -> Boolean, minX: Double, maxX: Double, minZ: Double, maxZ: Double, centreX: Double, centreZ: Double, started: Long,
    ) {
        val aheadStep = factors.indexOfLast { it <= (blocksPerPixel * 2).coerceAtLeast(1.0) }
        if (aheadStep <= step) return
        val scale = factors[aheadStep]
        val group = groupFor(scale)
        val span = REGION * group
        val width = maxX - minX
        val height = maxZ - minZ
        val aMinX = centreX - width
        val aMaxX = centreX + width
        val aMinZ = centreZ - height
        val aMaxZ = centreZ + height
        val ahead = ArrayList<Square>()
        for (z in Math.floorDiv(aMinZ.toInt(), span)..Math.floorDiv(aMaxZ.toInt(), span)) {
            for (x in Math.floorDiv(aMinX.toInt(), span)..Math.floorDiv(aMaxX.toInt(), span)) ahead.add(Square(dimension, x, z, group, Context.UI.Fullscreen))
        }
        if (ahead.size > MAX_SQUARES || ahead.size * pictureBytes(span, scale) > budget() / 2) return
        ahead.sortBy { val dx = it.westX + it.span / 2.0 - centreX; val dz = it.northZ + it.span / 2.0 - centreZ; dx * dx + dz * dz }
        val useIndex = layout.lodCount > 1 && ((aMaxX - aMinX) / layout.tileSize + 1) * ((aMaxZ - aMinZ) / layout.tileSize + 1) > TileIndex.ABOVE
        val pass = Pass(session, map, layout, scale, holding, mapTypeOf(folder), useIndex, seaFloor, now, isNear, aMinX, aMaxX, aMinZ, aMaxZ, centreX, centreZ)
        for (square in ahead) {
            if (System.nanoTime() - started > SLICE_NS) return
            if (square in building || squares.containsKey(square)) continue
            val held = State().also { it.hidden = true; it.lastWanted = now; squares[square] = it }
            decide(api, square, held, pass, folder, nearYou = false)
        }
    }

    /** Leaving a world: JourneyMap drops our overlays ([JourneyMapAdapter.leave]); this frees the pictures. */
    fun forget() {
        generation++
        squares.values.forEach { it.picture?.release() }
        squares.clear()
        building.clear()
        mapped.clear()
        seen.clear()
    }

    private fun clear(api: IClientAPI, ui: Context.UI) {
        squares.keys.filter { it.ui == ui }.forEach { forget(api, it) }
    }

    private fun forget(api: IClientAPI, square: Square) {
        val gone = squares.remove(square) ?: return
        building.remove(square)
        gone.picture?.let { remove(api, square, it) }
    }

    private fun remove(api: IClientAPI, square: Square, picture: RegionPicture) {
        picture.remove(api)
        if (square.ui == Context.UI.Minimap) JourneyMapMinimap.changed()
    }

    /**
     * Off the client thread: which cells of the square show BlueMap, by the rules above, and the
     * picture of them, BlueMap in those cells and see-through elsewhere. A cell is a chunk close up,
     * and a pixel once a pixel covers a chunk or more, so a far-out square costs what its picture
     * does, not what its chunks do.
     */
    private fun build(api: IClientAPI, session: Session, job: Job) {
        val square = job.square
        val sources = job.sources
        val size = job.tileSize
        val scale = job.scale
        val group = square.group
        val byPixel = scale >= 16
        // Rounded up: a tile size's factor need not divide the square; the last pixel runs a little past it.
        val side = (square.span + scale - 1) / scale
        val cells = if (byPixel) side else square.span / 16
        val wanted = BitSet(cells * cells)
        val firstChunkX = square.westX shr 4
        val firstChunkZ = square.northZ shr 4
        for (cz in 0 until cells) for (cx in 0 until cells) {
            val chunkX = if (byPixel) (square.westX + cx * scale) shr 4 else firstChunkX + cx
            val chunkZ = if (byPixel) (square.northZ + cz * scale) shr 4 else firstChunkZ + cz
            val middleX = (chunkX shl 4) + 8
            val middleZ = (chunkZ shl 4) + 8
            val slot = sources.at(middleX, middleZ, size)
            if (slot < 0) continue
            val tile = sources.tiles[slot] ?: continue
            // BlueMap must have something at the middle of the chunk, or it would replace your map with nothing.
            if (!hasDataAt(tile, middleX, middleZ)) continue
            val regionX = Math.floorDiv(chunkX, 32) - square.x * group
            val regionZ = Math.floorDiv(chunkZ, 32) - square.z * group
            if (regionX !in 0 until group || regionZ !in 0 until group) continue
            val inRegion = Math.floorMod(chunkZ, 32) * 32 + Math.floorMod(chunkX, 32)
            val index = cz * cells + cx
            if (!job.regions[regionZ * group + regionX][inRegion]) { wanted.set(index); continue }
            // A blurry stand-in only where JourneyMap shows nothing.
            if (sources.standIn[slot]) continue
            val seen = job.seen[regionZ * group + regionX]?.get(inRegion) ?: 0
            if (job.coverBefore != 0 && seen < job.coverBefore) { wanted.set(index); continue }
            // JourneyMap keeps no dates of its own, so ground never seen since the mod was installed counts as current.
            if (seen != 0 && job.nowMinute - seen >= job.staleMinutes && tile.contentMinute > seen) wanted.set(index)
        }
        val made = Made(wanted, scale)
        val old = job.old
        // The same as the picture up: it stands.
        if ((wanted.isEmpty && old == null) || (old != null && old.scale == scale && old.wanted == wanted)) {
            Minecraft.getInstance().execute { settle(api, square, job.generation, if (wanted.isEmpty) made else old ?: made, keep = !wanted.isEmpty) }
            return
        }
        if (wanted.isEmpty) {
            Minecraft.getInstance().execute { settle(api, square, job.generation, made, keep = false) }
            return
        }
        val image = NativeImage(side, side, true)
        // Each tile's picture as read for this one: (pixels, blocks a pixel), or null if it has none.
        val pictures = HashMap<TileStore.Tile, Pair<IntArray, Int>?>()
        try {
            for (py in 0 until side) {
                val blockZ = square.northZ + py * scale
                for (px in 0 until side) {
                    val blockX = square.westX + px * scale
                    val cell = if (byPixel) py * cells + px else ((blockZ - square.northZ) shr 4) * cells + ((blockX - square.westX) shr 4)
                    if (!wanted[cell]) continue
                    val slot = sources.at(blockX, blockZ, size)
                    if (slot < 0) continue
                    val tile = sources.tiles[slot] ?: continue
                    val per = tile.blocksPerPixel
                    val reach = tile.tileSize * per
                    val inX = Math.floorDiv(blockX - tile.key.x * reach, per)
                    val inZ = Math.floorDiv(blockZ - tile.key.z * reach, per)
                    if (inX !in 0 until tile.tileSize || inZ !in 0 until tile.tileSize || !tile.hasDataAt(inX, inZ)) continue
                    val (pixels, factor) = pictures.getOrPut(tile) {
                        // The finest tile's own smaller copy when it has one at this scale, else its
                        // full one, sampled; a coarsest tile standing in, as it is. Sea floor in, either way.
                        val smaller = if (sources.standIn[slot] || scale == 1) null else session.tiles.drawnPicture(tile, scale)
                        smaller?.let { it to scale } ?: session.tiles.drawnPicture(tile, 1)?.let { it to 1 }
                    } ?: continue
                    val pictureSide = tile.tileSize / factor
                    val colour = pixels[(inZ / factor).coerceAtMost(pictureSide - 1) * pictureSide + (inX / factor).coerceAtMost(pictureSide - 1)]
                    if (colour != 0) image.setPixel(px, py, colour or OPAQUE)
                }
            }
        } catch (e: Exception) {
            image.close()
            Minecraft.getInstance().execute { building.remove(square) }
            Log.warn("Could not build BlueMap's picture at {}, {} for JourneyMap: {}", square.westX, square.northZ, e.toString())
            return
        }
        Minecraft.getInstance().execute { show(api, square, image, scale, job.generation, made) }
    }

    /** Whether [tile] (finest or coarsest) has anything at block ([x], [z]). */
    private fun hasDataAt(tile: TileStore.Tile, x: Int, z: Int): Boolean {
        val per = tile.blocksPerPixel
        val reach = tile.tileSize * per
        val pixelX = Math.floorDiv(x - tile.key.x * reach, per).coerceIn(0, tile.tileSize - 1)
        val pixelZ = Math.floorDiv(z - tile.key.z * reach, per).coerceIn(0, tile.tileSize - 1)
        return tile.hasDataAt(pixelX, pixelZ)
    }

    /** Client thread: a square decided with no new picture: the one up stays ([keep]), or nothing is shown. */
    private fun settle(api: IClientAPI, square: Square, generation: Int, made: Made, keep: Boolean) {
        val held = squares[square]
        if (generation != this.generation || square !in building || held == null) return
        building.remove(square)
        held.made = made
        if (!keep) {
            held.picture?.let { remove(api, square, it) }
            held.picture = null
        }
    }

    /** Client thread: puts the finished picture up, replacing the square's old one. */
    private fun show(api: IClientAPI, square: Square, image: NativeImage, scale: Int, generation: Int, made: Made) {
        val held = squares[square]
        if (generation != this.generation || square !in building || held == null) {
            image.close()
            return
        }
        building.remove(square)
        val fullscreen = square.ui == Context.UI.Fullscreen
        val picture = RegionPicture.show(api, "terrain", square.dimension, square.westX, square.northZ, scale, image, fullscreen, TERRAIN_ORDER, visible = !held.hidden)
        held.picture?.let { remove(api, square, it) }
        held.picture = picture
        held.made = made
        if (!fullscreen) JourneyMapMinimap.changed()
    }

    /** Why nothing is being drawn just now, if anything stops it, on the full-screen map and the minimap. */
    private var note = ""
    private var minimapNote = ""

    /** "6 pictures of terrain" on JourneyMap now, and why not more if something is in the way, for `/mapexposer`. */
    fun summary(): String {
        val full = squares.count { (square, held) -> square.ui == Context.UI.Fullscreen && held.picture != null && !held.hidden }
        val mini = squares.count { (square, held) -> square.ui == Context.UI.Minimap && held.picture != null }
        return "$full picture${if (full == 1) "" else "s"} of terrain" + (if (note.isEmpty()) "" else " ($note)") +
            ", $mini on the minimap" + (if (minimapNote.isEmpty()) "" else " ($minimapNote)")
    }

    /** The minimap's pictures of [dimension], for [JourneyMapMinimap] to draw. */
    fun minimapPictures(dimension: String): List<RegionPicture> =
        squares.mapNotNull { (square, held) -> held.picture.takeIf { square.ui == Context.UI.Minimap && square.dimension == dimension } }

    /**
     * Regions a picture covers along each side at [scale] blocks a pixel: about half as many as
     * blocks a pixel, so a picture is 200 to 500 pixels across whatever the zoom.
     */
    private fun groupFor(scale: Int): Int {
        var group = 1
        while (group * 2 <= scale / 2 && group < MAX_GROUP) group *= 2
        return group
    }

    private fun budget(): Long = Config.textureBudgetMb.toLong() * 1024 * 1024

    private fun pictureBytes(span: Int, scale: Int): Long = ((span + scale - 1) / scale).toLong().let { it * it * 4 }

    /** JourneyMap shows nothing of a region: shared, never changed. */
    private val NOTHING = BitSet(0)

    private const val CHUNKS = REGION / 16
    private const val MAX_GROUP = 32
    /** Squares drawn at most, nearest first, as Xaero draws at most 1024 tiles. */
    private const val MAX_SQUARES = 1024
    /** Client time spent gathering squares a tick at most. */
    private const val SLICE_NS = 4_000_000L
    /** Tiles are checked with the server only this close in, as on Xaero's map. */
    private const val CHECK_UP_TO_FACTOR = 4

    /** How often a square is decided again around you, where JourneyMap is mapping, and further off, where only dates move on. */
    private const val RECHECK_MS = 5_000L
    private const val FAR_RECHECK_MS = 30_000L
    /** How often what JourneyMap holds under a square is looked at again: it loads and lets go of regions as you look around. */
    private const val HELD_RECHECK_MS = 1_000L
    /** How often a square still waiting for tiles is looked at again. */
    private const val INCOMPLETE_MS = 300L
    private const val KEEP_CLOSED_MS = 30_000L
    private const val MAX_REMEMBERED = 20_000
    private const val FORGET_MS = 10 * 60_000L
    private const val OPAQUE = 0xFF000000.toInt()

    /** Under the outlines and pins ([JourneyMapPins]). */
    private const val TERRAIN_ORDER = 50
}
