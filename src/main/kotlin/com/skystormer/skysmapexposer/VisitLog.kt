package com.skystormer.skysmapexposer

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap
import java.nio.file.Path
import java.util.concurrent.Executor

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
        if (visits.put(chunkKey(chunkX, chunkZ), minute) != minute) changed.add(dimension)
    }

    /** The minute the chunk was last loaded, or 0 if this mod has never seen it. */
    fun lastSeen(dimension: String, chunkX: Int, chunkZ: Int): Int =
        visitsIn(dimension).get(chunkKey(chunkX, chunkZ))

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

    private fun visitsIn(dimension: String): Long2IntOpenHashMap =
        byDimension.getOrPut(dimension) { read(fileFor(dimension)) }

    private fun fileFor(dimension: String): Path = SafeFiles.recordFile(folder, "visits", dimension)

    private fun read(path: Path): Long2IntOpenHashMap {
        val visits = Long2IntOpenHashMap()
        visits.defaultReturnValue(0)
        SafeFiles.readRecord(path, MAGIC, "visit") { input -> repeat(input.readInt()) { visits.put(input.readLong(), input.readInt()) } }
        return visits
    }

    private fun write(path: Path, visits: Long2IntOpenHashMap) = SafeFiles.writeRecord(path, MAGIC) { output ->
        output.writeInt(visits.size)
        val entries = visits.long2IntEntrySet().fastIterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            output.writeLong(entry.longKey)
            output.writeInt(entry.intValue)
        }
    }

    private companion object {
        const val MAGIC = 0x4D425631 // "MBV1"
    }
}
