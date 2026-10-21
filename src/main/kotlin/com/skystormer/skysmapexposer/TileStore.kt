package com.skystormer.skysmapexposer

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.renderer.texture.DynamicTexture
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.BitSet
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * BlueMap tiles: fetched on demand, kept on disk, decoded off the client thread, and turned into
 * textures on it.
 *
 * Only the finest tiles are ever drawn. Zoomed out, each is shrunk here by averaging blocks
 * together ([requestFactor]), the way Xaero shrinks its own map, rather than switching to
 * BlueMap's coarser tiles, which are rendered differently and would change the picture as you
 * zoom. The coarsest tiles are still fetched, but only as an index of where BlueMap has anything,
 * so that zooming out does not ask the server for thousands of empty tiles.
 *
 * Each tile also carries the one date BlueMap does not give: when its current picture first
 * appeared. BlueMap's server sends no Last-Modified header, so a tile is dated by the first time
 * this mod saw its current contents. A tile fetched for the first time is therefore dated "now",
 * which is correct as far as anything here can tell: it is at least as new as any map you made
 * before installing the mod. When a later fetch brings different contents, the date moves on.
 *
 * Tiles are kept on disk ready to draw ([TileFile]): shaded and shrunk to every zoom factor once,
 * when they arrive, so loading one is a read and an inflate. Older caches of BlueMap's own PNGs are
 * converted the first time each tile loads, without downloading it again.
 *
 * Tiles on screen are asked about every [Config.tileCheckMinutes] ("has it changed?", which costs
 * nothing when the server can answer "not modified") and downloaded whole every
 * [Config.tileHardRefreshHours] whatever the checks said; empty tiles too, so newly rendered areas
 * appear. Zoomed far out nothing is asked about: there are thousands of tiles on screen, and they
 * still load from the cache.
 *
 * Nothing is kept that does not decode: a reply is decoded before it is saved, and a saved tile
 * that no longer reads is deleted and fetched again. A tile is only taken as gone from BlueMap on
 * its word ([BlueMap.TileReply]), and one that had a picture only after two checks say so.
 *
 * Tiles stay in memory while either map has drawn them in the last few minutes, within a budget
 * of video memory ([Eviction]), so closing and reopening the world map finds them still there.
 */
