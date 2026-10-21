package com.skystormer.skysmapexposer

import com.mojang.renderpearl.api.textures.GpuTextureView
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
 * While a tile is on its way, a coarser one BlueMap has already sent stands in for it, blurry,
 * wherever Xaero has nothing at all ([standIn]); one coarse tile covers many fine ones, so it
 * arrives long before they do.
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

    /**
     * What [plan] found: what to draw, the origins of the tiles still on their way (`x, z` in
     * blocks, two to a tile) for a loading sign, and a line for the status screen.
     */
    class Plan(val pieces: List<Piece>, val loading: IntArray, val summary: String)

    /** What there is to draw for one dimension on one server: null with a reason if nothing. */
    class Target(
        val session: Session, val dimensionId: String, val mapId: String, val layout: BlueMap.MapLayout, val regionFolder: Path,
        /** Xaero's layer drawn over: the surface, or the Nether's cave layer ([XaeroWriter.blueMapLayer]). */
        val layer: Int,
    )

    private class Candidate(val x: Int, val z: Int, val distance: Double)

    /**
     * Works out whether anything can be drawn for Xaero's current map, or says why not (through
     * [standDown], which logs each reason once).
     */
    fun target(mapProcessor: MapProcessor, standDown: (String, String) -> Unit): Target? {
        if (!Config.enabled) return standDown("off", "Backfill is switched off").let { null }
        val session = Session.current ?: return standDown("no-session", "This server has no BlueMap set up").let { null }
        val dimension: MapDimension = mapProcessor.mapWorld?.currentDimension
            ?: return standDown("no-dimension", "Xaero has no dimension open").let { null }
        val dimensionId = dimension.dimId?.identifier()?.toString()
            ?: return standDown("no-dimension-id", "Xaero's dimension has no id").let { null }
        val mapId = session.server.mapFor(dimensionId)
            ?: return standDown("no-map:$dimensionId", "BlueMap is not used for $dimensionId").let { null }
        // In the Nether, BlueMap's map of the roof goes over Xaero's surface, and a map of the inside over its cave layer.
        val layer = XaeroWriter.blueMapLayer(dimensionId, mapId, mapProcessor.currentCaveLayer)
            ?: return caveReason(dimensionId, mapId).let { (key, why) -> standDown(key, why) }.let { null }
        val layout = session.layout(mapId)
            ?: return standDown("layout:$mapId", "Waiting for BlueMap's settings for map '$mapId'").let { null }
        val multiworld = dimension.currentMultiworld
            ?: return standDown("no-folder", "Xaero has not chosen a map folder for this dimension yet").let { null }
        val base = dimension.mainFolderPath?.resolve(multiworld)
            ?: return standDown("no-folder", "Xaero has not chosen a map folder for this dimension yet").let { null }
        return Target(session, dimensionId, mapId, layout, XaeroWriter.layerFolder(base, layer), layer)
    }

    /** Why nothing is drawn over the layer Xaero shows, for [target]'s stand-down: its key and the reason. */
    private fun caveReason(dimensionId: String, mapId: String): Pair<String, String> =
        if (!XaeroWriter.hasCeiling(dimensionId)) "cave" to "Xaero is in cave mode; BlueMap only has the surface"
        else when (XaeroWriter.viewOf(mapId)) {
            null -> "view:$mapId" to "Checking whether BlueMap map '$mapId' shows the roof or the inside"
            MapLayers.View.ROOF -> "roof:$mapId" to "BlueMap map '$mapId' shows the roof, and Xaero is showing the inside"
            MapLayers.View.INSIDE -> "inside:$mapId" to "BlueMap map '$mapId' shows the inside, and Xaero is showing the roof"
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
        wantedFactor: Int,
        budget: Budget,
    ): Plan? {
        val session = target.session
        val layout = target.layout
        val tileSize = layout.tileSize
        // Over the memory budget with what is on screen alone: coarser pictures, not fewer.
        val factor = coarser(tileSize, wantedFactor, session.tiles.coarser)
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
        val loading = ArrayList<Int>()
        var standIns = 0
        var ready = 0
        var waiting = 0
        var empty = 0
        var failed = 0
        var gaps = 0
        for (candidate in candidates) {
            val tile = session.tiles.get(TileStore.Key(target.mapId, 1, candidate.x, candidate.z), 1, tileSize, mayCheck = factor <= CHECK_UP_TO_FACTOR)
            val originX = candidate.x * tileSize
            val originZ = candidate.z * tileSize
            fun standInFor() {
                if (standIn(tile, originX, originZ, target, mapProcessor, budget, pieces, loading)) standIns++
            }
            when (tile.state) {
                TileStore.State.LOADING -> { waiting++; standInFor(); continue }
                TileStore.State.EMPTY -> { empty++; continue }
                TileStore.State.FAILED -> { failed++; continue }
                TileStore.State.READY -> ready++
            }
            session.tiles.requestFactor(tile, factor)

            var mask = tile.mask
            val maskStale = mask == null || mask.contentMinute != tile.contentMinute ||
                System.currentTimeMillis() - mask.builtAt > MASK_LIFETIME_MS
            if (maskStale && budget.masks > 0) {
                budget.masks--
                mask = buildMask(tile, originX, originZ, target, mapProcessor, now)
                tile.mask = mask
            }
            if (mask == null) { standInFor(); continue }
            gaps += mask.xaeroGaps
            if (mask.count == 0) continue

            val mayUpload = session.tiles.hasUploadWaiting(tile, factor) && budget.uploads > 0
            if (mayUpload) budget.uploads--
            val texture = session.tiles.textureOf(tile, factor, mayUpload)
            if (texture == null) { standInFor(); continue }
            tile.standIn = null
            pieces.add(Piece(texture, mask, originX, originZ, tileSize))
        }
        session.tiles.trim(FramePacer.game.budgetBytes())

        val summary = "${target.dimensionId} → '${target.mapId}' at 1:$factor: ${candidates.size} tiles with data in view " +
            "($waitingForIndex awaiting the index), $ready ready, $waiting loading, $empty empty, $failed failed; " +
            "${pieces.sumOf { it.mask.count }} rectangles from ${pieces.size} tiles ($standIns standing in); $gaps chunks are gaps in Xaero's map"
        return Plan(pieces, loading.toIntArray(), summary)
    }

    /**
     * A coarser tile standing in for [fine], which is not ready to draw: the finest level BlueMap
     * has already sent, drawn only where Xaero has nothing at all, so a map you have explored is
     * never covered by a blurry picture. Adds the piece to [pieces] and, while BlueMap may still
     * have something here, the tile's origin to [loading]. True when something stands in.
     */
    private fun standIn(
        fine: TileStore.Tile, originX: Int, originZ: Int, target: Target, mapProcessor: MapProcessor,
        budget: Budget, pieces: MutableList<Piece>, loading: MutableList<Int>,
    ): Boolean {
        val session = target.session
        val layout = target.layout
        for (lod in 2..layout.lodCount) {
            val span = layout.tileBlocks(lod)
            val coarseX = Math.floorDiv(originX, span)
            val coarseZ = Math.floorDiv(originZ, span)
            val coarse = session.tiles.get(TileStore.Key(target.mapId, lod, coarseX, coarseZ), layout.blocksPerPixel(lod), layout.tileSize)
            when (coarse.state) {
                TileStore.State.LOADING, TileStore.State.FAILED -> continue
                TileStore.State.EMPTY -> return false // BlueMap has nothing here at all
                TileStore.State.READY -> {}
            }
            session.tiles.requestFactor(coarse, 1)
            var mask = fine.standIn
            val stale = mask == null || fine.standInLod != lod || System.currentTimeMillis() - mask.builtAt > MASK_LIFETIME_MS
            if (stale && budget.masks > 0) {
                budget.masks--
                mask = buildStandInMask(coarse, coarseX * span, coarseZ * span, layout.blocksPerPixel(lod), originX, originZ, layout.tileSize, target, mapProcessor)
                fine.standIn = mask
                fine.standInLod = lod
            }
            if (mask == null || fine.standInLod != lod) {
                loading.add(originX)
                loading.add(originZ)
                return false
            }
            if (mask.count == 0) return false // Xaero has all of it: nothing to wait for
            loading.add(originX)
            loading.add(originZ)
            val mayUpload = session.tiles.hasUploadWaiting(coarse, 1) && budget.uploads > 0
            if (mayUpload) budget.uploads--
            val texture = session.tiles.textureOf(coarse, 1, mayUpload) ?: return false
            pieces.add(Piece(texture, mask, coarseX * span, coarseZ * span, span))
            return true
        }
        // Nothing coarser has arrived yet either.
        loading.add(originX)
        loading.add(originZ)
        return false
    }

    /**
     * The chunks of the fine tile at ([originX], [originZ]) where Xaero has nothing at all and the
     * [coarse] tile, starting at ([coarseX], [coarseZ]) with [blocksPerPixel] blocks to a pixel,
     * has something.
     */
    private fun buildStandInMask(
        coarse: TileStore.Tile, coarseX: Int, coarseZ: Int, blocksPerPixel: Int,
        originX: Int, originZ: Int, tileSize: Int, target: Target, mapProcessor: MapProcessor,
    ): Mask {
        val session = target.session
        val regionsKnown = session.regionAges.isKnown(target.regionFolder)
        val remembered = session.xaeroGapsIn(target.dimensionId)
        fun wanted(chunkX: Int, chunkZ: Int): Boolean {
            val pixelX = Math.floorDiv((chunkX shl 4) + 8 - coarseX, blocksPerPixel).coerceIn(0, coarse.tileSize - 1)
            val pixelZ = Math.floorDiv((chunkZ shl 4) + 8 - coarseZ, blocksPerPixel).coerceIn(0, coarse.tileSize - 1)
            if (!coarse.hasDataAt(pixelX, pixelZ)) return false
            if (isXaeroGap(mapProcessor, remembered, chunkX, chunkZ) || unshowable(target, chunkX, chunkZ, regionsKnown)) return true
            return regionsKnown && session.regionAges.minuteOf(target.regionFolder, chunkX shr 5, chunkZ shr 5) == 0
        }
        return rectanglesOf(originX, originZ, tileSize, coarse.contentMinute, 0, ::wanted)
    }

    /**
     * Uploads and mask builds allowed per frame, shared by the world map and the minimap. Uploads
     * are fewer while frames are slow ([FramePacer]).
     */
    class Budget(var uploads: Int = FramePacer.game.uploads, var masks: Int = 8)

    /** Divisors of the tile size, so a shrunk tile is still a whole number of pixels across. */
    fun factorFor(tileSize: Int, blocksPerPixel: Float): Int =
        TilePixels.factorsFor(tileSize).lastOrNull { it <= blocksPerPixel.coerceAtLeast(1f) } ?: 1

    /** [factor], [steps] of the tile size's factors coarser, as far as there are any. */
    fun coarser(tileSize: Int, factor: Int, steps: Int): Int {
        if (steps <= 0) return factor
        val usable = TilePixels.factorsFor(tileSize)
        val at = usable.indexOf(factor).coerceAtLeast(0)
        return usable[minOf(at + steps, usable.lastIndex)]
    }

    /**
     * Whether BlueMap's coarsest tile says the finest tile ([tileX], [tileZ]) has anything in it:
     * null while that is not known yet.
     */
    private fun indexSays(session: Session, layout: BlueMap.MapLayout, mapId: String, tileX: Int, tileZ: Int): Boolean? {
        val lod = layout.lodCount
        val ratio = layout.tileBlocks(lod) / layout.tileSize
        val indexX = Math.floorDiv(tileX, ratio)
        val indexZ = Math.floorDiv(tileZ, ratio)
        val index = session.tiles.get(TileStore.Key(mapId, lod, indexX, indexZ), layout.blocksPerPixel(lod), layout.tileSize)
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

            if (isXaeroGap(mapProcessor, remembered, chunkX, chunkZ) || unshowable(target, chunkX, chunkZ, regionsKnown)) {
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

        val mask = rectanglesOf(originX, originZ, tileSize, tile.contentMinute, 0, ::chunkWanted)
        return Mask(mask.builtAt, mask.contentMinute, mask.rectangles, mask.count, gapCount)
    }

    /** The chunks of the square at ([originX], [originZ]) that are [wanted], merged along each row into rectangles. */
    private fun rectanglesOf(originX: Int, originZ: Int, tileSize: Int, contentMinute: Int, gapCount: Int, wanted: (Int, Int) -> Boolean): Mask {
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
                val wanted = chunkX <= lastChunkX && wanted(chunkX, chunkZ)
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
        return Mask(System.currentTimeMillis(), contentMinute, rectangles, count, gapCount)
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

    /** Whether the chunk's region is one Xaero has a file for but cannot show ([UnshowableRegions]). */
    private fun unshowable(target: Target, chunkX: Int, chunkZ: Int, regionsKnown: Boolean): Boolean {
        if (!regionsKnown || target.session.unshowable.count == 0) return false
        val minute = target.session.regionAges.minuteOf(target.regionFolder, chunkX shr 5, chunkZ shr 5)
        return minute != 0 && target.session.unshowable.covers(target.regionFolder, chunkX shr 5, chunkZ shr 5, minute)
    }

    /**
     * Has [UnshowableRegions] look at the regions of the layer in view, close up, where Xaero
     * always loads what it shows. With [debug], also writes what Xaero has of each to the log.
     */
    fun watchRegions(target: Target, mapProcessor: MapProcessor, minX: Double, maxX: Double, minZ: Double, maxZ: Double, closeUp: Boolean, debug: Boolean) {
        val session = target.session
        val folder = target.regionFolder
        if (!session.regionAges.isKnown(folder)) return
        val now = System.currentTimeMillis()
        val lines = if (debug) ArrayList<String>() else null
        for (rx in floor(minX / 512).toInt()..floor(maxX / 512).toInt()) {
            for (rz in floor(minZ / 512).toInt()..floor(maxZ / 512).toInt()) {
                val minute = session.regionAges.minuteOf(folder, rx, rz)
                if (minute == 0) continue
                val region = try {
                    mapProcessor.getLeafMapRegion(target.layer, rx, rz, false)
                } catch (e: RuntimeException) {
                    continue
                }
                val state = region?.loadState?.toInt()
                if (closeUp) {
                    val seen = when {
                        region == null -> UnshowableRegions.Seen.NOTHING
                        state == LOADED -> UnshowableRegions.Seen.LOADED
                        else -> UnshowableRegions.Seen.NOT_LOADED
                    }
                    session.unshowable.observe(folder, rx, rz, minute, seen, now)
                }
                lines?.add(describeRegion(folder, rx, rz, region, state))
            }
        }
        if (lines != null) {
            Log.info("Regions in view with a file ({}), as Xaero has them:\n  {}", folder, lines.joinToString("\n  "))
        }
    }

    /** One region for the debug log: what Xaero holds of it, and whether its file opens. */
    private fun describeRegion(folder: Path, rx: Int, rz: Int, region: xaero.map.region.MapRegion?, state: Int?): String {
        val path = folder.resolve(MapBackup.regionFile(rx, rz))
        val file = try {
            java.util.zip.ZipFile(path.toFile()).use { zip ->
                "file ${java.nio.file.Files.size(path)} bytes, zip entries ${zip.entries().toList().joinToString { "${it.name} (${it.size})" }}"
            }
        } catch (e: Exception) {
            "file does not open as a zip: $e"
        }
        val xaero = if (region == null) "no region object" else try {
            var chunks = 0
            for (x in 0 until 8) for (z in 0 until 8) if (region.getChunk(x, z) != null) chunks++
            "load state $state, loaded ${region.isLoaded}, resting ${region.isResting}, has had terrain ${region.hasHadTerrain()}, " +
                "being written ${region.isBeingWritten}, $chunks of 64 tile chunks"
        } catch (e: RuntimeException) {
            "region object, load state $state, unreadable: $e"
        }
        return "${rx}_$rz: $xaero; $file"
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
            val region = mapProcessor.getLeafMapRegion(mapProcessor.currentCaveLayer, chunkX shr 5, chunkZ shr 5, false) ?: return null
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
    private const val LOADED = 2
    private const val UNKNOWN: Byte = 0
    private const val NOTHING: Byte = 1
    private const val SOMETHING: Byte = 2
    private const val MAX_TILES_SCANNED = 40_000L
    private const val INDEX_ABOVE = 16L
    private const val MAX_TILES_DRAWN = 1024
    private const val MASK_LIFETIME_MS = 5_000L

    /** Zoomed out further than this many blocks a pixel, tiles on screen are not asked about: there are too many. */
    private const val CHECK_UP_TO_FACTOR = 4
}
