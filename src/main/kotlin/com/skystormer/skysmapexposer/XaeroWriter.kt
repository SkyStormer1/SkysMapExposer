package com.skystormer.skysmapexposer

import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs
import net.minecraft.client.Minecraft
import net.minecraft.world.level.block.Blocks
import xaero.map.MapProcessor
import xaero.map.world.MapDimension
import xaero.map.WorldMapSession
import xaero.map.region.MapBlock
import xaero.map.region.MapRegion
import xaero.map.region.MapTileChunk
import xaero.map.region.Overlay
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writing a [BlueMapPicture.RegionPicture] into Xaero's own map, in memory, the way Xaero's writer
 * does when you walk through a chunk — and then letting Xaero save it, in its own format, itself.
 * Nothing here touches Xaero's files; Xaero reads them and writes them, and this only fills in the
 * pixels in between.
 *
 * Each of Xaero's pixels is a block, a height, a light level and a biome, and Xaero works out the
 * colour from those when it draws, shading hills from the heights. So BlueMap's heights go in as
 * they are, and each colour becomes the block from [XaeroPalette] whose colour Xaero would draw
 * closest to it.
 *
 * The rules:
 * - Where BlueMap has nothing, nothing is written: a pixel of yours stays yours, and a chunk
 *   nobody has seen stays blank.
 * - A chunk you have never mapped is only written if BlueMap has all 256 of its blocks, so there is
 *   never a half-drawn chunk with black holes in it.
 * - The biome: yours, where your map recorded one; otherwise [BiomeGuide]'s guess from BlueMap's
 *   colours; otherwise, where there is no guess, a placeholder for the dimension.
 * - A pixel of yours that already looks like BlueMap's is left exactly as it is, so the real
 *   blocks you mapped (paths, flowers, water over a sea bed) survive wherever nothing has changed.
 *
 * Every call runs on the client thread, which is where Xaero's own writer runs.
 */
object XaeroWriter {

    /** Xaero's cave layer for the surface, the only one BlueMap has anything for. */
    const val SURFACE = Int.MAX_VALUE

    /**
     * Where Xaero keeps the regions of cave [layer], under a dimension's map folder [base]. The
     * Nether's map is normally one of these, not the surface, which there is only the roof.
     */
    fun layerFolder(base: Path, layer: Int): Path = if (layer == SURFACE) base else base.resolve("caves").resolve(layer.toString())

    /** The map the world map is showing: its dimension, and the folder of the layer on screen. */
    class Shown(
        val processor: MapProcessor,
        val dimension: MapDimension,
        val dimensionId: String,
        /** The dimension's map folder, which has the surface's regions and the cave layers' folders. */
        val base: Path,
        /** Xaero's cave layer on screen: in the Nether a cave layer, as the surface there is the roof. */
        val layer: Int,
    ) {
        /** The regions of [layer]. */
        val folder: Path get() = layerFolder(base, layer)
        val name: String get() = MapMenus.dimensionName(dimensionId) ?: dimensionId
    }

    /** The map the world map is showing, or why there is none to work on yet. */
    fun shown(): Pair<Shown?, String?> {
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor
            ?: return null to "Xaero's world map is not ready yet; open it once and try again"
        val dimension = processor.mapWorld?.currentDimension ?: return null to "Xaero has no dimension open"
        val dimensionId = dimension.dimId?.identifier()?.toString() ?: return null to "Xaero's dimension has no id"
        val multiworld = dimension.currentMultiworld ?: return null to "Xaero has not chosen a map folder for this dimension yet"
        val base = dimension.mainFolderPath?.resolve(multiworld) ?: return null to "Xaero has not chosen a map folder for this dimension yet"
        return Shown(processor, dimension, dimensionId, base, processor.currentCaveLayer) to null
    }

    /** What a region write came to, for the log and the progress line. */
    class Tally {
        var written = 0
        var kept = 0
        var newChunks = 0
        var replacedChunks = 0
        var partialSkipped = 0
        var cleared = 0

        fun add(other: Tally) {
            written += other.written
            kept += other.kept
            newChunks += other.newChunks
            replacedChunks += other.replacedChunks
            partialSkipped += other.partialSkipped
            cleared += other.cleared
        }
    }

    /**
     * Region ([x], [z]) of cave [layer] (normally the surface), if Xaero has it loaded and nothing else is busy with it.
     * Otherwise asks Xaero to load it — the same request its writer makes for a region you walk
     * into — and returns null; call again on a later tick.
     *
     * Xaero only reads a region's file if the region is marked as having had terrain, and throws a
     * marked region away if its file turns out to be missing, so the mark is set exactly when
     * [folder], that layer's, has the file.
     */
    fun loaded(processor: MapProcessor, folder: Path, x: Int, z: Int, layer: Int = SURFACE): MapRegion? {
        val region = processor.getLeafMapRegion(layer, x, z, true) ?: return null
        synchronized(region) {
            if (region.loadState.toInt() == LOADED && region.isResting) return region
            if (region.isResting && region.canRequestReload_unsynced() && region.loadState.toInt() != LOADED) {
                if (Files.exists(folder.resolve(MapBackup.regionFile(x, z)))) region.setHasHadTerrain()
                // Marked as being written, a region loads its full data rather than just the
                // picture Xaero cached of it.
                if (!region.isBeingWritten) markedByUs.add(region)
                region.setBeingWritten(true)
                processor.mapSaveLoad.requestLoad(region, "Sky's Map Exposer download")
                processor.mapSaveLoad.setNextToLoadByViewing(region)
            }
        }
        return null
    }

