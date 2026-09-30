package com.skystormer.skysmapexposer

import net.minecraft.world.level.block.Blocks
import xaero.map.MapProcessor
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

    /** What a region write came to, for the log and the progress line. */
    class Tally {
        var written = 0
        var kept = 0
        var newChunks = 0
        var replacedChunks = 0
        var partialSkipped = 0

        fun add(other: Tally) {
            written += other.written
            kept += other.kept
            newChunks += other.newChunks
            replacedChunks += other.replacedChunks
            partialSkipped += other.partialSkipped
        }
    }

    /**
     * Region ([x], [z]) of the surface, if Xaero has it loaded and nothing else is busy with it.
     * Otherwise asks Xaero to load it — the same request its writer makes for a region you walk
     * into — and returns null; call again on a later tick.
     *
     * Xaero only reads a region's file if the region is marked as having had terrain, and throws a
     * marked region away if its file turns out to be missing, so the mark is set exactly when
     * [folder] has the file.
     */
    fun loaded(processor: MapProcessor, folder: Path, x: Int, z: Int): MapRegion? {
        val region = processor.getLeafMapRegion(SURFACE, x, z, true) ?: return null
        synchronized(region) {
            if (region.loadState.toInt() == LOADED && region.isResting) return region
            if (region.isResting && region.canRequestReload_unsynced() && region.loadState.toInt() != LOADED) {
                if (Files.exists(folder.resolve(MapBackup.regionFile(x, z)))) region.setHasHadTerrain()
                // Marked as being written, a region loads its full data rather than just the
                // picture Xaero cached of it.
                region.setBeingWritten(true)
                processor.mapSaveLoad.requestLoad(region, "Sky's Map Exposer download")
                processor.mapSaveLoad.setNextToLoadByViewing(region)
            }
        }
        return null
    }

    /**
     * Writes Xaero's 64-block tile chunks [from] until [until] (numbered `x * 8 + z` within the
     * region) of [picture] into [region]. Returns the number of the first tile chunk not yet done:
     * [until] when all went in, less when Xaero was busy and the rest should wait for a later tick,
     * or -1 when the region is no longer loaded and has to be loaded again.
     */
    fun write(
        processor: MapProcessor,
        region: MapRegion,
        picture: BlueMapPicture.RegionPicture,
        palette: XaeroPalette,
        from: Int,
        until: Int,
        tally: Tally,
        unexploredOnly: Boolean = false,
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
                    writeTileChunk(processor, region, existing, tileChunkX, tileChunkZ, picture, palette, tally, unexploredOnly)
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
        picture: BlueMapPicture.RegionPicture,
        palette: XaeroPalette,
        tally: Tally,
        unexploredOnly: Boolean,
    ) {
        val pixel = XaeroPalette.Pixel()
        var tileChunk = existing
        var created = false
        for (tileX in 0 until 4) {
            for (tileZ in 0 until 4) {
                val chunkInRegionX = tileChunkX * 4 + tileX
                val chunkInRegionZ = tileChunkZ * 4 + tileZ
                val have = picture.countInChunk(chunkInRegionX, chunkInRegionZ)
                if (have == 0) continue
                val oldTile = tileChunk?.getTile(tileX, tileZ)
                val mapped = oldTile != null && oldTile.isLoaded
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
                val chunkX = region.regionX * 32 + chunkInRegionX
                val chunkZ = region.regionZ * 32 + chunkInRegionZ
                val tile = oldTile ?: processor.tilePool.get(processor.currentDimension, chunkX, chunkZ)
                var changed = false
                for (x in 0 until 16) {
                    for (z in 0 until 16) {
                        val px = chunkInRegionX * 16 + x
                        val pz = chunkInRegionZ * 16 + z
                        val i = pz * BlueMapPicture.REGION + px
                        val colour = picture.colour[i]
                        val old = if (mapped) tile.getBlock(x, z) else null
                        if (colour == 0) continue // only reachable when mapped: yours stays
                        val height = picture.height[i].toInt()
                        val guessed = if (old?.biome == null) picture.guess?.at(px, pz)?.let(palette::biomeKey) else null
                        palette.decide(colour, height, old?.biome ?: guessed, pixel)
                        if (old != null && palette.stillMatches(old, pixel.water, colour, pixel.biome)) {
                            tally.kept++
                            continue
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
                        block.setSlopeUnknown(true)
                        tile.setBlock(x, z, block)
                        tally.written++
                        changed = true
                    }
                }
                if (!changed && mapped) continue
                if (mapped) tally.replacedChunks++ else tally.newChunks++
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

    private const val LOADED = 2
}
