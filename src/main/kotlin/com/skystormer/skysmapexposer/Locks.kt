package com.skystormer.skysmapexposer

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ExecutorService

/**
 * The players you have locked on to on this server: the ones whose pin follows them like a waypoint
 * that moves, and who get a head over them in the world.
 *
 * Kept in `<game>/skysmapexposer/<server>/locks.json` so a lock survives a relog — you unlock
 * someone when you mean to, not by leaving. Read on the client thread, written on the session's IO
 * thread like the visit log.
 */
class Locks(private val file: Path, private val io: ExecutorService) {

    private val locked = LinkedHashSet<UUID>()

    init {
        load()
    }

    fun isLocked(uuid: UUID): Boolean = uuid in locked

    /** Every locked player, whether or not they are online. */
    fun all(): Set<UUID> = locked

    val size: Int get() = locked.size

    /** Locks or unlocks [uuid]. Returns whether it is locked afterwards. */
    fun set(uuid: UUID, on: Boolean): Boolean {
        val changed = if (on) locked.add(uuid) else locked.remove(uuid)
        if (changed) save()
        return on
    }

    fun toggle(uuid: UUID): Boolean = set(uuid, uuid !in locked)

    fun clear() {
        if (locked.isEmpty()) return
        locked.clear()
        save()
    }

    private fun load() {
        try {
            if (Files.notExists(file)) return
            JsonParser.parseString(Files.readString(file)).asJsonArray.forEach { element ->
                runCatching { UUID.fromString(element.asString) }.getOrNull()?.let(locked::add)
            }
        } catch (e: Exception) {
            Log.warn("Could not read {}; no players are locked: {}", file, e.toString())
        }
    }

    private fun save() {
        val snapshot = locked.toList()
        io.execute {
            try {
                Files.createDirectories(file.parent)
                val array = JsonArray()
                snapshot.forEach { array.add(it.toString()) }
                Files.writeString(file, array.toString())
            } catch (e: Exception) {
                Log.error("Could not write $file", e)
            }
        }
    }
}