    /** Regions [loaded] marked as being written only to read them, for [release]. */
    private val markedByUs: MutableSet<MapRegion> = Collections.newSetFromMap(WeakHashMap())

    /**
     * Done with a region [loaded] only to read it: takes back its "being written" mark, so Xaero
     * neither saves it again (the whole file, for nothing) nor keeps its full data in memory. Not
     * near you, where Xaero's own writer may have been adding what you see since.
     */
    fun release(region: MapRegion) {
        synchronized(region) {
            if (!markedByUs.remove(region)) return
            val player = Minecraft.getInstance().player
            if (player != null && abs(region.regionX - (player.blockX shr 9)) <= 1 && abs(region.regionZ - (player.blockZ shr 9)) <= 1) return
            region.setBeingWritten(false)
        }
    }

    /**
     * Writes Xaero's 64-block tile chunks [from] until [until] (numbered `x * 8 + z` within the
     * region) of [pixels] into [region]. Returns the number of the first tile chunk not yet done:
     * [until] when all went in, less when Xaero was busy and the rest should wait for a later tick,
     * or -1 when the region is no longer loaded and has to be loaded again.
     */
    fun write(
        processor: MapProcessor,
        region: MapRegion,
        pixels: Pixels,
        from: Int,
        until: Int,
        tally: Tally,
        unexploredOnly: Boolean = false,
        age: MapAge? = null,
    ): Int {
        synchronized(region.writerThreadPauseSync) {
            if (region.isWritingPaused) return from
            synchronized(region) {
                if (region.loadState.toInt() != LOADED || !region.isResting) return -1
                region.setBeingWritten(true)
                for (index in from until until) {
                    val tileChunkX = index / 8
                    val tileChunkZ = index % 8
                    val existing = region.getChunk(tileChunkX, tileChunkZ)
                    // Xaero is reading this one's picture back for its cache; not now.
                    if (existing != null && existing.leafTexture.shouldDownloadFromPBO()) return index
                    if (existing != null && existing.loadState != LOADED) continue
                    writeTileChunk(processor, region, existing, tileChunkX, tileChunkZ, pixels, tally, unexploredOnly, age)
                }
                if (!region.isNormalMapData) {
                    // What Xaero's writer does when it writes one layer of a singleplayer map.
                    region.dim.layeredMapRegions.applyToEachLoadedLayer { layer, _ ->
                        if (layer != region.caveLayer) {
                            processor.getLeafMapRegion(layer, region.regionX, region.regionZ, true)?.let {
                                it.setOutdatedWithOtherLayers(true)
                                it.setHasHadTerrain()
                            }
                        }
                    }
                }
                return until
            }
        }
    }

    /**
     * After the last [write]: has Xaero redraw the region from its new pixels and save it straight
     * away rather than in a minute's time. This is Xaero's own "resave" path, the one its full map
     * reload uses; [saved] reports when the save has happened.
     */
    fun finish(processor: MapProcessor, region: MapRegion) {
        synchronized(region) {
            region.setHasHadTerrain()
            region.setAllCachePrepared(false)
            region.setResaving(true)
        }
        region.requestRefresh(processor)
    }

    /** Whether Xaero has saved [region] since [finish]. */
    fun saved(region: MapRegion): Boolean = !region.isResaving

