package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.Clock
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapLayers
import com.skystormer.skysmapexposer.Session
import com.skystormer.skysmapexposer.ShareFormat
import com.skystormer.skysmapexposer.SharedTerrain
import net.minecraft.client.Minecraft
import net.minecraft.core.RegistryAccess
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.BitSet
import java.util.concurrent.ThreadLocalRandom

/**
 * Saving terrain files from JourneyMap, the same files as Xaero's save writes ([SharedTerrain]), so
 * whoever gets one adds it to either map. JourneyMap's map keeps only pictures, but its chunk cache
 * keeps every block, which is read here ([JourneyMapChunkCache]): for each column, the top block
 * under any water as the ground, the water over it as a layer as deep as it is, the biome and the
 * light, as Xaero records them. The surface only, as JourneyMap's day map. Chunks from before the
 * season began are left out, as from Xaero's map.
 *
 * Read and written off the client thread; one save at a time.
 */
internal object JourneyMapTerrainSave {

    @Volatile
    private var saving = false

    /** Whether a save is going, for the menu and commands. */
    val busy: Boolean get() = saving

    /**
     * Saves chunks [left]..[right], [top]..[bottom] (inclusive) of [dimension] from JourneyMap's
     * cache to a file in the shared maps folder. What to tell you now; the outcome comes in chat.
     */
    fun save(dimension: String, left: Int, top: Int, right: Int, bottom: Int): String {
        if (saving) return "A map is already being saved to a file"
        val area = ShareFormat.Area(left, top, right, bottom)
        if (area.width > ShareFormat.MAX_SIDE || area.height > ShareFormat.MAX_SIDE) return "That is too much at once: at most ${ShareFormat.MAX_SIDE} chunks a side"
        val registries = Minecraft.getInstance().level?.registryAccess() ?: return "Not in a world"
        val folder = JourneyMapFiles.chunkCacheFolder(dimension) ?: return "JourneyMap has no map for this world yet"
        val me = Minecraft.getInstance().player?.gameProfile?.name() ?: "?"
        val old = oldChunks(dimension, area)
        saving = true
        Thread({
            val message = try {
                write(me, dimension, area, folder, registries, old)
            } catch (e: Exception) {
                Log.error("Could not save terrain from JourneyMap", e)
                Component.literal("Saving stopped: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                saving = false
            }
            Minecraft.getInstance().execute { Minecraft.getInstance().player?.sendSystemMessage(message) }
        }, "SkysMapExposer JourneyMap terrain file").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        return "Saving the terrain of ${area.width} × ${area.height} chunks to a file…"
    }

    /**
     * The chunks of [area] from before [dimension]'s season began, by when you last saw them, as
     * Xaero's save leaves them out; none without a season start. Client thread, where the visit log is.
     */
    private fun oldChunks(dimension: String, area: ShareFormat.Area): BitSet? {
        val session = Session.current ?: return null
        val start = session.server.coverBefore[dimension]?.let(Clock::minutesOf) ?: return null
        val old = BitSet(area.width * area.height)
        for (cz in area.top..area.bottom) for (cx in area.left..area.right) {
            val seen = session.visits.lastSeen(dimension, cx, cz)
            if (seen != 0 && seen < start) old.set((cz - area.top) * area.width + (cx - area.left))
        }
        return old
    }

    private fun write(
        me: String, dimension: String, area: ShareFormat.Area, cacheFolder: Path, registries: RegistryAccess, old: BitSet?,
    ): Component {
        val (file, part) = SharedTerrain.newFile(dimension, area)
        val regions = buildList {
            for (rz in Math.floorDiv(area.top, 32)..Math.floorDiv(area.bottom, 32)) {
                for (rx in Math.floorDiv(area.left, 32)..Math.floorDiv(area.right, 32)) add(rx to rz)
            }
        }
        val shareId = ThreadLocalRandom.current().nextInt(1 shl 30)
        var chunks = 0
        var oldLeftOut = 0
        try {
            DataOutputStream(BufferedOutputStream(Files.newOutputStream(part))).use { out ->
                SharedTerrain.startFile(out, me, dimension, MapLayers.SURFACE, area)
                for ((index, region) in regions.withIndex()) {
                    val (rx, rz) = region
                    val cache = JourneyMapChunkCache.readRegion(JourneyMapChunkCache.file(cacheFolder, rx, rz), registries, withBlocks = true)
                    val writer = SharedTerrain.Writer()
                    for (cz in maxOf(area.top, rz * 32)..minOf(area.bottom, rz * 32 + 31)) {
                        for (cx in maxOf(area.left, rx * 32)..minOf(area.right, rx * 32 + 31)) {
                            val chunk = cache[(cz - rz * 32) * 32 + (cx - rx * 32)] ?: continue
                            if (old?.get((cz - area.top) * area.width + (cx - area.left)) == true) { oldLeftOut++; continue }
                            writer.chunk(cx, cz, columns(chunk) ?: continue)
                        }
                    }
                    if (writer.chunks == 0) continue
                    val header = ShareFormat.Header(me, dimension, shareId, index, regions.size, area)
                    SharedTerrain.writeRegion(out, rx, rz, writer.chunks, writer.pack(header))
                    chunks += writer.chunks
                }
            }
            if (chunks == 0) {
                Files.deleteIfExists(part)
                return Component.literal(SharedTerrain.nothingSaved(oldLeftOut, "Nothing of JourneyMap's there to save: it has no chunks cached there"))
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(part) }
            throw e
        }
        Log.info("Saved the terrain of {} chunks from JourneyMap ({} old-season chunks left out) to {}", chunks, oldLeftOut, file)
        return SharedTerrain.savedMessage(chunks, file, Files.size(file), oldLeftOut)
    }

    /** [chunk]'s 256 columns as Xaero records them; null if JourneyMap cached no heights for it. */
    private fun columns(chunk: JourneyMapChunkCache.Chunk): SharedTerrain.SharedChunk? {
        val surface = chunk.surface ?: return null
        val out = SharedTerrain.SharedChunk()
        for (x in 0 until 16) for (z in 0 until 16) {
            val i = x * 16 + z
            val top = (surface[x + z * 16] - 1).coerceIn(chunk.minBuild, chunk.maxBuild - 1)
            val topState = chunk.blockAt(x, top, z)
            // Water is a layer over the ground, as deep as it is, as Xaero keeps it.
            var ground = top
            while (ground > chunk.minBuild && isWater(chunk.blockAt(x, ground, z))) ground--
            val state = chunk.blockAt(x, ground, z)
            val depth = top - ground
            out.state[i] = state
            out.height[i] = ground.toShort()
            out.top[i] = top.toShort()
            out.biome[i] = chunk.biomeAt(x, z, ground)
            out.light[i] = chunk.lightAt(x, (top + 1).coerceAtMost(chunk.maxBuild - 1), z).toByte()
            out.glowing[i] = state.lightEmission > 0
            if (depth > 0) {
                out.overlays[i] = listOf(SharedTerrain.SharedOverlay(topState, out.light[i], false, depth.coerceAtMost(MAX_DEPTH)))
            }
        }
        return out
    }

    /** Water itself; a waterlogged block is the ground, as Xaero sees it. */
    private fun isWater(state: BlockState): Boolean = state.block == Blocks.WATER

    /** The deepest a water layer is recorded, as a byte. */
    private const val MAX_DEPTH = 255
}
