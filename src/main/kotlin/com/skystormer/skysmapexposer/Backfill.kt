package com.skystormer.skysmapexposer

import com.mojang.blaze3d.textures.GpuTextureView
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import xaero.map.MapProcessor
import xaero.map.world.MapDimension
import java.nio.file.Path
import kotlin.math.floor

/**
 * Decides which chunks of an area should show BlueMap, for the world map and the minimap alike.
 *
 * A chunk is backfilled when BlueMap has something there and any of these holds:
 *  - Xaero has nothing there: no region file, or a region with a gap in it;
 *  - it was mapped before the dimension's [Config.Server.coverBefore] time — a map left over from
 *    a previous season;
 *  - what you mapped there is older than [Config.staleDays], and BlueMap's picture is newer than
 *    your visit.
 *
 * The decision is always per chunk, at every zoom, so the backfilled area keeps its shape; only
 * the picture's resolution changes, the same way Xaero's own does.
 *
 * Xaero's map pipelines overwrite colour rather than blending it, so a tile cannot simply be drawn
 * with the areas to keep made transparent. Instead each tile gets a [Mask]: the chunks that
 * qualify, merged along each row into rectangles, and only those are drawn.
 */
object Backfill {

    /**
     * Rectangles to draw for one tile, as `minX, minZ, maxX, maxZ` in block coordinates, four
     * floats per rectangle.
     */
    class Mask(val builtAt: Long, val contentMinute: Int, val rectangles: FloatArray, val count: Int, val xaeroGaps: Int)

    /** One tile's worth of drawing: its texture, the rectangles to draw, and where the tile starts. */
    class Piece(val texture: GpuTextureView, val mask: Mask, val originX: Int, val originZ: Int, val tileSize: Int)

    /** What [plan] found, for the status line. */
    class Plan(val pieces: List<Piece>, val summary: String)

    /** What there is to draw for one dimension on one server: null with a reason if nothing. */
    class Target(val session: Session, val dimensionId: String, val mapId: String, val layout: BlueMap.MapLayout, val regionFolder: Path)

    private var frame = 0L

    private class Candidate(val x: Int, val z: Int, val distance: Double)

    /** Frame counter shared by everything that touches tiles, so eviction knows what is in use. */
    fun nextFrame(): Long = ++frame

    /**
     * Works out whether anything can be drawn for Xaero's current map, or says why not (through
     * [standDown], which logs each reason once).
     */
    fun target(mapProcessor: MapProcessor, standDown: (String, String) -> Unit): Target? {
        if (!Config.enabled) return standDown("off", "Backfill is switched off").let { null }
        val session = Session.current ?: return standDown("no-session", "This server has no BlueMap set up").let { null }
        if (mapProcessor.currentCaveLayer != Int.MAX_VALUE) {
            return standDown("cave", "Xaero is in cave mode; BlueMap only has the surface").let { null }
        }
        val dimension: MapDimension = mapProcessor.mapWorld?.currentDimension
            ?: return standDown("no-dimension", "Xaero has no dimension open").let { null }
        val dimensionId = dimension.dimId?.identifier()?.toString()
            ?: return standDown("no-dimension-id", "Xaero's dimension has no id").let { null }
        val mapId = session.server.mapFor(dimensionId)
            ?: return standDown("no-map:$dimensionId", "BlueMap is not used for $dimensionId").let { null }
        val layout = session.layout(mapId)
            ?: return standDown("layout:$mapId", "Waiting for BlueMap's settings for map '$mapId'").let { null }
        val multiworld = dimension.currentMultiworld
            ?: return standDown("no-folder", "Xaero has not chosen a map folder for this dimension yet").let { null }
        val regionFolder = dimension.mainFolderPath?.resolve(multiworld)
            ?: return standDown("no-folder", "Xaero has not chosen a map folder for this dimension yet").let { null }
        return Target(session, dimensionId, mapId, layout, regionFolder)
    }

