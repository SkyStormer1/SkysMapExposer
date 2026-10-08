package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import xaero.map.MapProcessor
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Biomes other players shared with you ([ChatShare]), kept per server and dimension in this mod's
 * folder, never in Xaero's map. Minecraft's own resolution: one biome per 4×4 blocks, 16 per chunk
 * (`cell = (x / 4) * 4 + z / 4` within the chunk).
 *
 * Used where Xaero has no biome of its own — land you know only from BlueMap: the biome highlight
 * tints it ([BiomeHighlight]) and the world map names the biome under the mouse ([hoverLine]).
 *
 * Kept by region (1024 chunks each), so drawing only looks at the regions on screen however much
 * has been shared. Saved off the client thread. Read and changed on the client thread only.
 */
object SharedBiomes {

    /** One region's chunks, `(z & 31) * 32 + (x & 31)`; each chunk's 16 cells, or null if not shared. */
    private class Region {
        val chunks = arrayOfNulls<Array<ResourceKey<Biome>?>>(32 * 32)
    }

    /** Shared regions by dimension, then by [Session.chunkKey] of the region; loaded on first use. */
    private val dimensions = HashMap<String, HashMap<Long, Region>>()

    /** The server these are for; a different one empties the lot. */
    private var server: Path? = null

    private val saver = Executors.newSingleThreadExecutor { Thread(it, "SkysMapExposer shared biomes").apply { isDaemon = true } }

    fun forget() {
        dimensions.clear()
        server = null
        address = null
    }

    /**
     * Calls [action] with the block corner and 16 cells of every shared chunk of [dimensionId]
     * in the regions that touch blocks [minX]..[maxX], [minZ]..[maxZ].
     */
    fun forEachIn(
        dimensionId: String, minX: Double, maxX: Double, minZ: Double, maxZ: Double,
        action: (blockX: Int, blockZ: Int, cells: Array<ResourceKey<Biome>?>) -> Unit,
    ) {
        val regions = dimension(dimensionId) ?: return
        if (regions.isEmpty()) return
        for (rx in Math.floorDiv(minX.toInt(), 512)..Math.floorDiv(maxX.toInt(), 512)) {
            for (rz in Math.floorDiv(minZ.toInt(), 512)..Math.floorDiv(maxZ.toInt(), 512)) {
                val chunks = regions[Session.chunkKey(rx, rz)]?.chunks ?: continue
                for (i in chunks.indices) {
                    val cells = chunks[i] ?: continue
                    action(rx * 512 + (i and 31) * 16, rz * 512 + (i shr 5) * 16, cells)
                }
            }
        }
    }

    /** The shared biome of block ([x], [z]) of [dimensionId], if anyone shared it. */
    fun at(dimensionId: String, x: Int, z: Int): ResourceKey<Biome>? {
        val region = dimension(dimensionId)?.get(Session.chunkKey(x shr 9, z shr 9)) ?: return null
        val chunk = region.chunks[((z shr 4) and 31) * 32 + ((x shr 4) and 31)] ?: return null
        return chunk[((x and 15) shr 2) * 4 + ((z and 15) shr 2)]
    }

    /** Adds [chunks] (by [Session.chunkKey]) to what is kept for [dimensionId], replacing older ones, and saves. */
    fun add(dimensionId: String, chunks: Map<Long, Array<ResourceKey<Biome>?>>): Boolean {
        val into = dimension(dimensionId) ?: return false
        for ((key, cells) in chunks) put(into, key, cells)
        save(dimensionId, into)
        return true
    }

    private fun put(into: HashMap<Long, Region>, chunkKey: Long, cells: Array<ResourceKey<Biome>?>) {
        val cx = (chunkKey and 0xFFFFFFFFL).toInt()
        val cz = (chunkKey shr 32).toInt()
        into.getOrPut(Session.chunkKey(cx shr 5, cz shr 5)) { Region() }.chunks[(cz and 31) * 32 + (cx and 31)] = cells
    }

