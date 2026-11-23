package com.skystormer.skysmapexposer

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
import java.nio.file.Path
import java.util.concurrent.Executor

/**
 * The chunks Xaero was last seen to have nothing for, per dimension, for one server, kept on disk.
 *
 * Xaero can only say whether it has mapped a chunk while it has that part of the map open, which
 * it does around you and when you zoom in, but not when you zoom out. So what it said is kept, and
 * kept across sessions, or the unmapped ring at the edge of where you have been would show black
 * again after rejoining.
 *
 * Only touched from the client thread.
 */
class GapStore(private val folder: Path, private val io: Executor) {

    private val byDimension = HashMap<String, LongOpenHashSet>()

    /** What each dimension's record held when last read or written, so an unchanged one is not written again. */
    private val saved = HashMap<String, LongOpenHashSet>()

    fun gapsIn(dimension: String): LongOpenHashSet = byDimension.getOrPut(dimension) {
        read(fileFor(dimension)).also { saved[dimension] = LongOpenHashSet(it) }
    }

    /** Writes every dimension changed since it was read or last written, off the client thread. */
    fun save() {
        for ((dimension, gaps) in byDimension) {
            if (gaps == saved[dimension]) continue
            val copy = LongOpenHashSet(gaps)
            saved[dimension] = copy
            val path = fileFor(dimension)
            io.execute { write(path, copy) }
        }
    }

    private fun fileFor(dimension: String): Path = SafeFiles.recordFile(folder, "gaps", dimension)

    private fun read(path: Path): LongOpenHashSet {
        val gaps = LongOpenHashSet()
        SafeFiles.readRecord(path, MAGIC, "gap") { input -> repeat(input.readInt()) { gaps.add(input.readLong()) } }
        return gaps
    }

    private fun write(path: Path, gaps: LongOpenHashSet) = SafeFiles.writeRecord(path, MAGIC) { output ->
        output.writeInt(gaps.size)
        val each = gaps.iterator()
        while (each.hasNext()) output.writeLong(each.nextLong())
    }

    private companion object {
        const val MAGIC = 0x534D4731 // "SMG1"
    }
}
