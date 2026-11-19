package com.skystormer.skysmapexposer.journeymap

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
import net.minecraft.client.Minecraft
import net.minecraft.world.level.ChunkPos
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet
import java.util.concurrent.Executors

/**
 * BlueMap's terrain on JourneyMap's full-screen map and minimap: pictures over squares of the
 * world, showing BlueMap only in the chunks that need it and see-through everywhere else, so
 * JourneyMap's own map shows through wherever it is good.
 *
 * A chunk needs it when BlueMap has something there and either JourneyMap has nothing, or your map
 * of it is out of date by the same rules as on Xaero's map: seen before the dimension's
 * [Config.Server.coverBefore], or longer ago than [Config.staleDays] with BlueMap's picture newer.
 *
 * Built to keep up with Xaero's map however far out you zoom, the way Xaero's does it:
 *  - A picture covers more of the world the further out you are ([groupFor]): one JourneyMap
 *    region close up, up to 32 by 32 regions far out, so there are tens of pictures on screen, not
 *    thousands, each a few hundred pixels across.
 *  - What JourneyMap has mapped is asked of it (`requestMapTile`, which runs on JourneyMap's one
 *    task thread, shared with its own mapping) only where it has a picture on disk, once per
 *    change of that file, and around you, where it is mapping; everywhere else its folder listing
 *    says it has nothing. A few questions at a time ([MAX_ASKING]), so JourneyMap's own work is
 *    never stuck behind a zoomed-out screen's worth of them.
 *  - Zoomed out, BlueMap's coarsest tile is asked where its finest ones have anything before they
 *    are ([TileIndex]), and tiles are not checked with the server, as on Xaero's map.
 *  - Pictures fill in as tiles arrive, nearest first; a tile still on its way is stood in for by
 *    the coarsest one, blurry, only where JourneyMap has nothing at all.
 *
 * Pictures come from the tiles [TileStore] keeps on disk, built on a thread of their own; nothing
 * is cached here but the pictures on screen, which stay within the texture budget
 * ([Config.textureBudgetMb]) and are let go once off screen or once you leave. Pictures of the last
 * zoom stay up until the new ones over them are ready, so zooming never blanks the map.
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

    /** What a picture was made from: the cells shown, a fingerprint of all it was decided from, and its scale. */
    private class Made(val wanted: BitSet, val fingerprint: Long, val scale: Int)

    /** A square on screen: its picture if it has one, and when and from what it was last decided. */
    private class State {
        var picture: RegionPicture? = null
        var made: Made? = null
        var fingerprint = 0L
        var scale = 0
        var checkedAt = 0L
        var decidedAt = 0L
        var complete = false
    }

    private val squares = HashMap<Square, State>()

    /** Squares whose picture is being built. */
    private val building = HashSet<Square>()

    /** One map of JourneyMap's: a dimension's day map, or one of its cave slices. */
    private data class Folder(val dimension: String, val type: Context.MapType, val slice: Int?)

    /** A JourneyMap region of one of its maps. */
    private data class Region(val folder: Folder, val x: Int, val z: Int)

    /** What JourneyMap had mapped in a region, by chunk, when asked at [at]; [stamp]: its file's date then. */
    private class Mapped(val chunks: BitSet, val at: Long, val stamp: Long)

    private val mapped = HashMap<Region, Mapped>()
    private val asking = HashSet<Region>()

    /** Each map's region pictures on disk and their dates (region key to epoch milliseconds), listed every [LISTING_MS]. */
    private class Listing(val dates: Map<Long, Long>, val at: Long)

    private val listings = HashMap<Folder, Listing>()
    private val listing = HashSet<Folder>()

    /** Bumped on leaving, so a picture or answer meant for the last world is thrown away when it arrives. */
    private var generation = 0

    /** Pictures are built here rather than on the session's workers, so they never wait behind tile downloads. */
    private val builder = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "SkysMapExposer JourneyMap terrain").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    private val lastActive = HashMap<Context.UI, Long>()

    /** Client thread, every tick. */
    fun tick(api: IClientAPI, session: Session?) {
        this.api = api
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

        // Off screen: gone. A picture of another zoom stays until every new one over it is up.
        for ((square, held) in squares.entries.filter { (it, _) -> it.ui == ui && it !in wantedSet }) {
            val onScreen = square.dimension == dimension && square.westX < maxX && square.westX + square.span > minX &&
                square.northZ < maxZ && square.northZ + square.span > minZ
            val replaced = !onScreen || square.group == group || held.picture == null ||
                wanted.none { it.overlaps(square) && squares[it]?.let { new -> new.picture == null && !new.complete } ?: true }
            if (replaced) forget(api, square)
        }

        // JourneyMap's map files: where it has nothing on disk it is not asked.
        val folder = Folder(dimension, state.mapType, if (state.mapType == Context.MapType.Underground) state.chunkY else null)
        val files = listingOf(session, folder, now) ?: return say("reading JourneyMap's map folder")

        // Around you JourneyMap is mapping, and what it has is newer than its files.
        val player = Minecraft.getInstance().player
        val near = player != null && player.level().dimension().identifier().toString() == dimension
        val playerRegionX = player?.let { Math.floorDiv(it.blockX, REGION) } ?: 0
        val playerRegionZ = player?.let { Math.floorDiv(it.blockZ, REGION) } ?: 0
        fun isNear(regionX: Int, regionZ: Int) = near && Math.abs(regionX - playerRegionX) <= 1 && Math.abs(regionZ - playerRegionZ) <= 1

        val seaFloor = Config.seaFloorHires && !SeaFloor.hasCeiling(dimension)
        val useIndex = layout.lodCount > 1 &&
            ((maxX - minX) / layout.tileSize + 1) * ((maxZ - minZ) / layout.tileSize + 1) > TileIndex.ABOVE
        val context = Pass(session, map, layout, scale, files, useIndex, seaFloor, now, ::isNear, minX, maxX, minZ, maxZ, centreX, centreZ)

        // Nearest first, within a slice of each tick, as Xaero spreads its masks over frames.
        val started = System.nanoTime()
        var waiting = 0
        for (square in wanted) {
            if (square in building) continue
            val held = squares[square]
            val nearYou = (square.x * group..square.x * group + group - 1).any { rx ->
                (square.z * group..square.z * group + group - 1).any { rz -> isNear(rx, rz) }
            }
            val due = held == null || held.scale != scale ||
                (!held.complete && now - held.checkedAt > INCOMPLETE_MS) ||
                now - held.decidedAt > (if (nearYou) RECHECK_MS else FAR_RECHECK_MS)
            if (!due) {
                if (!held!!.complete) waiting++
                continue
            }
            if (System.nanoTime() - started > SLICE_NS) { waiting++; continue }
            decide(api, square, held ?: State().also { squares[square] = it }, context, folder, nearYou)
            if (squares[square]?.complete != true) waiting++
        }
        say(if (waiting > 0) "filling in $waiting of ${wanted.size} squares" else "")
        if (mapped.size > MAX_REMEMBERED) mapped.entries.removeIf { now - it.value.at > FORGET_MAPPED_MS }
    }

    /** What every square on screen this tick is decided against. */
    private class Pass(
        val session: Session, val map: String, val layout: BlueMap.MapLayout, val scale: Int,
        val files: Listing, val useIndex: Boolean, val seaFloor: Boolean, val now: Long,
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

    /**
     * Works out which cells of [square] show BlueMap and starts its picture if that differs from
     * what is up. A cell is a chunk close up, and a pixel of the picture once a pixel covers a
     * chunk or more, so a far-out square costs what its picture does, not what its chunks do.
     */
    private fun decide(api: IClientAPI, square: Square, held: State, context: Pass, folder: Folder, nearYou: Boolean) {
        val session = context.session
        val layout = context.layout
        val size = layout.tileSize
        val scale = context.scale
        val now = context.now
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
            if (context.useIndex) {
                when (TileIndex.says(session, layout, context.map, tileX, tileZ)) {
                    null -> { complete = false; continue }
                    false -> continue
                    true -> {}
                }
            }
            val tile = session.tiles.get(TileStore.Key(context.map, 1, tileX, tileZ), 1, size, mayCheck = scale <= CHECK_UP_TO_FACTOR)
            val slot = z * across + x
            when (tile.state) {
                TileStore.State.READY -> {
                    tiles[slot] = tile
                    mix(tile.contentMinute.toLong())
                    mix(System.identityHashCode(tile.floor).toLong())
                    if (context.seaFloor) {
                        session.seaFloor.want(tile, context.map, context.centreX, context.centreZ, context.minX, context.maxX, context.minZ, context.maxZ,
                            closeUp = scale <= SeaFloor.CLOSE_UP)
                    }
                }
                TileStore.State.LOADING -> {
                    complete = false
                    if (layout.lodCount > 1) {
                        val index = TileIndex.over(session, layout, context.map, tileX, tileZ)
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

        // What JourneyMap has, region by region.
        val group = square.group
        val regions = arrayOfNulls<BitSet>(group * group)
        for (z in 0 until group) for (x in 0 until group) {
            val regionX = square.x * group + x
            val regionZ = square.z * group + z
            val chunks = mappedIn(Region(folder, regionX, regionZ), context, context.isNear(regionX, regionZ))
            if (chunks == null) complete = false
            regions[z * group + x] = chunks
            mix(System.identityHashCode(chunks).toLong())
        }

        // Nothing changed since it was last decided, and not due for the date rules: it stands.
        val timeDue = now - held.decidedAt > (if (nearYou) RECHECK_MS else FAR_RECHECK_MS)
        if (held.made != null && held.scale == scale && held.fingerprint == fingerprint && !timeDue) {
            held.complete = complete
            return
        }
        held.fingerprint = fingerprint
        held.scale = scale
        held.decidedAt = now
        held.complete = complete

        val sources = Sources(firstX, firstZ, across, down, tiles, standIn)
        val byPixel = scale >= 16
        val side = (square.span + scale - 1) / scale
        val cells = if (byPixel) side else square.span / 16
        val wanted = BitSet(cells * cells)
        val nowMinute = Clock.nowMinutes()
        val staleMinutes = Config.staleMinutes
        val coverBefore = session.server.coverBefore[square.dimension]?.let(Clock::minutesOf) ?: 0
        val firstChunkX = square.westX shr 4
        val firstChunkZ = square.northZ shr 4
        for (cz in 0 until cells) for (cx in 0 until cells) {
            val chunkX = if (byPixel) (square.westX + cx * scale) shr 4 else firstChunkX + cx
            val chunkZ = if (byPixel) (square.northZ + cz * scale) shr 4 else firstChunkZ + cz
            val middleX = (chunkX shl 4) + 8
            val middleZ = (chunkZ shl 4) + 8
            val slot = sources.at(middleX, middleZ, size)
            if (slot < 0) continue
            val tile = tiles[slot] ?: continue
            // BlueMap must have something at the middle of the chunk, or it would replace your map with nothing.
            if (!hasDataAt(tile, middleX, middleZ)) continue
            val regionX = Math.floorDiv(chunkX, 32) - square.x * group
            val regionZ = Math.floorDiv(chunkZ, 32) - square.z * group
            if (regionX !in 0 until group || regionZ !in 0 until group) continue
            val chunks = regions[regionZ * group + regionX] ?: continue // not known yet: JourneyMap's map is left showing
            val index = cz * cells + cx
            if (!chunks[Math.floorMod(chunkZ, 32) * 32 + Math.floorMod(chunkX, 32)]) { wanted.set(index); continue }
            // A blurry stand-in only where JourneyMap has nothing at all.
            if (standIn[slot]) continue
            val seen = session.visits.lastSeen(square.dimension, chunkX, chunkZ)
            if (coverBefore != 0 && seen < coverBefore) { wanted.set(index); continue }
            // JourneyMap keeps no dates of its own, so ground never seen since the mod was installed counts as current.
            if (seen != 0 && nowMinute - seen >= staleMinutes && tile.contentMinute > seen) wanted.set(index)
        }

        val old = held.made
        if (wanted.isEmpty) {
            held.made = Made(wanted, fingerprint, scale)
            held.picture?.let { remove(api, square, it) }
            held.picture = null
            return
        }
        if (old != null && held.picture != null && old.scale == scale && old.wanted == wanted && old.fingerprint == fingerprint) return
        val made = Made(wanted, fingerprint, scale)
        val generation = generation
        building.add(square)
        try {
            builder.execute { build(api, session, square, sources, size, scale, generation, made, byPixel) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            building.remove(square)
        }
    }

    /** Whether [tile] (finest or coarsest) has anything at block ([x], [z]). */
    private fun hasDataAt(tile: TileStore.Tile, x: Int, z: Int): Boolean {
        val per = tile.blocksPerPixel
        val reach = tile.tileSize * per
        val pixelX = Math.floorDiv(x - tile.key.x * reach, per).coerceIn(0, tile.tileSize - 1)
        val pixelZ = Math.floorDiv(z - tile.key.z * reach, per).coerceIn(0, tile.tileSize - 1)
        return tile.hasDataAt(pixelX, pixelZ)
    }

    /**
     * What JourneyMap has mapped in [region], by chunk; null while that is not known yet (it is
     * asked). Where it has no picture on disk and you are not close by, nothing, without asking;
     * a region it was seen to have something in keeps that until its file says otherwise.
     */
    private fun mappedIn(region: Region, context: Pass, near: Boolean): BitSet? {
        val known = mapped[region]
        if (near) {
            if (known != null && context.now - known.at < RECHECK_MS) return known.chunks
            ask(region, context, 0L)
            return known?.chunks
        }
        val date = context.files.dates[regionKey(region.x, region.z)]
        if (date == null) return if (known != null && !known.chunks.isEmpty) known.chunks else NOTHING
        if (known != null && known.stamp == date) return known.chunks
        ask(region, context, date)
        return known?.chunks
    }

    /** Asks JourneyMap what it has of [region], a few regions at a time. */
    private fun ask(region: Region, context: Pass, stamp: Long) {
        if (region in asking || asking.size >= MAX_ASKING) return
        val generation = generation
        val folder = region.folder
        asking.add(region)
        try {
            // JourneyMap's own picture of the same map it is showing: day, or the cave slice on screen.
            api?.requestMapTile(
                JourneyMapAdapter.MOD_ID, JourneyMapAdapter.levelKey(folder.dimension), folder.type,
                ChunkPos(region.x * CHUNKS, region.z * CHUNKS), ChunkPos(region.x * CHUNKS + CHUNKS - 1, region.z * CHUNKS + CHUNKS - 1),
                folder.slice, 0, false,
            ) { image ->
                // Called back on the client thread.
                val chunks = BitSet(CHUNKS * CHUNKS)
                image?.use { readMapped(it, chunks) }
                if (generation != this.generation) return@requestMapTile
                asking.remove(region)
                mapped[region] = Mapped(chunks, System.currentTimeMillis(), stamp)
            } ?: asking.remove(region)
        } catch (e: Exception) {
            asking.remove(region)
            // Taken as mapped all over, so BlueMap never covers what JourneyMap could not hand over.
            mapped[region] = Mapped(BitSet(CHUNKS * CHUNKS).apply { set(0, CHUNKS * CHUNKS) }, System.currentTimeMillis(), stamp)
            Log.warn("JourneyMap would not hand over its map of region {}, {}: {}", region.x, region.z, e.toString())
        }
    }

    /** JourneyMap's API, for the questions above; set every tick. */
    private var api: IClientAPI? = null

    /** JourneyMap's picture is see-through where it has nothing; a chunk counts as mapped if its middle is not. */
    private fun readMapped(image: NativeImage, into: BitSet) {
        val width = image.width
        val height = image.height
        if (width < CHUNKS || height < CHUNKS) return
        for (chunkZ in 0 until CHUNKS) for (chunkX in 0 until CHUNKS) {
            val x = (chunkX * width + width / 2) / CHUNKS
            val y = (chunkZ * height + height / 2) / CHUNKS
            if (image.getPixel(x, y) ushr 24 != 0) into.set(chunkZ * CHUNKS + chunkX)
        }
    }

    /**
     * [folder]'s region pictures on disk, listed again every [LISTING_MS] off the client thread;
     * null before the first listing is in.
     */
    private fun listingOf(session: Session, folder: Folder, now: Long): Listing? {
        val known = listings[folder]
        if ((known == null || now - known.at > LISTING_MS) && folder !in listing) {
            val path = JourneyMapFiles.dimensionFolder(folder.dimension)?.resolve(
                if (folder.type == Context.MapType.Underground) (folder.slice ?: 0).toString() else "day",
            )
            if (path == null) {
                listings[folder] = Listing(emptyMap(), now)
            } else {
                listing.add(folder)
                val generation = generation
                session.work {
                    val dates = list(path)
                    Minecraft.getInstance().execute {
                        listing.remove(folder)
                        if (generation == this.generation) listings[folder] = Listing(dates, System.currentTimeMillis())
                    }
                }
            }
        }
        return listings[folder]
    }

    /** `x,z.png` files in [folder] and their dates. Off the client thread. */
    private fun list(folder: Path): Map<Long, Long> {
        val dates = HashMap<Long, Long>()
        if (!Files.isDirectory(folder)) return dates
        try {
            Files.newDirectoryStream(folder, "*.png").use { stream ->
                for (file in stream) {
                    val match = REGION_FILE.matchEntire(file.fileName.toString()) ?: continue
                    val x = match.groupValues[1].toIntOrNull() ?: continue
                    val z = match.groupValues[2].toIntOrNull() ?: continue
                    dates[regionKey(x, z)] = try { Files.getLastModifiedTime(file).toMillis() } catch (e: Exception) { 1L }
                }
            }
        } catch (e: Exception) {
            Log.warn("Could not list JourneyMap's map folder {}: {}", folder.fileName, e.toString())
        }
        return dates
    }

    private fun regionKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    /** Leaving a world: JourneyMap drops our overlays ([JourneyMapAdapter.leave]); this frees the pictures. */
    fun forget() {
        generation++
        squares.values.forEach { it.picture?.release() }
        squares.clear()
        building.clear()
        mapped.clear()
        asking.clear()
        listings.clear()
        listing.clear()
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

    /** Off the client thread: the square's picture, BlueMap in the wanted cells, see-through elsewhere. */
    private fun build(
        api: IClientAPI, session: Session, square: Square, sources: Sources, size: Int, scale: Int, generation: Int, made: Made, byPixel: Boolean,
    ) {
        val wanted = made.wanted
        // Rounded up: a tile size's factor need not divide the square; the last pixel runs a little past it.
        val side = (square.span + scale - 1) / scale
        val cells = if (byPixel) side else square.span / 16
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
        Minecraft.getInstance().execute { show(api, square, image, scale, generation, made) }
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
        val picture = RegionPicture.show(api, "terrain", square.dimension, square.westX, square.northZ, scale, image, fullscreen, TERRAIN_ORDER)
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
        val full = squares.count { (square, held) -> square.ui == Context.UI.Fullscreen && held.picture != null }
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

    private val NOTHING = BitSet(0)
    private val REGION_FILE = Regex("""(-?\d+),(-?\d+)\.png""")

    private const val CHUNKS = REGION / 16
    private const val MAX_GROUP = 32
    /** Squares drawn at most, nearest first, as Xaero draws at most 1024 tiles. */
    private const val MAX_SQUARES = 1024
    /** Questions to JourneyMap waiting at once. */
    private const val MAX_ASKING = 8
    /** Client time spent deciding squares a tick at most. */
    private const val SLICE_NS = 4_000_000L
    /** Tiles are checked with the server only this close in, as on Xaero's map. */
    private const val CHECK_UP_TO_FACTOR = 4

    /** How often a square is decided again: around you, where JourneyMap is mapping, and further off, where only dates move on. */
    private const val RECHECK_MS = 5_000L
    private const val FAR_RECHECK_MS = 30_000L
    /** How often a square still waiting for tiles or answers is looked at again. */
    private const val INCOMPLETE_MS = 300L
    private const val LISTING_MS = 5_000L
    private const val KEEP_CLOSED_MS = 30_000L
    private const val MAX_REMEMBERED = 20_000
    private const val FORGET_MAPPED_MS = 10 * 60_000L
    private const val OPAQUE = 0xFF000000.toInt()

    /** Under the outlines and pins ([JourneyMapPins]). */
    private const val TERRAIN_ORDER = 50
}