class TileStore(
    private val blueMap: BlueMap,
    private val folder: Path,
    private val workers: ExecutorService,
) {

    data class Key(val map: String, val lod: Int, val x: Int, val z: Int)

    enum class State { LOADING, READY, EMPTY, FAILED }

    /**
     * @property blocksPerPixel of the image as BlueMap sends it.
     * @property tileSize pixels along a side, not counting BlueMap's one-pixel overlap.
     */
    class Tile(val key: Key, val blocksPerPixel: Int, val tileSize: Int) {
        @Volatile
        var state: State = State.LOADING

        /** When the current picture was first seen, in [Clock] minutes. Set before [state] turns READY. */
        var contentMinute: Int = 0

        /** Side of BlueMap's image: `tileSize + 1`. */
        var imageSize: Int = 0

        /** Which image pixels BlueMap has anything at. */
        @Volatile
        var present: BitSet? = null

        /** For an index tile: per finest tile inside it, 0 unknown, 1 nothing there, 2 something. */
        var finerPresence: ByteArray? = null

        /**
         * Textures by blocks per pixel. The world map and the minimap usually want different ones
         * and are drawn in the same frames, so each keeps its own.
         */
        val textures = HashMap<Int, DynamicTexture>()

        /** Shrunk pictures made on a worker, waiting to become [textures]. */
        val pending = ConcurrentHashMap<Int, IntArray>()

        /** Factors a worker is already making or has made, so each is only asked for once. */
        val requested: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        /** When the server was last asked about this tile (epoch milliseconds). */
        @Volatile
        var checkedAt: Long = 0

        /** When it was last downloaded whole, rather than only asked about. */
        @Volatile
        var hardAt: Long = 0

        @Volatile
        var refreshing: Boolean = false

        @Volatile
        var failedAt: Long = 0

        /** The drawing plan for this tile; rebuilt by [Backfill] from time to time. */
        var mask: Backfill.Mask? = null

        /** While this tile is on its way: where a coarser tile stands in for it, and which level that is. */
        var standIn: Backfill.Mask? = null
        var standInLod: Int = 0

        /**
         * The sea floor from BlueMap's 3D tiles ([SeaFloor]), `tileSize` square: drawn over the
         * picture wherever it is not 0. Null with the setting off or nothing in yet.
         */
        @Volatile
        var floor: IntArray? = null

        /** When either map last drew it (epoch milliseconds). */
        var lastUsed: Long = 0

        /** Video memory its [textures] take. */
        var bytes: Long = 0

        fun hasDataAt(pixelX: Int, pixelZ: Int): Boolean =
            present?.get(pixelZ * imageSize + pixelX) ?: false
    }

    private val tiles = HashMap<Key, Tile>(256)

    /** The keys of [tiles], for [prepare] on another thread: tiles the maps hold check themselves. */
    private val inMemory: MutableSet<Key> = ConcurrentHashMap.newKeySet()

    /** Loads and checks under way for the maps, which background preparation waits for. */
    val busy = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Anything downloaded before this is downloaded again as it is drawn, whatever its age. Set by
     * `/mapexposer refresh` and the map bar's refresh button.
     */
    @Volatile
    var refetchBefore: Long = 0

    val size: Int get() = tiles.size

    fun countIn(state: State): Int = tiles.values.count { it.state == state }

    /** Video memory all the textures take, for the settings screen. */
    var heldBytes: Long = 0
        private set

    /** How many zoom factors coarser than asked tiles are drawn, while those on screen are over the budget. */
    var coarser: Int = 0
        private set

    /** Tiles whose failure has been logged, so each is logged once. */
    private val logged: MutableSet<Key> = ConcurrentHashMap.newKeySet()

    private fun warnOnce(key: Key, message: String, error: Throwable) {
        if (logged.add(key)) Log.warn("{} {}: {} (said once per tile)", message, key, error.toString())
    }

    /**
     * The tile, starting its load if it is not in memory. Unless [mayCheck] is false (zoomed far
     * out), a tile due for a check is asked about too; BlueMap's coarser tiles always are, being
     * few. Client thread only.
     */
    fun get(key: Key, blocksPerPixel: Int, tileSize: Int, mayCheck: Boolean = true): Tile {
        var tile = tiles[key]
        val now = System.currentTimeMillis()
        if (tile == null || (tile.state == State.FAILED && now - tile.failedAt > RETRY_MS)) {
            tile?.let(::release)
            tile = Tile(key, blocksPerPixel, tileSize)
            tiles[key] = tile
            inMemory.add(key)
            val loading = tile
            busy.incrementAndGet()
            workers.execute { try { load(loading) } finally { busy.decrementAndGet() } }
        } else if ((tile.state == State.READY || tile.state == State.EMPTY) && !tile.refreshing &&
            (mayCheck || key.lod > 1) && Schedule.due(tile.checkedAt, tile.hardAt, refetchBefore, now) != Schedule.Due.NO
        ) {
            tile.refreshing = true
            val refreshing = tile
            busy.incrementAndGet()
            workers.execute { try { refresh(refreshing) } finally { busy.decrementAndGet() } }
        } else if (tile.state != State.READY && tile.textures.isNotEmpty()) {
            // Gone from BlueMap, or no longer readable: its old picture goes too.
            release(tile)
        }
        tile.lastUsed = now
        return tile
    }

    /**
     * Asks for the tile's texture at [factor] blocks per pixel. Whatever texture it already has
     * keeps being drawn until the new one is ready, so zooming never leaves a hole. A picture that
     * cannot be read back marks the tile failed, to be loaded afresh in a minute, rather than being
     * asked for again every frame.
     */
    fun requestFactor(tile: Tile, factor: Int) {
        if (tile.state != State.READY || !tile.requested.add(factor)) return
        workers.execute {
            try {
                val floor = tile.floor
                tile.pending[factor] = (if (floor == null) TileFile.pixels(tileFile(tile.key), factor) else withFloor(tile, floor, factor))
                    ?: throw IOException("the tile file has no 1:$factor picture")
            } catch (e: Exception) {
                warnOnce(tile.key, "Could not read BlueMap tile back from disk; loading it again in a minute", e)
                tile.requested.remove(factor)
                tile.failedAt = System.currentTimeMillis()
                tile.state = State.FAILED
            }
        }
    }

    /**
     * The tile's texture, first swapping in a newly shrunk one if there is one and [mayUpload]
     * (uploads are rationed per frame). Client thread only.
     */
    fun textureOf(tile: Tile, factor: Int, mayUpload: Boolean): GpuTextureView? {
        val pixels = tile.pending[factor]
        if (pixels != null && mayUpload) {
            tile.pending.remove(factor)
            val side = tile.tileSize / factor
            val image = NativeImage(side, side, false)
            for (z in 0 until side) {
                for (x in 0 until side) image.setPixel(x, z, pixels[z * side + x] or TilePixels.OPAQUE)
            }
            close(tile, factor)
            tile.textures[factor] = DynamicTexture({ "skysmapexposer ${tile.key} at 1:$factor" }, image)
            account(tile, textureBytes(side))
            // Three sizes at most: the minimap's, the world map's, and the one it is zooming to;
            // and over the memory budget, only this one.
            val most = if (coarser > 0) 1 else 3
            if (tile.textures.size > most) {
                tile.textures.keys.filter { it != factor && (coarser > 0 || it != 1) }.take(tile.textures.size - most).forEach { old ->
                    close(tile, old)
                    tile.requested.remove(old)
                }
            }
        }
        // Until the wanted size is ready, any size will do: better blurry than a hole.
        return (tile.textures[factor] ?: tile.textures.values.firstOrNull())?.textureView
    }

    /** [tile]'s picture at a block a pixel, `tileSize` square, as it is drawn; null if it has none. Blocking. */
    fun picture(tile: Tile): IntArray? = TileFile.pixels(tileFile(tile.key), 1)

    /** The picture at [factor] with the sea floor [floor] in, where the picture has anything. */
    private fun withFloor(tile: Tile, floor: IntArray, factor: Int): IntArray? {
        val pixels = picture(tile) ?: return null
        for (i in pixels.indices) if (floor[i] != 0 && pixels[i] != 0) pixels[i] = floor[i]
        return if (factor == 1) pixels else TilePixels.shrink(pixels, tile.tileSize, tile.tileSize, factor)
    }

    /** Whether [tile] has a texture of [factor] waiting to be uploaded. */
    fun hasUploadWaiting(tile: Tile, factor: Int): Boolean = tile.pending.containsKey(factor)

    private var trimmedAt = 0L

    /**
     * Drops tiles neither map has drawn for a while, and the least recently drawn ones while their
     * textures are over [budget] bytes; never one on screen or on its way ([Eviction]). Runs at
     * most once a second, however often it is called.
     */
    fun trim(budget: Long, now: Long = System.currentTimeMillis()) {
        if (now - trimmedAt < 1000L) return
        trimmedAt = now
        val result = Eviction.choose(
            tiles.values.map { Eviction.Entry(it.key, it.bytes, it.lastUsed, it.state == State.LOADING || it.refreshing) }, now, budget,
        )
        for (key in result.drop) {
            tiles.remove(key)?.let(::release)
            inMemory.remove(key)
        }
        coarser = Eviction.coarser(coarser, result.inViewOverBudget, result.kept, budget)
    }

    fun clear() {
        tiles.values.forEach(::release)
        tiles.clear()
        inMemory.clear()
    }

    private fun release(tile: Tile) {
        for (factor in tile.textures.keys.toList()) close(tile, factor)
        tile.pending.clear()
        tile.requested.clear()
    }

    private fun close(tile: Tile, factor: Int) {
        val texture = tile.textures.remove(factor) ?: return
        texture.close()
        account(tile, -textureBytes(tile.tileSize / factor))
    }

    private fun account(tile: Tile, bytes: Long) {
        tile.bytes += bytes
        heldBytes += bytes
    }

    // The map id came from the server and becomes a folder name, so only ever a safe one.
    private fun base(key: Key): Path = folder.resolve("${if (BlueMap.isMapId(key.map)) key.map else "_"}/${key.lod}")

    private fun tileFile(key: Key): Path = base(key).resolve("${key.x}_${key.z}.smet")

    /** BlueMap's own picture, as caches before the tile files kept it; converted when found. */
    private fun oldPicture(key: Key): Path = base(key).resolve("${key.x}_${key.z}.png")

    private fun metaOf(key: Key): Path = base(key).resolve("${key.x}_${key.z}.meta")

    // ---- worker threads from here on ----

    /** When each map's settings last loaded, to believe a 404 by. */
    private val settingsLoadedAt = ConcurrentHashMap<String, Long>()

    /**
     * BlueMap's answer for [key], only asking whether it changed when [etag] or [lastModified] is
     * given. A 404 counts as "nothing there" only while the map's settings still load: otherwise
     * the address or the server is wrong, and it is an error.
     */
    private fun fetch(key: Key, etag: String? = null, lastModified: String? = null): BlueMap.TileReply {
        val reply = blueMap.fetchTile(key.map, key.lod, key.x, key.z, etag, lastModified)
        if (reply != BlueMap.TileReply.NotFound) return reply
        val now = System.currentTimeMillis()
        if (now - (settingsLoadedAt[key.map] ?: 0L) > SETTINGS_TRUST_MS) {
            try {
                blueMap.layout(key.map)
            } catch (e: IOException) {
                throw IOException("BlueMap answers 404 and its settings for '${key.map}' do not load either (${e.message})", e)
            }
            settingsLoadedAt[key.map] = now
        }
        return BlueMap.TileReply.Empty
    }

    private fun load(tile: Tile) {
        val key = tile.key
        try {
            val meta = readMeta(metaOf(key))
            val fetchedAt = meta.getProperty("fetched")?.toLongOrNull() ?: 0L
            // Caches from before checks were cheap were downloaded whole every time.
            val hardAt = meta.getProperty("hard")?.toLongOrNull() ?: fetchedAt
            val contentMinute = meta.getProperty("since")?.toIntOrNull() ?: 0

            // What is on disk is shown at once rather than waiting on the server, and asked about
            // once it is due ([get]).
            if (meta.getProperty("empty") == "true") {
                tile.checkedAt = fetchedAt
                tile.hardAt = hardAt
                tile.state = State.EMPTY
                return
            }
            if (fromDisk(tile, meta, contentMinute)) {
                tile.checkedAt = fetchedAt
                tile.hardAt = hardAt
                return
            }
            download(tile, meta, System.currentTimeMillis())
        } catch (e: Exception) {
            tile.failedAt = System.currentTimeMillis()
            tile.state = State.FAILED
            warnOnce(key, "Could not load BlueMap tile; trying again in a minute", e)
        }
    }

    /**
     * Readies [tile] from its file on disk, converting an old PNG cache first. False if there is
     * nothing usable on disk; anything that does not read is deleted, to be downloaded again.
     */
    private fun fromDisk(tile: Tile, meta: Properties, contentMinute: Int): Boolean {
        val key = tile.key
        val file = tileFile(key)
        if (Files.exists(file)) {
            try {
                val (header, present) = TileFile.presence(file)
                val hash = meta.getProperty("hash")
                if (header.tileSize != tile.tileSize) throw IOException("made for ${header.tileSize}-block tiles, not ${tile.tileSize}")
                if (hash != null && header.hash != hash) throw IOException("made from another picture than the one last downloaded")
                tile.imageSize = header.imageSize
                ready(tile, present, contentMinute)
                return true
            } catch (e: Exception) {
                warnOnce(key, "A saved BlueMap tile does not read; fetching it again", e)
                Files.deleteIfExists(file)
            }
        }
        val old = oldPicture(key)
        if (Files.exists(old)) {
            try {
                val bytes = Files.readAllBytes(old)
                build(tile, bytes, meta.getProperty("hash") ?: sha1(bytes), contentMinute)
                return true
            } catch (e: Exception) {
                warnOnce(key, "A saved BlueMap tile does not read; fetching it again", e)
            } finally {
                Files.deleteIfExists(old)
            }
        }
        return false
    }

    /**
     * Decodes BlueMap's picture, writes the tile file for it, and readies [tile]. Decoded before
     * anything is saved: a junk reply (a proxy's page, a cut-off file) fails here, and nothing of
     * it is kept.
     */
    private fun build(tile: Tile, bytes: ByteArray, hash: String, contentMinute: Int) {
        val colours = TilePixels.decode(bytes, tile.tileSize, tile.blocksPerPixel)
        val imageSize = tile.tileSize + 1
        SafeFiles.write(tileFile(tile.key), TileFile.encode(hash, colours, imageSize, tile.tileSize, TilePixels.factorsFor(tile.tileSize)))
        tile.imageSize = imageSize
        ready(tile, TilePixels.presence(colours), contentMinute)
    }

    /** A whole download, as for a tile not on disk. */
    private fun download(tile: Tile, meta: Properties, now: Long) {
        when (val reply = fetch(tile.key)) {
            is BlueMap.TileReply.Image -> changed(tile, meta, reply, now)
            is BlueMap.TileReply.NotModified -> throw IOException("BlueMap answered 304 to a plain request")
            else -> markEmpty(tile, meta, now)
        }
        tile.checkedAt = now
        tile.hardAt = now
    }

    /**
     * Takes BlueMap's new picture for [tile] if it differs from the last one, dating it now. An
     * unchanged picture changes nothing but the dates.
     */
    private fun changed(tile: Tile, meta: Properties, reply: BlueMap.TileReply.Image, now: Long) {
        val hash = sha1(reply.bytes)
        var minute = meta.getProperty("since")?.toIntOrNull() ?: 0
        val same = hash == meta.getProperty("hash") && tile.state == State.READY
        if (!same) {
            if (hash != meta.getProperty("hash") || minute == 0) minute = Clock.nowMinutes()
            build(tile, reply.bytes, hash, minute)
            // Every size of the old picture is out of date; they are remade as they are drawn.
            tile.requested.clear()
        }
        meta.remove("empty")
        meta.remove("emptyAt")
        meta.setProperty("hash", hash)
        meta.setProperty("since", minute.toString())
        meta.setProperty("fetched", now.toString())
        meta.setProperty("hard", now.toString())
        if (reply.etag != null) meta.setProperty("etag", reply.etag) else meta.remove("etag")
        if (reply.lastModified != null) meta.setProperty("modified", reply.lastModified) else meta.remove("modified")
        writeMeta(metaOf(tile.key), meta)
    }

    /**
     * Asks the server whether a tile has changed: only asks, when the server gave something to ask
     * by and no whole download is due; otherwise downloads it and compares. A change replaces the
     * picture at once. A tile BlueMap now has nothing for loses its picture on the second check
     * that says so; an empty tile that has something now gets it. Every way out counts as checked.
     */
    private fun refresh(tile: Tile) {
        val key = tile.key
        val now = System.currentTimeMillis()
        val hard = Schedule.due(tile.checkedAt, tile.hardAt, refetchBefore, now) == Schedule.Due.DOWNLOAD
        try {
            val meta = readMeta(metaOf(key))
            val reply = if (hard || tile.state != State.READY) fetch(key)
            else fetch(key, meta.getProperty("etag"), meta.getProperty("modified"))
            when (reply) {
                is BlueMap.TileReply.Image -> changed(tile, meta, reply, now)
                is BlueMap.TileReply.NotModified -> {
                    meta.setProperty("fetched", now.toString())
                    meta.remove("emptyAt")
                    writeMeta(metaOf(key), meta)
                }
                else -> {
                    if (tile.state != State.READY) {
                        meta.setProperty("fetched", now.toString())
                        meta.setProperty("hard", now.toString())
                        writeMeta(metaOf(key), meta)
                        return
                    }
                    val firstSaid = meta.getProperty("emptyAt")?.toLongOrNull()
                    if (firstSaid == null || firstSaid >= now) {
                        // Once could be BlueMap re-rendering; the picture stays until the next check.
                        meta.setProperty("emptyAt", now.toString())
                        meta.setProperty("fetched", now.toString())
                        writeMeta(metaOf(key), meta)
                        return
                    }
                    markEmpty(tile, meta, now)
                }
            }
        } catch (e: Exception) {
            warnOnce(key, "Could not check BlueMap tile for changes", e)
        } finally {
            tile.checkedAt = now
            // Asked for a whole download and got one, or BlueMap's word that there is nothing.
            if (hard) tile.hardAt = now
            tile.refreshing = false
        }
    }

    /**
     * For background preparation ([MapPrep]): makes sure [key] is on disk, ready to draw and within
     * its schedule, without keeping it in memory. A tile on disk is only asked about when a whole
     * download is due, or a check is and the server can answer it without one. True if the server
     * was asked. Blocking.
     */
    fun prepare(key: Key, blocksPerPixel: Int, tileSize: Int): Boolean {
        if (key in inMemory) return false
        val tile = Tile(key, blocksPerPixel, tileSize)
        return try {
            val meta = readMeta(metaOf(key))
            val fetchedAt = meta.getProperty("fetched")?.toLongOrNull() ?: 0L
            val hardAt = meta.getProperty("hard")?.toLongOrNull() ?: fetchedAt
            val empty = meta.getProperty("empty") == "true"
            val onDisk = empty || fromDisk(tile, meta, meta.getProperty("since")?.toIntOrNull() ?: 0)
            val now = System.currentTimeMillis()
            if (!onDisk) {
                download(tile, meta, now)
                return true
            }
            val cheap = meta.getProperty("etag") != null || meta.getProperty("modified") != null
            when (Schedule.due(fetchedAt, hardAt, refetchBefore, now)) {
                Schedule.Due.NO -> return false
                Schedule.Due.CHECK -> if (!cheap) return false
                Schedule.Due.DOWNLOAD -> {}
            }
            if (empty) tile.state = State.EMPTY
            tile.checkedAt = fetchedAt
            tile.hardAt = hardAt
            refresh(tile)
            true
        } catch (e: Exception) {
            warnOnce(key, "Could not prepare BlueMap tile", e)
            true
        }
    }

    /** Records that BlueMap has nothing for a tile, and deletes its picture. */
    private fun markEmpty(tile: Tile, meta: Properties, now: Long) {
        Files.deleteIfExists(tileFile(tile.key))
        Files.deleteIfExists(oldPicture(tile.key))
        meta.setProperty("empty", "true")
        meta.setProperty("fetched", now.toString())
        meta.setProperty("hard", now.toString())
        meta.remove("emptyAt")
        meta.remove("hash")
        meta.remove("etag")
        meta.remove("modified")
        writeMeta(metaOf(tile.key), meta)
        tile.present = null
        tile.state = State.EMPTY
    }

    private fun ready(tile: Tile, present: BitSet, contentMinute: Int) {
        tile.present = present
        tile.contentMinute = contentMinute
        tile.state = State.READY
    }

    private fun readMeta(file: Path): Properties {
        val meta = Properties()
        if (Files.exists(file)) Files.newInputStream(file).use(meta::load)
        return meta
    }

    private fun writeMeta(file: Path, meta: Properties) {
        val text = java.io.StringWriter().also { meta.store(it, null) }.toString()
        SafeFiles.writeString(file, text)
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    /** When a tile is next asked about, and whether that is a whole download. */
    object Schedule {
        enum class Due { NO, CHECK, DOWNLOAD }

        fun due(checkedAt: Long, hardAt: Long, refetchBefore: Long, now: Long): Due = when {
            hardAt < refetchBefore || now - hardAt >= Config.tileHardRefreshHours * 3_600_000L -> Due.DOWNLOAD
            now - checkedAt >= Config.tileCheckMinutes * 60_000L -> Due.CHECK
            else -> Due.NO
        }
    }

    companion object {
        private const val RETRY_MS = 60_000L
        /** A map's settings loading is taken as true for this long before a 404 is believed again. */
        private const val SETTINGS_TRUST_MS = 60_000L

        /** Video memory of a [side]-pixel square texture. */
        fun textureBytes(side: Int): Long = side.toLong() * side * 4
    }
}
