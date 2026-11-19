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
import it.unimi.dsi.fastutil.longs.LongOpenHashSet
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
    @Volatile
    private var cancelled = false

    /** Whether a save is going, for the menu and commands. */
    val busy: Boolean get() = saving

    /** Stops the save going on, keeping nothing of it; false if there is none. */
    fun cancel(): Boolean {
        if (!saving) return false
        cancelled = true
        return true
    }

    private val CACHE_FILE = Regex("""c\.(-?\d+)\.(-?\d+)\.jmc""")

    /**
     * Saves the chunks of [wanted] (inclusive), or every region JourneyMap has cached when null, of
     * [dimension] from JourneyMap's cache to a file in the shared maps folder. Anything over
     * [SharedTerrain.WARN_SIDE] chunks a side comes back as a warning to confirm, unless
     * [confirmed]; it never starts without room for it. The outcome comes in chat.
     */
    fun save(dimension: String, wanted: ShareFormat.Area?, confirmed: Boolean): SharedTerrain.Answer {
        if (saving) return SharedTerrain.Answer("A map is already being saved to a file; right-click the map and choose Stop saving to stop it")
        val registries = Minecraft.getInstance().level?.registryAccess() ?: return SharedTerrain.Answer("Not in a world")
        val folder = JourneyMapFiles.chunkCacheFolder(dimension) ?: return SharedTerrain.Answer("JourneyMap has no map for this world yet")
        val regions = SharedTerrain.regionsIn(wanted, SharedTerrain.regionFiles(folder, CACHE_FILE))
        if (regions.isEmpty()) return SharedTerrain.Answer("Nothing of JourneyMap's there to save: it has no chunks cached there")
        val area = wanted ?: SharedTerrain.spanning(regions)!!
        SharedTerrain.noRoom(regions.size)?.let { return SharedTerrain.Answer(it) }
        if (!confirmed) SharedTerrain.bigWarning(area.width, area.height, regions.size)?.let { return SharedTerrain.Answer(it, confirm = true) }
        val me = Minecraft.getInstance().player?.gameProfile?.name() ?: "?"
        val old = oldChunks(dimension, regions)
        saving = true
        cancelled = false
        Thread({
            val message = try {
                write(me, dimension, area, regions, folder, registries, old)
            } catch (e: Exception) {
                Log.error("Could not save terrain from JourneyMap", e)
                Component.literal("Saving stopped: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                saving = false
            }
            Minecraft.getInstance().execute { Minecraft.getInstance().player?.sendSystemMessage(message) }
        }, "SkysMapExposer JourneyMap terrain file").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        return SharedTerrain.Answer("Saving the terrain of ${area.width} × ${area.height} chunks to a file…")
    }

    private fun key(chunkX: Int, chunkZ: Int): Long = (chunkX.toLong() shl 32) or (chunkZ.toLong() and 0xFFFFFFFFL)

    /**
     * The chunks of [regions] from before [dimension]'s season began, by when you last saw them, as
     * Xaero's save leaves them out; none without a season start. Client thread, where the visit log is.
     */
    private fun oldChunks(dimension: String, regions: List<Pair<Int, Int>>): LongOpenHashSet? {
        val session = Session.current ?: return null
        val start = session.server.coverBefore[dimension]?.let(Clock::minutesOf) ?: return null
        val old = LongOpenHashSet()
        for ((rx, rz) in regions) for (cz in rz * 32 until rz * 32 + 32) for (cx in rx * 32 until rx * 32 + 32) {
            val seen = session.visits.lastSeen(dimension, cx, cz)
            if (seen != 0 && seen < start) old.add(key(cx, cz))
        }
        return old
    }

    private fun write(
        me: String, dimension: String, area: ShareFormat.Area, regions: List<Pair<Int, Int>>, cacheFolder: Path,
        registries: RegistryAccess, old: LongOpenHashSet?,
    ): Component {
        val (file, part) = SharedTerrain.newFile(dimension, area)
        val shareId = ThreadLocalRandom.current().nextInt(1 shl 30)
        var chunks = 0
        var oldLeftOut = 0
        try {
            DataOutputStream(BufferedOutputStream(Files.newOutputStream(part))).use { out ->
                SharedTerrain.startFile(out, me, dimension, MapLayers.SURFACE, area)
                for ((index, region) in regions.withIndex()) {
                    if (cancelled) throw Stopped()
                    if (SharedTerrain.nearlyFull()) throw java.io.IOException("the drive is nearly full")
                    val (rx, rz) = region
                    val cache = JourneyMapChunkCache.readRegion(JourneyMapChunkCache.file(cacheFolder, rx, rz), registries, withBlocks = true)
                    val writer = SharedTerrain.Writer()
                    for (cz in maxOf(area.top, rz * 32)..minOf(area.bottom, rz * 32 + 31)) {
                        for (cx in maxOf(area.left, rx * 32)..minOf(area.right, rx * 32 + 31)) {
                            val chunk = cache[(cz - rz * 32) * 32 + (cx - rx * 32)] ?: continue
                            if (old?.contains(key(cx, cz)) == true) { oldLeftOut++; continue }
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
        } catch (e: Stopped) {
            runCatching { Files.deleteIfExists(part) }
            return Component.literal("Stopped saving the terrain file; nothing of it was kept")
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

    private class Stopped : Exception()

    /** The deepest a water layer is recorded, as a byte. */
    private const val MAX_DEPTH = 255
}