    /**
     * Everything to draw inside the block area [minX]..[maxX], [minZ]..[maxZ] at [factor] blocks
     * per texture pixel, nearest to ([centreX], [centreZ]) first. Returns null (after saying why)
     * when the area is too large to cover.
     */
    fun plan(
        target: Target,
        mapProcessor: MapProcessor,
        centreX: Double,
        centreZ: Double,
        minX: Double,
        maxX: Double,
        minZ: Double,
        maxZ: Double,
        factor: Int,
        budget: Budget,
    ): Plan? {
        val session = target.session
        val layout = target.layout
        val tileSize = layout.tileSize
        val tilesX = floor(minX / tileSize).toInt()..floor(maxX / tileSize).toInt()
        val tilesZ = floor(minZ / tileSize).toInt()..floor(maxZ / tileSize).toInt()
        val inView = tilesX.count().toLong() * tilesZ.count()
        if (inView > MAX_TILES_SCANNED) return null

        // Zoomed out, ask BlueMap's coarsest tiles where there is anything before asking for the
        // fine ones: most of a zoomed-out screen is usually beyond the world border.
        val candidates = ArrayList<Candidate>()
        var waitingForIndex = 0
        for (tileZ in tilesZ) {
            for (tileX in tilesX) {
                if (inView > INDEX_ABOVE && layout.lodCount > 1) {
                    when (indexSays(session, layout, target.mapId, tileX, tileZ)) {
                        null -> { waitingForIndex++; continue }
                        false -> continue
                        true -> {}
                    }
                }
                val dx = (tileX + 0.5) * tileSize - centreX
                val dz = (tileZ + 0.5) * tileSize - centreZ
                candidates.add(Candidate(tileX, tileZ, dx * dx + dz * dz))
            }
        }
        candidates.sortBy { it.distance }
        if (candidates.size > MAX_TILES_DRAWN) candidates.subList(MAX_TILES_DRAWN, candidates.size).clear()

        val now = Clock.nowMinutes()
        val pieces = ArrayList<Piece>()
        var ready = 0
        var waiting = 0
        var empty = 0
        var failed = 0
        var gaps = 0
        for (candidate in candidates) {
            val tile = session.tiles.get(TileStore.Key(target.mapId, 1, candidate.x, candidate.z), 1, tileSize, frame)
            when (tile.state) {
                TileStore.State.LOADING -> { waiting++; continue }
                TileStore.State.EMPTY -> { empty++; continue }
                TileStore.State.FAILED -> { failed++; continue }
                TileStore.State.READY -> ready++
            }
            session.tiles.requestFactor(tile, factor)

            val originX = candidate.x * tileSize
            val originZ = candidate.z * tileSize
            var mask = tile.mask
            val maskStale = mask == null || mask.contentMinute != tile.contentMinute ||
                System.currentTimeMillis() - mask.builtAt > MASK_LIFETIME_MS
            if (maskStale && budget.masks > 0) {
                budget.masks--
                mask = buildMask(tile, originX, originZ, target, mapProcessor, now)
                tile.mask = mask
            }
            if (mask == null) continue
            gaps += mask.xaeroGaps
            if (mask.count == 0) continue

            val mayUpload = session.tiles.hasUploadWaiting(tile, factor) && budget.uploads > 0
            if (mayUpload) budget.uploads--
            val texture = session.tiles.textureOf(tile, factor, mayUpload) ?: continue
            pieces.add(Piece(texture, mask, originX, originZ, tileSize))
        }
        session.tiles.trim(maxOf(Config.maxLoadedTiles, candidates.size * 2 + 16), frame)

        val summary = "${target.dimensionId} → '${target.mapId}' at 1:$factor: ${candidates.size} tiles with data in view " +
            "($waitingForIndex awaiting the index), $ready ready, $waiting loading, $empty empty, $failed failed; " +
            "${pieces.sumOf { it.mask.count }} rectangles from ${pieces.size} tiles; $gaps chunks are gaps in Xaero's map"
        return Plan(pieces, summary)
    }

    /** Uploads and mask builds allowed per frame, shared by the world map and the minimap. */
    class Budget(var uploads: Int = 8, var masks: Int = 8)

    /** Divisors of the tile size, so a shrunk tile is still a whole number of pixels across. */
    fun factorFor(tileSize: Int, blocksPerPixel: Float): Int =
        listOf(1, 2, 4, 5, 8, 10, 16, 20, 25, 32, 50, 100)
            .filter { tileSize % it == 0 }
            .lastOrNull { it <= blocksPerPixel.coerceAtLeast(1f) } ?: 1

