package com.skystormer.skysmapexposer

import it.unimi.dsi.fastutil.longs.LongOpenHashSet
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

    fun gapsIn(dimension: String): LongOpenHashSet = byDimension.getOrPut(dimension) { read(fileFor(dimension)) }

    /** Writes every dimension read or changed this session, off the client thread. */
    fun save() {
        for ((dimension, gaps) in byDimension) {
            val copy = LongOpenHashSet(gaps)
            val path = fileFor(dimension)
            io.execute { write(path, copy) }
        }
    }

    private fun fileFor(dimension: String): Path =
        folder.resolve("gaps-" + dimension.replace(Regex("[^a-zA-Z0-9._-]"), "_") + ".bin")

    private fun read(path: Path): LongOpenHashSet {
        val gaps = LongOpenHashSet()
        if (Files.notExists(path)) return gaps
        try {
            DataInputStream(BufferedInputStream(GZIPInputStream(Files.newInputStream(path)))).use { input ->
                check(input.readInt() == MAGIC) { "not a gap file" }
                input.readInt() // format version, 1 so far
                repeat(input.readInt()) { gaps.add(input.readLong()) }
            }
        } catch (e: Exception) {
            Log.error("Could not read $path; starting that dimension's gap record afresh", e)
        }
        return gaps
    }

    private fun write(path: Path, gaps: LongOpenHashSet) {
        try {
            Files.createDirectories(path.parent)
            val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
            DataOutputStream(BufferedOutputStream(GZIPOutputStream(Files.newOutputStream(temporary)))).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(1)
                output.writeInt(gaps.size)
                val each = gaps.iterator()
                while (each.hasNext()) output.writeLong(each.nextLong())
            }
            // Written aside and moved into place so a crash mid-write cannot lose the old record.
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Log.error("Could not save $path", e)
        }
    }

    private companion object {
        const val MAGIC = 0x534D4731 // "SMG1"
    }
}