    private fun writeTileChunk(
        processor: MapProcessor,
        region: MapRegion,
        existing: MapTileChunk?,
        tileChunkX: Int,
        tileChunkZ: Int,
        pixels: Pixels,
        tally: Tally,
        unexploredOnly: Boolean,
        age: MapAge?,
    ) {
        var tileChunk = existing
        var created = false
        for (tileX in 0 until 4) {
            for (tileZ in 0 until 4) {
                val chunkInRegionX = tileChunkX * 4 + tileX
                val chunkInRegionZ = tileChunkZ * 4 + tileZ
                val chunkX = region.regionX * 32 + chunkInRegionX
                val chunkZ = region.regionZ * 32 + chunkInRegionZ
                val oldTile = tileChunk?.getTile(tileX, tileZ)
                val loaded = oldTile != null && oldTile.isLoaded
                if (loaded && pixels.clears(chunkX, chunkZ)) {
                    tileChunk.setTile(tileX, tileZ, null, processor.blockStateShortShapeCache, processor)
                    tileChunk.setChanged(true)
                    tally.cleared++
                    continue
                }
                val have = pixels.countInChunk(chunkInRegionX, chunkInRegionZ)
                if (have == 0) continue
                // A chunk mapped before the season began counts as never mapped: it is written
                // afresh, and nothing of it (its biomes least of all) is kept or built on.
                val mapped = loaded && age?.isOld(chunkX, chunkZ) != true
                if (mapped && unexploredOnly) continue
                if (!mapped && have < 256) {
                    tally.partialSkipped++
                    continue
                }
                if (tileChunk == null) {
                    tileChunk = MapTileChunk(region, region.regionX * 8 + tileChunkX, region.regionZ * 8 + tileChunkZ)
                    region.setChunk(tileChunkX, tileChunkZ, tileChunk)
                    tileChunk.setLoadState(LOADED.toByte())
                    region.setAllCachePrepared(false)
                    created = true
                }
                val tile = (if (mapped) oldTile else oldTile?.takeUnless { loaded }) ?: processor.tilePool.get(processor.currentDimension, chunkX, chunkZ)
                var changed = false
                for (x in 0 until 16) {
                    for (z in 0 until 16) {
                        val px = chunkInRegionX * 16 + x
                        val pz = chunkInRegionZ * 16 + z
                        val old = if (mapped) tile.getBlock(x, z) else null
                        val block = pixels.block(processor, px, pz, old, tally) ?: continue
                        block.setSlopeUnknown(true)
                        tile.setBlock(x, z, block)
                        tally.written++
                        changed = true
                    }
                }
                if (!changed && mapped) continue
                if (loaded) tally.replacedChunks++ else tally.newChunks++
                tile.worldInterpretationVersion = 1
                if (!mapped) tile.setWrittenCave(SURFACE, 0)
                tileChunk.setTile(tileX, tileZ, tile, processor.blockStateShortShapeCache, processor)
                tile.setWrittenOnce(true)
                tile.isLoaded = true
                tileChunk.setChanged(true)
            }
        }
        val chunk = tileChunk ?: return
        if (chunk.includeInSave()) chunk.setHasHadTerrain()
        if (created) processor.mapRegionHighlightsPreparer.prepare(region, tileChunkX, tileChunkZ, false)
    }

    /**
     * What goes into one region: for each of its blocks, the pixel to write over what your map has
     * there (`old`, null where you have nothing), or null to leave it as it is.
     */
    interface Pixels {
        val regionX: Int
        val regionZ: Int

        /** How many of the 256 blocks of chunk ([chunkInRegionX], [chunkInRegionZ]) this has. */
        fun countInChunk(chunkInRegionX: Int, chunkInRegionZ: Int): Int

        fun block(processor: MapProcessor, px: Int, pz: Int, old: MapBlock?, tally: Tally): MapBlock?

        /** Whether what your map has of chunk ([chunkX], [chunkZ]) is to be taken off it ([MapClear]). */
        fun clears(chunkX: Int, chunkZ: Int): Boolean = false
    }

    /** A region of BlueMap's picture, as blocks whose colours Xaero would draw closest to it. */
    class BlueMapPixels(
        private val picture: BlueMapPicture.RegionPicture,
        private val palette: XaeroPalette,
    ) : Pixels {
        private val pixel = XaeroPalette.Pixel()
        override val regionX get() = picture.regionX
        override val regionZ get() = picture.regionZ

        override fun countInChunk(chunkInRegionX: Int, chunkInRegionZ: Int) = picture.countInChunk(chunkInRegionX, chunkInRegionZ)

        override fun block(processor: MapProcessor, px: Int, pz: Int, old: MapBlock?, tally: Tally): MapBlock? {
            val i = pz * BlueMapPicture.REGION + px
            val colour = picture.colour[i]
            if (colour == 0) return null // only reachable when mapped: yours stays
            val height = picture.height[i].toInt()
            val guessed = if (old?.biome == null) picture.guess?.at(px, pz)?.let(palette::biomeKey) else null
            palette.decide(colour, height, old?.biome ?: guessed, pixel)
            if (old != null && palette.stillMatches(old, pixel.water, colour, pixel.biome)) {
                tally.kept++
                return null
            }
            val light = picture.light[i]
            val block = MapBlock()
            if (pixel.water) {
                // The way Xaero keeps water: the bed that many blocks below, and one
                // water layer over it whose opacity is the depth. The bed's height
                // follows the depth, so Xaero shades the sea floor's shape.
                block.write(pixel.state, height - pixel.depth, height, pixel.biome, light, false, false)
                val surface = Overlay(Blocks.WATER.defaultBlockState(), light, false)
                surface.increaseOpacity(pixel.depth)
                // Xaero shares one object per kind of layer; ask it for its own.
                block.addOverlay(processor.overlayManager.getOriginal(surface))
            } else {
                block.write(pixel.state, height, height, pixel.biome, light, pixel.state.lightEmission > 0, false)
            }
            return block
        }
    }

    private const val LOADED = 2
}
