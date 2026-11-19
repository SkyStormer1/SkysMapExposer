package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.BiomeGuide
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.SafeFiles
import com.skystormer.skysmapexposer.SharedTerrain
import com.skystormer.skysmapexposer.journeymap.JourneyMapFiles.REGION
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * The biomes of shared map files added to JourneyMap's map ([JourneyMapEdits.addShared]), and the
 * biomes guessed for a download of BlueMap into it, as a download into Xaero's map guesses them.
 *
 * JourneyMap's map is only pictures; the biome under the mouse, its own and this mod's biome
 * highlight ([JourneyMapBiomes]) come from its chunk cache, which only holds chunks JourneyMap
 * mapped itself. A shared file keeps every block's biome, so they are kept here, in this mod's
 * folder for the server ([JourneyMapEdits.serverFolder]), one small file per region of a
 * dimension: a biome every 4 blocks, as the game keeps them. Wherever JourneyMap knows a biome
 * itself, its own is used; these fill in only where it knows none.
 *
 * Each file: "SMEB", format 1, then deflated: the biomes used, then each 4-block cell's (0 none,
 * else 1 + its place in that list), z by x.
 */
internal object JourneyMapImportedBiomes {

    /** A region's cells, `CELLS` square, each 0 or 1 + an index into [palette]. */
    class Grid(val palette: List<ResourceKey<Biome>>, val cells: ShortArray) {
        fun at(cellX: Int, cellZ: Int): ResourceKey<Biome>? = cells[cellZ * CELLS + cellX].toInt().takeIf { it > 0 }?.let { palette[it - 1] }
    }

    /** Lately used regions' grids by file, an empty grid where there is no file. Any thread. */
    private val cache = ConcurrentHashMap<Path, Grid>()

    /** This server's folder of [dimension]'s biomes; null outside a world. Client thread. */
    fun folder(dimension: String): Path? = JourneyMapEdits.serverFolder()?.resolve("journeymap-biomes")?.resolve(SafeFiles.name(dimension))

    private fun file(folder: Path, regionX: Int, regionZ: Int): Path = folder.resolve("$regionX,$regionZ.biomes")

    /** When region ([regionX], [regionZ])'s file was last written, or 0 if there is none. */
    fun fileTime(folder: Path, regionX: Int, regionZ: Int): Long = try {
        Files.getLastModifiedTime(file(folder, regionX, regionZ)).toMillis()
    } catch (e: Exception) {
        0L
    }

    /**
     * Keeps the biomes of a shared file's [region] at JourneyMap's region ([regionX], [regionZ]),
     * over what an earlier file left there. Off the client thread.
     */
    fun add(folder: Path, regionX: Int, regionZ: Int, region: SharedTerrain.Region) {
        val old = read(folder, regionX, regionZ)
        val palette = ArrayList(old?.palette.orEmpty())
        val indexOf = HashMap<ResourceKey<Biome>, Int>().apply { palette.forEachIndexed { i, key -> put(key, i) } }
        val cells = old?.cells?.copyOf() ?: ShortArray(CELLS * CELLS)
        var any = false
        for (cellZ in 0 until CELLS) for (cellX in 0 until CELLS) {
            // The middle of the cell, as the biome tint reads JourneyMap's own.
            val x = cellX * 4 + 2
            val z = cellZ * 4 + 2
            val chunk = region.chunks[(z shr 4) * 32 + (x shr 4)] ?: continue
            val biome = chunk.biome[(x and 15) * 16 + (z and 15)] ?: continue
            val index = indexOf.getOrPut(biome) { palette.add(biome); palette.size - 1 }
            if (index >= MAX_BIOMES) continue
            cells[cellZ * CELLS + cellX] = (index + 1).toShort()
            any = true
        }
        if (any) save(folder, regionX, regionZ, palette, cells)
    }

