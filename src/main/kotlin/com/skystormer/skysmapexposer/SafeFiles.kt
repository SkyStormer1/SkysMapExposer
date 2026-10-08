package com.skystormer.skysmapexposer

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writing a file so that a crash or a full disk part-way through never leaves it half written: the
 * new contents go to a file beside it, which is then moved over it in one step.
 */
object SafeFiles {

    private val UNSAFE = Regex("[^A-Za-z0-9_-]")

    /** [text] as part of a file name: anything but letters, digits, `_` and `-` becomes `_`. */
    fun name(text: String): String = text.replace(UNSAFE, "_")

    fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.write(temporary, bytes)
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun writeString(path: Path, text: String) = write(path, text.toByteArray(Charsets.UTF_8))
}
