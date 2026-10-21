package com.skystormer.skysmapexposer

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * The tile cache on disk (`skysmapexposer/<server>/tiles`): no size cap, tidied on joining, and
 * measured or cleared for the settings. All of it is file walking, so it runs off the client thread.
 */
object TileCache {

    /** @property tiles tiles with a picture on disk. */
    class Size(val bytes: Long, val tiles: Int) {
        override fun toString() = "${megabytes(bytes)} · %,d tiles".format(tiles)
    }

    /** Leftover `.tmp` files deleted (from a game closed part way through a write); and its size. */
    fun tidy(folder: Path): Size {
        var bytes = 0L
        var tiles = 0
        walk(folder) { file, size ->
            val name = file.fileName.toString()
            if (name.endsWith(".tmp")) {
                Files.deleteIfExists(file)
            } else {
                bytes += size
                if (name.endsWith(".smet") || name.endsWith(".png")) tiles++
            }
        }
        return Size(bytes, tiles)
    }

    /** How much [folder] holds. */
    fun measure(folder: Path): Size {
        var bytes = 0L
        var tiles = 0
        walk(folder) { file, size ->
            bytes += size
            val name = file.fileName.toString()
            if (name.endsWith(".smet") || name.endsWith(".png")) tiles++
        }
        return Size(bytes, tiles)
    }

    /** Every server's tile cache under [root] (`skysmapexposer/`) together. */
    fun measureAll(root: Path): Size {
        if (!Files.isDirectory(root)) return Size(0, 0)
        var bytes = 0L
        var tiles = 0
        Files.newDirectoryStream(root).use { servers ->
            for (server in servers) {
                val size = measure(server.resolve("tiles"))
                bytes += size.bytes
                tiles += size.tiles
            }
        }
        return Size(bytes, tiles)
    }

    /** Deletes [folder] and everything in it. */
    @Throws(IOException::class)
    fun clear(folder: Path) {
        if (!Files.exists(folder)) return
        Files.walkFileTree(folder, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    fun megabytes(bytes: Long): String =
        if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1024.0 * 1024 * 1024)) else "%,d MB".format(bytes / (1024 * 1024))

    private fun walk(folder: Path, each: (Path, Long) -> Unit) {
        if (!Files.isDirectory(folder)) return
        Files.walkFileTree(folder, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                each(file, attrs.size())
                return FileVisitResult.CONTINUE
            }

            // A file deleted or locked while walking is passed over.
            override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
        })
    }
}