    /**
     * The world map's line for block ([x], [z]): its shared biome, where Xaero itself has none.
     * Null where Xaero knows the biome (its own readout names it) or nobody shared one.
     */
    @JvmStatic
    fun hoverLine(processor: MapProcessor?, x: Int, z: Int): String? = try {
        if (processor == null) null else {
            val dimensionId = processor.mapWorld?.currentDimension?.dimId?.identifier()?.toString()
            val biome = dimensionId?.let { at(it, x, z) }
            if (biome == null || BiomeHighlight.xaeroHasBiome(processor, x, z)) null
            else "Biome: ${Component.translatable(biome.identifier().toLanguageKey("biome")).string} (shared)"
        }
    } catch (e: Throwable) {
        null
    }

    /** The server address [server] was worked out for; asked every frame, so only redone when it changes. */
    private var address: String? = null

    private fun dimension(dimensionId: String): HashMap<Long, Region>? {
        val now = MapExposerClient.addressOf(Minecraft.getInstance()) ?: return null
        if (now != address || server == null) {
            dimensions.clear()
            address = now
            server = Session.folderFor(now).resolve("shared-biomes")
        }
        return dimensions.getOrPut(dimensionId) { load(server!!, dimensionId) }
    }

    private fun file(folder: Path, dimensionId: String): Path = folder.resolve(SafeFiles.name(dimensionId) + ".bin.gz")

    private fun load(folder: Path, dimensionId: String): HashMap<Long, Region> {
        val out = HashMap<Long, Region>()
        val file = file(folder, dimensionId)
        if (Files.notExists(file)) return out
        try {
            DataInputStream(GZIPInputStream(Files.newInputStream(file)).buffered()).use { input ->
                if (input.readInt() != FILE_VERSION) return out
                val names = List(input.readInt()) { ResourceKey.create(Registries.BIOME, Identifier.parse(input.readUTF())) }
                repeat(input.readInt()) {
                    val key = input.readLong()
                    put(out, key, Array(16) { input.readShort().toInt().let { i -> if (i == 0) null else names[i - 1] } })
                }
            }
        } catch (e: Throwable) {
            Log.error("Could not read the biomes shared with you for $dimensionId", e)
        }
        return out
    }

    /**
     * Writes [regions] out on the saver thread. A chunk's cells are never changed once kept, only
     * replaced, so copies of the region tables are a safe picture of this moment.
     */
    private fun save(dimensionId: String, regions: HashMap<Long, Region>) {
        val folder = server ?: return
        val snapshot = regions.map { (key, region) -> key to region.chunks.copyOf() }
        saver.execute {
            try {
                val names = LinkedHashMap<ResourceKey<Biome>, Int>()
                var count = 0
                for ((_, chunks) in snapshot) {
                    for (cells in chunks) {
                        if (cells == null) continue
                        count++
                        cells.forEach { if (it != null) names.getOrPut(it) { names.size + 1 } }
                    }
                }
                val bytes = ByteArrayOutputStream()
                DataOutputStream(GZIPOutputStream(bytes).buffered()).use { out ->
                    out.writeInt(FILE_VERSION)
                    out.writeInt(names.size)
                    names.keys.forEach { out.writeUTF(it.identifier().toString()) }
                    out.writeInt(count)
                    for ((key, chunks) in snapshot) {
                        val rx = (key and 0xFFFFFFFFL).toInt()
                        val rz = (key shr 32).toInt()
                        for (i in chunks.indices) {
                            val cells = chunks[i] ?: continue
                            out.writeLong(Session.chunkKey(rx * 32 + (i and 31), rz * 32 + (i shr 5)))
                            cells.forEach { out.writeShort(it?.let(names::get) ?: 0) }
                        }
                    }
                }
                Files.createDirectories(folder)
                SafeFiles.write(file(folder, dimensionId), bytes.toByteArray())
            } catch (e: Throwable) {
                Log.error("Could not save the biomes shared with you for $dimensionId", e)
            }
        }
    }

    private const val FILE_VERSION = 1
}