    /**
     * Whether BlueMap's coarsest tile says the finest tile ([tileX], [tileZ]) has anything in it:
     * null while that is not known yet.
     */
    private fun indexSays(session: Session, layout: BlueMap.MapLayout, mapId: String, tileX: Int, tileZ: Int): Boolean? {
        val lod = layout.lodCount
        val ratio = layout.tileBlocks(lod) / layout.tileSize
        val indexX = Math.floorDiv(tileX, ratio)
        val indexZ = Math.floorDiv(tileZ, ratio)
        val index = session.tiles.get(TileStore.Key(mapId, lod, indexX, indexZ), layout.blocksPerPixel(lod), layout.tileSize, frame)
        return when (index.state) {
            TileStore.State.LOADING -> null
            TileStore.State.EMPTY -> false
            TileStore.State.FAILED -> true // no index to go by: ask for the fine tile itself
            TileStore.State.READY -> {
                val localX = tileX - indexX * ratio
                val localZ = tileZ - indexZ * ratio
                val known = index.finerPresence ?: ByteArray(ratio * ratio).also { index.finerPresence = it }
                val slot = localZ * ratio + localX
                if (known[slot] == UNKNOWN) {
                    val span = layout.tileSize / ratio
                    var any = false
                    search@ for (z in localZ * span until (localZ + 1) * span) {
                        for (x in localX * span until (localX + 1) * span) {
                            if (index.hasDataAt(x, z)) {
                                any = true
                                break@search
                            }
                        }
                    }
                    known[slot] = if (any) SOMETHING else NOTHING
                }
                known[slot] == SOMETHING
            }
        }
    }

    private fun buildMask(
        tile: TileStore.Tile,
        originX: Int,
        originZ: Int,
        target: Target,
        mapProcessor: MapProcessor,
        now: Int,
    ): Mask {
        val session = target.session
        val dimensionId = target.dimensionId
        val tileSize = tile.tileSize
        val staleMinutes = Config.staleMinutes
        val regionsKnown = session.regionAges.isKnown(target.regionFolder)
        val remembered = session.xaeroGapsIn(dimensionId)
        val coverBefore = session.server.coverBefore[dimensionId]?.let(Clock::minutesOf) ?: 0
        var gapCount = 0

        fun chunkWanted(chunkX: Int, chunkZ: Int): Boolean {
            // BlueMap must have something to show at the middle of the chunk, or drawing it would
            // replace your map with nothing.
            val pixelX = ((chunkX shl 4) + 8 - originX).coerceIn(0, tileSize - 1)
            val pixelZ = ((chunkZ shl 4) + 8 - originZ).coerceIn(0, tileSize - 1)
            if (!tile.hasDataAt(pixelX, pixelZ)) return false

            if (isXaeroGap(mapProcessor, remembered, chunkX, chunkZ)) {
                gapCount++
                return true
            }
            var seen = session.visits.lastSeen(dimensionId, chunkX, chunkZ)
            // A map from before the cover date is covered however recent Xaero's files look:
            // Xaero rewrites a whole region file when any chunk in it changes.
            if (coverBefore != 0 && seen < coverBefore) return true
            if (seen == 0) {
                // Not seen since the mod was installed: date it by Xaero's region file instead.
                val regionMinute = session.regionAges.minuteOf(target.regionFolder, chunkX shr 5, chunkZ shr 5)
                if (!regionsKnown) return false // the folder listing is on its way; draw nothing yet
                if (regionMinute == 0) return true // Xaero has never mapped this region
                seen = regionMinute
            }
            if (now - seen < staleMinutes) return false
            return tile.contentMinute > seen
        }

        val firstChunkX = Math.floorDiv(originX, 16)
        val lastChunkX = Math.floorDiv(originX + tileSize - 1, 16)
        val firstChunkZ = Math.floorDiv(originZ, 16)
        val lastChunkZ = Math.floorDiv(originZ + tileSize - 1, 16)
        val maxX = originX + tileSize
        val maxZ = originZ + tileSize

        var rectangles = FloatArray(64)
        var count = 0
        for (chunkZ in firstChunkZ..lastChunkZ) {
            val top = maxOf(chunkZ shl 4, originZ).toFloat()
            val bottom = minOf((chunkZ + 1) shl 4, maxZ).toFloat()
            var runStart = Int.MIN_VALUE
            for (chunkX in firstChunkX..lastChunkX + 1) {
                val wanted = chunkX <= lastChunkX && chunkWanted(chunkX, chunkZ)
                if (wanted && runStart == Int.MIN_VALUE) runStart = chunkX
                if (!wanted && runStart != Int.MIN_VALUE) {
                    if ((count + 1) * 4 > rectangles.size) rectangles = rectangles.copyOf(rectangles.size * 2)
                    rectangles[count * 4] = maxOf(runStart shl 4, originX).toFloat()
                    rectangles[count * 4 + 1] = top
                    rectangles[count * 4 + 2] = minOf(chunkX shl 4, maxX).toFloat()
                    rectangles[count * 4 + 3] = bottom
                    count++
                    runStart = Int.MIN_VALUE
                }
            }
        }
        return Mask(System.currentTimeMillis(), tile.contentMinute, rectangles, count, gapCount)
    }

