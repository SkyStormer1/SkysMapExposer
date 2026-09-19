package com.skystormer.skysmapexposer

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.Executor
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * When you last had each chunk loaded, per dimension, for one server.
 *
 * This is what "my map data is old" means. Xaero redraws a chunk whenever the server sends it to
 * you, so the last time a chunk was loaded is the date of what your map shows there. Xaero does not
 * keep that date itself, so this does, from the moment the mod is installed; anything older falls
 * back to the date on Xaero's region file (see [RegionAges]).
 *
 * Only touched from the client thread, which is also the thread the map screen draws on.
 */
class VisitLog(private val folder: Path, private val io: Executor) {

    private val byDimension = HashMap<String, Long2IntOpenHashMap>()
    private val changed = HashSet<String>()

    fun record(dimension: String, chunkX: Int, chunkZ: Int, minute: Int) {
        val visits = visitsIn(dimension)
        if (visits.put(key(chunkX, chunkZ), minute) != minute) changed.add(dimension)
    }

    /** The minute the chunk was last loaded, or 0 if this mod has never seen it. */
    fun lastSeen(dimension: String, chunkX: Int, chunkZ: Int): Int =
        visitsIn(dimension).get(key(chunkX, chunkZ))

    fun count(dimension: String): Int = visitsIn(dimension).size

    /** Writes every dimension that has changed, off the client thread. */
    fun save() {
        for (dimension in changed) {
            val copy = Long2IntOpenHashMap(visitsIn(dimension))
            val path = fileFor(dimension)
            io.execute { write(path, copy) }
        }
        changed.clear()
    }

    private fun key(chunkX: Int, chunkZ: Int): Long =
        (chunkX.toLong() and 0xFFFFFFFFL) or (chunkZ.toLong() shl 32)

    private fun visitsIn(dimension: String): Long2IntOpenHashMap =
        byDimension.getOrPut(dimension) { read(fileFor(dimension)) }

    private fun fileFor(dimension: String): Path =
        folder.resolve("visits-" + dimension.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".bin")

    private fun read(path: Path): Long2IntOpenHashMap {
        val visits = Long2IntOpenHashMap()
        visits.defaultReturnValue(0)
        if (Files.notExists(path)) return visits
        try {
            DataInputStream(BufferedInputStream(GZIPInputStream(Files.newInputStream(path)))).use { input ->
                check(input.readInt() == MAGIC) { "not a visit file" }
                input.readInt() // format version, 1 so far
                repeat(input.readInt()) { visits.put(input.readLong(), input.readInt()) }
            }
        } catch (e: Exception) {
            Log.error("Could not read $path; starting that dimension's visit record afresh", e)
        }
        return visits
    }

    private fun write(path: Path, visits: Long2IntOpenHashMap) {
        try {
            Files.createDirectories(path.parent)
            val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(Files.newOutputStream(temporary)))).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(1)
                output.writeInt(visits.size)
                val entries = visits.long2IntEntrySet().fastIterator()
                while (entries.hasNext()) {
                    val entry = entries.next()
                    output.writeLong(entry.longKey)
                    output.writeInt(entry.intValue)
                }
            }
            // Written aside and moved into place so a crash mid-write cannot lose the old record.
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Log.error("Could not save $path", e)
        }
    }

    private companion object {
        const val MAGIC = 0x4D425631 // "MBV1"
    }
}
