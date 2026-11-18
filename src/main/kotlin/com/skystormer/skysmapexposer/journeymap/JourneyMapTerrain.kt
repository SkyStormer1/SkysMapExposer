package com.skystormer.skysmapexposer.journeymap

import com.mojang.blaze3d.platform.NativeImage
import com.skystormer.skysmapexposer.BlueMap
import com.skystormer.skysmapexposer.Clock
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.SeaFloor
import com.skystormer.skysmapexposer.Session
import com.skystormer.skysmapexposer.TilePixels
import com.skystormer.skysmapexposer.TileStore
import journeymap.api.v2.client.IClientAPI
import journeymap.api.v2.common.Context
import net.minecraft.client.Minecraft
import net.minecraft.world.level.ChunkPos
import java.util.BitSet

/**
 * BlueMap's terrain on JourneyMap's full-screen map: one picture per JourneyMap region (512 blocks
 * square) on screen, showing BlueMap only in the chunks that need it and see-through everywhere
 * else, so JourneyMap's own map shows through wherever it is good. The same on its minimap, for
 * the regions around you there.
 *
 * A chunk needs it when BlueMap has something there and either JourneyMap has nothing (asked with
 * the API's `requestMapTile`, which hands back JourneyMap's own picture of the region), or your map
 * of it is out of date by the same rules as on Xaero's map: seen before the dimension's
 * [Config.Server.coverBefore], or longer ago than [Config.staleDays] with BlueMap's picture newer.
 *
 * Pictures come from the tiles [TileStore] already keeps on disk; nothing is cached here but the
 * pictures on screen, which stay within the texture budget ([Config.textureBudgetMb]) and are
 * closed once their region goes off screen or you leave. Zoomed out, each picture is smaller, read
 * from the tile's own smaller copy when it has one. On the full-screen map each picture is an
 * overlay; on the minimap, [JourneyMapMinimap] draws it.
 */
internal object JourneyMapTerrain {

    /** A region of a dimension, on the full-screen map or the minimap: each has its own pictures, at its own zoom. */
    private data class Region(val dimension: String, val x: Int, val z: Int, val ui: Context.UI)

    /**
     * A picture on screen, with what it was made from: the chunks shown ([wanted]), and each tile's
     * content date and sea floor, so a change to any of them rebuilds it. [checkedAt]: when
     * JourneyMap was last asked what it has there.
     */
    private class Shown(
        val picture: RegionPicture, val wanted: BitSet, val contents: List<Int>, val floors: List<IntArray?>, var checkedAt: Long,
    )

    private val shown = HashMap<Region, Shown>()

    /** Regions asked of JourneyMap or being built; each is in one or the other until it is shown. */
    private val busy = HashSet<Region>()

    /** Bumped on leaving, so a picture built for the last world is thrown away when it arrives. */
    private var generation = 0

    /**
     * Client thread, every tick, as Xaero's map is drawn every frame: new ground on screen is asked
     * for at once, and what is shown is checked again every [RECHECK_MS], as Xaero's masks are.
     */
    fun tick(api: IClientAPI, session: Session?) {
        tickFor(api, session, Context.UI.Fullscreen)
        tickFor(api, session, Context.UI.Minimap)
    }

    private val lastActive = HashMap<Context.UI, Long>()