    /**
     * Whether Xaero has nothing for this chunk.
     *
     * While Xaero has the chunk's region open this is exact: Xaero keeps a height for every pixel
     * it has mapped, and reports 32767 for one it has not. The answer is remembered, so zooming
     * out — when Xaero closes its regions and draws from a smaller copy — does not change it. It
     * is only ever changed by asking Xaero again, here or in [learnGapsAround]: a chunk being
     * loaded is no proof, because Xaero leaves the outermost ring of loaded chunks unmapped until
     * the chunks beyond them arrive.
     */
    private fun isXaeroGap(mapProcessor: MapProcessor, remembered: LongOpenHashSet, chunkX: Int, chunkZ: Int): Boolean {
        val key = Session.chunkKey(chunkX, chunkZ)
        val live = xaeroHasNothing(mapProcessor, chunkX, chunkZ) ?: return remembered.contains(key)
        if (live) remembered.add(key) else remembered.remove(key)
        return live
    }

    /**
     * Asks Xaero about every chunk within [radius] chunks of ([centreChunkX], [centreChunkZ]) and
     * remembers the answers. Xaero always has the regions around the player open for the minimap,
     * so this is where it can say which chunks it has really mapped, including the ring at the
     * edge of render distance that it has not.
     */
    fun learnGapsAround(mapProcessor: MapProcessor, remembered: LongOpenHashSet, centreChunkX: Int, centreChunkZ: Int, radius: Int) {
        for (chunkX in centreChunkX - radius..centreChunkX + radius) {
            for (chunkZ in centreChunkZ - radius..centreChunkZ + radius) {
                val live = xaeroHasNothing(mapProcessor, chunkX, chunkZ) ?: continue
                val key = Session.chunkKey(chunkX, chunkZ)
                if (live) remembered.add(key) else remembered.remove(key)
            }
        }
    }

    private fun xaeroHasNothing(mapProcessor: MapProcessor, chunkX: Int, chunkZ: Int): Boolean? {
        return try {
            val region = mapProcessor.getLeafMapRegion(Int.MAX_VALUE, chunkX shr 5, chunkZ shr 5, false) ?: return null
            if (!region.isLoaded) return null
            val tileChunk = region.getChunk((chunkX shr 2) and 7, (chunkZ shr 2) and 7) ?: return true
            val texture = tileChunk.leafTexture ?: return null
            texture.getHeight(((chunkX and 3) shl 4) + 8, ((chunkZ and 3) shl 4) + 8) == NO_HEIGHT
        } catch (e: RuntimeException) {
            // Xaero's worker threads may be rebuilding the region under us; ask again next time.
            null
        }
    }

    private const val NO_HEIGHT = 32767
    private const val UNKNOWN: Byte = 0
    private const val NOTHING: Byte = 1
    private const val SOMETHING: Byte = 2
    private const val MAX_TILES_SCANNED = 40_000L
    private const val INDEX_ABOVE = 16L
    private const val MAX_TILES_DRAWN = 1024
    private const val MASK_LIFETIME_MS = 5_000L
}