    private fun save(folder: Path, regionX: Int, regionZ: Int, palette: List<ResourceKey<Biome>>, cells: ShortArray) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(FORMAT)
            DataOutputStream(DeflaterOutputStream(out)).use { body ->
                body.writeShort(palette.size)
                for (key in palette) body.writeUTF(key.identifier().toString())
                for (cell in cells) body.writeShort(cell.toInt())
            }
        }
        val path = file(folder, regionX, regionZ)
        Files.createDirectories(folder)
        SafeFiles.write(path, bytes.toByteArray())
        cache[path] = Grid(palette, cells)
    }

    /**
     * Keeps [guess]'s biomes ([BiomeGuide], from a download of BlueMap) for region ([regionX],
     * [regionZ]), where [drawn] (block x, z in the region) and no biome is kept yet: a shared map
     * file's real biomes are never written over by a guess. Off the client thread.
     */
    fun addGuess(folder: Path, regionX: Int, regionZ: Int, guess: BiomeGuide.Guess, drawn: (Int, Int) -> Boolean) {
        val old = read(folder, regionX, regionZ)
        val palette = ArrayList(old?.palette.orEmpty())
        val indexOf = HashMap<ResourceKey<Biome>, Int>().apply { palette.forEachIndexed { i, key -> put(key, i) } }
        val cells = old?.cells?.copyOf() ?: ShortArray(CELLS * CELLS)
        val keys = HashMap<String, ResourceKey<Biome>?>()
        var any = false
        for (cellZ in 0 until CELLS) for (cellX in 0 until CELLS) {
            val index = cellZ * CELLS + cellX
            if (cells[index] > 0) continue
            val x = cellX * 4 + 2
            val z = cellZ * 4 + 2
            if (!drawn(x, z)) continue
            val name = guess.at(x, z) ?: continue
            val biome = keys.getOrPut(name) { runCatching { ResourceKey.create(Registries.BIOME, Identifier.parse(name)) }.getOrNull() } ?: continue
            val at = indexOf.getOrPut(biome) { palette.add(biome); palette.size - 1 }
            if (at >= MAX_BIOMES) continue
            cells[index] = (at + 1).toShort()
            any = true
        }
        if (any) save(folder, regionX, regionZ, palette, cells)
    }

    /** Region ([regionX], [regionZ])'s grid, or null where no shared file left any biomes. Any thread; reads the file the first time. */
    fun read(folder: Path, regionX: Int, regionZ: Int): Grid? {
        val path = file(folder, regionX, regionZ)
        val grid = cache[path] ?: load(path).also {
            if (cache.size >= MAX_CACHED) cache.clear()
            cache[path] = it
        }
        return grid.takeIf { it.palette.isNotEmpty() }
    }

    private fun load(path: Path): Grid {
        if (!Files.isRegularFile(path)) return EMPTY
        return try {
            DataInputStream(Files.newInputStream(path).buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != FORMAT) return EMPTY
                DataInputStream(InflaterInputStream(input)).use { body ->
                    val count = body.readUnsignedShort()
                    if (count > MAX_BIOMES) return EMPTY
                    val palette = List(count) { ResourceKey.create(Registries.BIOME, Identifier.parse(body.readUTF())) }
                    val cells = ShortArray(CELLS * CELLS) { body.readShort() }
                    if (cells.any { it < 0 || it > count }) return EMPTY
                    Grid(palette, cells)
                }
            }
        } catch (e: Exception) {
            Log.warn("Could not read the shared map biomes in {}: {}", path.fileName, e.toString())
            EMPTY
        }
    }

    /** The biome a shared file gave block ([x], [z]) of [dimension], or null. Client thread. */
    fun biomeAt(dimension: String, x: Int, z: Int): ResourceKey<Biome>? {
        val folder = folder(dimension) ?: return null
        val grid = read(folder, Math.floorDiv(x, REGION), Math.floorDiv(z, REGION)) ?: return null
        return grid.at(Math.floorMod(x, REGION) / 4, Math.floorMod(z, REGION) / 4)
    }

    /**
     * For JourneyMap's own line under the mouse, where it knows no biome itself
     * (`SnapshotBlockDataAccessorMixin`): the biome a shared file gave [pos], or null.
     */
    @JvmStatic
    fun biomeFor(dimension: ResourceKey<Level>, pos: BlockPos): Biome? {
        val key = biomeAt(dimension.identifier().toString(), pos.x, pos.z) ?: return null
        return Minecraft.getInstance().level?.registryAccess()?.lookupOrThrow(Registries.BIOME)?.getValue(key)
    }

    /** Leaving a world: another server's biomes are in another folder. */
    fun forget() = cache.clear()

    const val CELLS = REGION / 4
    private const val MAGIC = 0x534D4542 // "SMEB"
    private const val FORMAT = 1
    private const val MAX_BIOMES = 4096
    private const val MAX_CACHED = 256
    private val EMPTY = Grid(emptyList(), ShortArray(0))
}