    private fun tickFor(api: IClientAPI, session: Session?, ui: Context.UI) {
        val fullscreen = ui == Context.UI.Fullscreen
        fun say(text: String) { if (fullscreen) note = text else minimapNote = text }
        val now = System.currentTimeMillis()
        val state = api.getUIState(ui)
        say(when {
            session == null -> "this server has no BlueMap set up"
            !Config.enabled -> "switched off"
            state == null -> "JourneyMap has not said what it shows"
            !state.active -> if (fullscreen) "the full-screen map is closed" else "the minimap is hidden"
            state.mapType != Context.MapType.Day && state.mapType != Context.MapType.Underground -> "BlueMap shows on JourneyMap's day and cave maps"
            else -> ""
        })
        if (session == null || !Config.enabled || state == null || !state.active ||
            (state.mapType != Context.MapType.Day && state.mapType != Context.MapType.Underground)) {
            // Kept while the map is only closed for a moment, and let go once it has stayed closed;
            // switched off, no BlueMap, or another kind of map: gone at once, as on Xaero's map.
            val closedOnly = session != null && Config.enabled && state != null && !state.active
            if (shown.keys.any { it.ui == ui } && (!closedOnly || now - (lastActive[ui] ?: 0L) > KEEP_CLOSED_MS)) clear(api, ui)
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
        val bounds = net.minecraft.world.phys.AABB(area[0], 0.0, area[1], area[2], 0.0, area[3])

        val left = Math.floorDiv(bounds.minX.toInt(), REGION)
        val right = Math.floorDiv(bounds.maxX.toInt(), REGION)
        val top = Math.floorDiv(bounds.minZ.toInt(), REGION)
        val bottom = Math.floorDiv(bounds.maxZ.toInt(), REGION)
        val inView = (right - left + 1) * (bottom - top + 1)
        // The tile's own size for this zoom, as on Xaero's map ([TilePixels.factorsFor]); coarser
        // only while the regions on screen would not fit the budget.
        val factors = TilePixels.factorsFor(layout.tileSize)
        val blocksPerPixel = if (state.blockSize > 0) 1 / state.blockSize else 1.0
        var step = factors.indexOfLast { it <= blocksPerPixel.coerceAtLeast(1.0) }.coerceAtLeast(0)
        while (step < factors.lastIndex && inView.toLong() * bytesAt(factors[step]) > budget()) step++
        val scale = factors[step]
        val wanted = HashSet<Region>()
        for (z in top..bottom) for (x in left..right) wanted.add(Region(dimension, x, z, ui))
        // The sea floor, from BlueMap's 3D tiles, as on Xaero's map: asked for the tiles on screen.
        val seaFloor = Config.seaFloorHires && !SeaFloor.hasCeiling(dimension)

        shown.keys.filter { it.ui == ui && (it !in wanted || shown[it]!!.picture.scale != scale) }.forEach { remove(api, it) }

        // Nearest first, at most as many a tick as Xaero rebuilds masks a frame.
        val centreX = (left + right) / 2.0
        val centreZ = (top + bottom) / 2.0
        var started = 0
        for (region in wanted.sortedBy { (it.x - centreX) * (it.x - centreX) + (it.z - centreZ) * (it.z - centreZ) }) {
            if (started >= PER_TICK) break
            if (region in busy) continue
            val old = shown[region]
            val tiles = tilesFor(session, region, layout) ?: continue.also { say("waiting for BlueMap's tiles") }
            if (seaFloor) tiles.forEach { session.seaFloor.want(it, map, bounds.center.x, bounds.center.z, bounds.minX, bounds.maxX, bounds.minZ, bounds.maxZ, closeUp = scale <= SeaFloor.CLOSE_UP) }
            val changed = old != null && (tiles.map { it.floor } != old.floors || tiles.map { it.contentMinute } != old.contents)
            if (old != null && !changed && now - old.checkedAt < RECHECK_MS) continue
            if (old == null && shown.size * bytesAt(scale) >= budget()) break
            busy.add(region)
            ask(api, session, region, tiles, scale, rebuild = changed, state.mapType, state.chunkY)
            started++
        }
    }

    /** Leaving a world: JourneyMap drops our overlays ([JourneyMapAdapter.leave]); this frees the pictures. */
    fun forget() {
        generation++
        shown.values.forEach { it.picture.release() }
        shown.clear()
        busy.clear()
    }

    private fun clear(api: IClientAPI, ui: Context.UI) {
        shown.keys.filter { it.ui == ui }.forEach { remove(api, it) }
    }

    private fun remove(api: IClientAPI, region: Region) {
        val gone = shown.remove(region) ?: return
        gone.picture.remove(api)
        if (region.ui == Context.UI.Minimap) JourneyMapMinimap.changed()
    }

    /**
     * BlueMap's finest tiles under [region], or null while any of them is still on its way (it is
     * asked again next check). Client thread.
     */
    private fun tilesFor(session: Session, region: Region, layout: BlueMap.MapLayout): List<TileStore.Tile>? {
        val size = layout.tileSize
        val map = session.server.mapFor(region.dimension) ?: return null
        val tiles = ArrayList<TileStore.Tile>()
        for (tileZ in Math.floorDiv(region.z * REGION, size)..Math.floorDiv(region.z * REGION + REGION - 1, size)) {
            for (tileX in Math.floorDiv(region.x * REGION, size)..Math.floorDiv(region.x * REGION + REGION - 1, size)) {
                val tile = session.tiles.get(TileStore.Key(map, 1, tileX, tileZ), 1, size)
                if (tile.state == TileStore.State.LOADING) return null
                if (tile.state == TileStore.State.READY) tiles.add(tile)
            }
        }
        return tiles
    }

    /** Asks JourneyMap what it has of [region], then builds the picture from that. */
    private fun ask(
        api: IClientAPI, session: Session, region: Region, tiles: List<TileStore.Tile>, scale: Int, rebuild: Boolean,
        type: Context.MapType, chunkY: Int?,
    ) {
        val generation = generation
        val key = JourneyMapAdapter.levelKey(region.dimension)
        val first = ChunkPos(region.x * CHUNKS, region.z * CHUNKS)
        val last = ChunkPos(region.x * CHUNKS + CHUNKS - 1, region.z * CHUNKS + CHUNKS - 1)
        try {
            // JourneyMap's own picture of the same map it is showing: day, or the cave slice on screen.
            api.requestMapTile(JourneyMapAdapter.MOD_ID, key, type, first, last, chunkY, 0, false) { image ->
                // Called back on the client thread.
                val mapped = BitSet(CHUNKS * CHUNKS)
                image?.use { readMapped(it, mapped) }
                if (generation != this.generation || region !in busy) return@requestMapTile
                val wanted = wantedChunks(session, region, tiles, mapped)
                val old = shown[region]
                if (wanted.isEmpty) {
                    busy.remove(region)
                    remove(api, region)
                    return@requestMapTile
                }
                // The same chunks from the same tiles: the picture stands, as a mask rebuilt with
                // the same rectangles leaves Xaero's map as it was.
                if (old != null && !rebuild && old.wanted == wanted) {
                    old.checkedAt = System.currentTimeMillis()
                    busy.remove(region)
                    return@requestMapTile
                }
                val made = Made(wanted, tiles.map { it.contentMinute }, tiles.map { it.floor })
                session.work { build(api, session, region, tiles, scale, generation, made) }
            }
        } catch (e: Exception) {
            busy.remove(region)
            Log.warn("JourneyMap would not hand over its map of region {}, {}: {}", region.x, region.z, e.toString())
        }
    }

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

    /** The chunks of [region] to show BlueMap in, by the rules above. Client thread. */
    private fun wantedChunks(session: Session, region: Region, tiles: List<TileStore.Tile>, mapped: BitSet): BitSet {
        val wanted = BitSet(CHUNKS * CHUNKS)
        val now = Clock.nowMinutes()
        val staleMinutes = Config.staleMinutes
        val coverBefore = session.server.coverBefore[region.dimension]?.let(Clock::minutesOf) ?: 0
        for (chunkZ in 0 until CHUNKS) for (chunkX in 0 until CHUNKS) {
            val cx = region.x * CHUNKS + chunkX
            val cz = region.z * CHUNKS + chunkZ
            val tile = tileAt(tiles, (cx shl 4) + 8, (cz shl 4) + 8) ?: continue
            // BlueMap must have something at the middle of the chunk, or it would replace your map with nothing.
            if (!tile.hasDataAt((cx shl 4) + 8 - tile.key.x * tile.tileSize, (cz shl 4) + 8 - tile.key.z * tile.tileSize)) continue
            val index = chunkZ * CHUNKS + chunkX
            if (!mapped[index]) { wanted.set(index); continue }
            val seen = session.visits.lastSeen(region.dimension, cx, cz)
            if (coverBefore != 0 && seen < coverBefore) { wanted.set(index); continue }
            // JourneyMap keeps no dates of its own, so ground never seen since the mod was installed counts as current.
            if (seen != 0 && now - seen >= staleMinutes && tile.contentMinute > seen) wanted.set(index)
        }
        return wanted
    }

    private fun tileAt(tiles: List<TileStore.Tile>, x: Int, z: Int): TileStore.Tile? = tiles.firstOrNull { tile ->
        Math.floorDiv(x, tile.tileSize) == tile.key.x && Math.floorDiv(z, tile.tileSize) == tile.key.z
    }

    /** Off the client thread: the region's picture, BlueMap where [wanted], see-through elsewhere. */
    /** What a picture is made from, kept with it to tell when it needs making again. */
    private class Made(val wanted: BitSet, val contents: List<Int>, val floors: List<IntArray?>)

    private fun build(api: IClientAPI, session: Session, region: Region, tiles: List<TileStore.Tile>, scale: Int, generation: Int, made: Made) {
        val wanted = made.wanted
        // Rounded up: a tile size's factor need not divide the region; the last pixel runs a little past it.
        val side = (REGION + scale - 1) / scale
        val image = NativeImage(side, side, true)
        try {
            for (tile in tiles) {
                val size = tile.tileSize
                // The tile's own smaller copy when it has one at this scale, else the full one, sampled;
                // either way with its sea floor in, once it has one.
                val smaller = session.tiles.drawnPicture(tile, scale)
                val (pixels, factor) = smaller?.let { it to scale } ?: (session.tiles.drawnPicture(tile, 1) ?: continue) to 1
                val pictureSide = size / factor
                val originX = tile.key.x * size
                val originZ = tile.key.z * size
                for (py in 0 until side) for (px in 0 until side) {
                    val blockX = region.x * REGION + px * scale
                    val blockZ = region.z * REGION + py * scale
                    if (Math.floorDiv(blockX, size) != tile.key.x || Math.floorDiv(blockZ, size) != tile.key.z) continue
                    val chunkX = (blockX shr 4) - region.x * CHUNKS
                    val chunkZ = (blockZ shr 4) - region.z * CHUNKS
                    if (chunkX >= CHUNKS || chunkZ >= CHUNKS || !wanted[chunkZ * CHUNKS + chunkX]) continue
                    val inX = blockX - originX
                    val inZ = blockZ - originZ
                    if (!tile.hasDataAt(inX, inZ)) continue
                    val colour = pixels[(inZ / factor).coerceAtMost(pictureSide - 1) * pictureSide + (inX / factor).coerceAtMost(pictureSide - 1)]
                    image.setPixel(px, py, colour or OPAQUE)
                }
            }
        } catch (e: Exception) {
            image.close()
            Minecraft.getInstance().execute { busy.remove(region) }
            Log.warn("Could not build BlueMap's picture of region {}, {} for JourneyMap: {}", region.x, region.z, e.toString())
            return
        }
        Minecraft.getInstance().execute { show(api, region, image, scale, generation, made) }
    }

    /** Client thread: puts the finished picture up, replacing the region's old one. */
    private fun show(api: IClientAPI, region: Region, image: NativeImage, scale: Int, generation: Int, made: Made) {
        if (generation != this.generation || region !in busy) {
            image.close()
            return
        }
        busy.remove(region)
        remove(api, region)
        val fullscreen = region.ui == Context.UI.Fullscreen
        val picture = RegionPicture.show(api, "terrain", region.dimension, region.x * REGION, region.z * REGION, scale, image, fullscreen, TERRAIN_ORDER) ?: return
        shown[region] = Shown(picture, made.wanted, made.contents, made.floors, System.currentTimeMillis())
        if (!fullscreen) JourneyMapMinimap.changed()
    }

    /** Why nothing is being drawn just now, if anything stops it, on the full-screen map and the minimap. */
    private var note = ""
    private var minimapNote = ""

    /** "6 regions of terrain" on JourneyMap now, and why not more if something is in the way, for `/mapexposer`. */
    fun summary(): String {
        val full = shown.keys.count { it.ui == Context.UI.Fullscreen }
        val mini = shown.size - full
        return "$full region${if (full == 1) "" else "s"} of terrain" + (if (note.isEmpty()) "" else " ($note)") +
            ", $mini on the minimap" + (if (minimapNote.isEmpty()) "" else " ($minimapNote)")
    }

    /** The minimap's pictures of [dimension], for [JourneyMapMinimap] to draw. */
    fun minimapPictures(dimension: String): List<RegionPicture> =
        shown.mapNotNull { (region, picture) -> picture.picture.takeIf { region.ui == Context.UI.Minimap && region.dimension == dimension } }

    private fun budget(): Long = Config.textureBudgetMb.toLong() * 1024 * 1024

    private fun bytesAt(scale: Int): Long = ((REGION + scale - 1) / scale).toLong().let { it * it * 4 }

    private const val REGION = 512
    private const val CHUNKS = REGION / 16
    /** Regions asked about a tick at most: Xaero rebuilds up to 8 masks a frame. */
    private const val PER_TICK = 8

    /** How often what is shown is checked again: as often as Xaero rebuilds its masks. */
    private const val RECHECK_MS = 5_000L
    private const val KEEP_CLOSED_MS = 30_000L
    private const val OPAQUE = 0xFF000000.toInt()

    /** Under the outlines and pins ([JourneyMapPins]). */
    private const val TERRAIN_ORDER = 50
}
