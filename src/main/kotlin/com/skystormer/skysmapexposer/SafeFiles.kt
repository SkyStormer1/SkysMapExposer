package com.skystormer.skysmapexposer

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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
        // A temporary file of its own for every write, so two writes of the same file at once
        // (a tile loaded twice) never write into each other's.
        val temporary = Files.createTempFile(path.parent, path.fileName.toString() + ".", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun writeString(path: Path, text: String) = write(path, text.toByteArray(Charsets.UTF_8))

    /** A gzipped file of whatever [content] writes. */
    /**
     * Reads a small record file of this mod's ([writeRecord]): [magic], a format number, then what
     * [body] reads. A missing file reads as nothing; a damaged one is logged and read no further,
     * so the [what] record starts afresh.
     */
    fun readRecord(path: Path, magic: Int, what: String, body: (DataInputStream) -> Unit) {
        if (Files.notExists(path)) return
        try {
            DataInputStream(BufferedInputStream(GZIPInputStream(Files.newInputStream(path)))).use { input ->
                check(input.readInt() == magic) { "not a $what file" }
                input.readInt() // format version, 1 so far
                body(input)
            }
        } catch (e: Exception) {
            Log.error("Could not read $path; starting that dimension's $what record afresh", e)
        }
    }

    /** Writes a record file for [readRecord]: [magic], format 1, then what [body] writes; a failure is logged. */
    fun writeRecord(path: Path, magic: Int, body: (DataOutputStream) -> Unit) {
        try {
            writeGzip(path) { output ->
                output.writeInt(magic)
                output.writeInt(1)
                body(output)
            }
        } catch (e: Exception) {
            Log.error("Could not save $path", e)
        }
    }

    fun writeGzip(path: Path, content: (DataOutputStream) -> Unit) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use(content)
        write(path, bytes.toByteArray())
    }
}
