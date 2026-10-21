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
 * Tiles on screen are checked again every [Config.tileRefreshSeconds], so the map follows the
 * server's BlueMap as it re-renders; an unchanged tile costs a download and nothing else.
 *
 * Nothing is kept that does not decode: a reply is decoded before it is saved, and a saved picture
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

        @Volatile
        var refreshing: Boolean = false

        @Volatile
        var failedAt: Long = 0

        /** The drawing plan for this tile; rebuilt by [Backfill] from time to time. */
        var mask: Backfill.Mask? = null

        /** While this tile is on its way: where a coarser tile stands in for it, and which level that is. */
        var standIn: Backfill.Mask? = null
        var standInLod: Int = 0

        /** When either map last drew it (epoch milliseconds). */
        var lastUsed: Long = 0

        /** Video memory its [textures] take. */
        var bytes: Long = 0

        fun hasDataAt(pixelX: Int, pixelZ: Int): Boolean =
            present?.get(pixelZ * imageSize + pixelX) ?: false
    }

    private val tiles = HashMap<Key, Tile>(256)

    /** Anything fetched before this is fetched again, whatever its age. Set by `/mapexposer refresh`. */
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

    /** The tile, starting its load if it is not in memory. Client thread only. */
    fun get(key: Key, blocksPerPixel: Int, tileSize: Int): Tile {
        var tile = tiles[key]
        val now = System.currentTimeMillis()
        if (tile == null || (tile.state == State.FAILED && now - tile.failedAt > RETRY_MS)) {
            tile?.let(::release)
            tile = Tile(key, blocksPerPixel, tileSize)
            tiles[key] = tile
            val loading = tile
            workers.execute { load(loading) }
        } else if (tile.state == State.READY && !tile.refreshing && now - tile.checkedAt > Config.tileRefreshSeconds * 1000L) {
            tile.refreshing = true
            val refreshing = tile
            workers.execute { refresh(refreshing) }
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
                tile.pending[factor] = shrink(decodeColours(tile, Files.readAllBytes(imageFile(tile.key))), tile, factor)
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
                for (x in 0 until side) image.setPixel(x, z, pixels[z * side + x] or OPAQUE)
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
        for (key in result.drop) tiles.remove(key)?.let(::release)
        coarser = Eviction.coarser(coarser, result.inViewOverBudget, result.kept, budget)
    }

    fun clear() {
        tiles.values.forEach(::release)
        tiles.clear()
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
    private fun imageFile(key: Key): Path = folder.resolve("${if (BlueMap.isMapId(key.map)) key.map else "_"}/${key.lod}/${key.x}_${key.z}.png")

    // ---- worker threads from here on ----

    /** When each map's settings last loaded, to believe a 404 by. */
    private val settingsLoadedAt = ConcurrentHashMap<String, Long>()

    /**
     * BlueMap's answer for [key]. A 404 counts as "nothing there" only while the map's settings
     * still load: otherwise the address or the server is wrong, and it is an error.
     */
    private fun fetch(key: Key): BlueMap.TileReply {
        val reply = blueMap.fetchTile(key.map, key.lod, key.x, key.z)
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
        val image = imageFile(key)
        val metaFile = metaOf(key, image)
        try {
            val meta = readMeta(metaFile)
            val fetchedAt = meta.getProperty("fetched")?.toLongOrNull() ?: 0L
            val known = fetchedAt > refetchBefore
            val fresh = known && System.currentTimeMillis() - fetchedAt < Config.tileRefreshSeconds * 1000L
            var contentMinute = meta.getProperty("since")?.toIntOrNull() ?: 0

            if (fresh && meta.getProperty("empty") == "true") {
                tile.checkedAt = fetchedAt
                tile.state = State.EMPTY
                return
            }
            if (Files.exists(image) && meta.getProperty("empty") != "true") {
                // An old picture is shown at once rather than waiting on the server: dated by when
                // it was fetched, it is asked about again once that is longer ago than the refresh
                // interval ([get]), and replaced only if BlueMap's has changed.
                val colours = try {
                    decodeColours(tile, Files.readAllBytes(image))
                } catch (e: Exception) {
                    warnOnce(key, "A saved BlueMap tile does not read; fetching it again", e)
                    Files.deleteIfExists(image)
                    null
                }
                if (colours != null) {
                    tile.checkedAt = if (known) fetchedAt else 0L
                    finish(tile, colours, contentMinute)
                    return
                }
            }

            when (val reply = fetch(key)) {
                is BlueMap.TileReply.Image -> {
                    // Decoded before anything is saved: a junk reply (a proxy's page, a cut-off
                    // file) fails here, and nothing of it is kept.
                    val colours = decodeColours(tile, reply.bytes)
                    val now = System.currentTimeMillis()
                    tile.checkedAt = now
                    val hash = sha1(reply.bytes)
                    if (hash != meta.getProperty("hash") || contentMinute == 0) contentMinute = Clock.nowMinutes()
                    SafeFiles.write(image, reply.bytes)
                    meta.remove("empty")
                    meta.remove("emptyAt")
                    meta.setProperty("hash", hash)
                    meta.setProperty("since", contentMinute.toString())
                    meta.setProperty("fetched", now.toString())
                    writeMeta(metaFile, meta)
                    finish(tile, colours, contentMinute)
                }
                else -> {
                    val now = System.currentTimeMillis()
                    markEmpty(image, metaFile, meta, now)
                    tile.checkedAt = now
                    tile.state = State.EMPTY
                }
            }
        } catch (e: Exception) {
            tile.failedAt = System.currentTimeMillis()
            tile.state = State.FAILED
            warnOnce(key, "Could not load BlueMap tile; trying again in a minute", e)
        }
    }

    /**
     * Asks the server whether a tile on screen has changed. If it has, the new picture replaces the
     * old one and is dated now; if not, nothing changes. A tile BlueMap now has nothing for loses
     * its picture on the second check that says so. Every way out counts as checked.
     */
    private fun refresh(tile: Tile) {
        val key = tile.key
        val image = imageFile(key)
        val metaFile = metaOf(key, image)
        val now = System.currentTimeMillis()
        try {
            val meta = readMeta(metaFile)
            when (val reply = fetch(key)) {
                is BlueMap.TileReply.Image -> {
                    val hash = sha1(reply.bytes)
                    meta.setProperty("fetched", now.toString())
                    meta.remove("emptyAt")
                    if (hash == meta.getProperty("hash")) {
                        writeMeta(metaFile, meta)
                        return
                    }
                    val colours = decodeColours(tile, reply.bytes)
                    val minute = Clock.nowMinutes()
                    SafeFiles.write(image, reply.bytes)
                    meta.setProperty("hash", hash)
                    meta.setProperty("since", minute.toString())
                    writeMeta(metaFile, meta)
                    finish(tile, colours, minute)
                    // Every size of the old picture is out of date; they are remade as they are drawn.
                    tile.requested.clear()
                }
                else -> {
                    val firstSaid = meta.getProperty("emptyAt")?.toLongOrNull()
                    if (firstSaid == null || firstSaid >= now) {
                        // Once could be BlueMap re-rendering; the picture stays until the next check.
                        meta.setProperty("emptyAt", now.toString())
                        meta.setProperty("fetched", now.toString())
                        writeMeta(metaFile, meta)
                        return
                    }
                    markEmpty(image, metaFile, meta, now)
                    tile.present = null
                    tile.state = State.EMPTY
                }
            }
        } catch (e: Exception) {
            warnOnce(key, "Could not check BlueMap tile for changes", e)
        } finally {
            tile.checkedAt = now
            tile.refreshing = false
        }
    }

    /** Records that BlueMap has nothing for a tile, and deletes its picture. */
    private fun markEmpty(image: Path, metaFile: Path, meta: Properties, now: Long) {
        Files.deleteIfExists(image)
        meta.setProperty("empty", "true")
        meta.setProperty("fetched", now.toString())
        meta.remove("emptyAt")
        meta.remove("hash")
        writeMeta(metaFile, meta)
    }

    private fun finish(tile: Tile, colours: IntArray, contentMinute: Int) {
        val present = BitSet(colours.size)
        for (i in colours.indices) if (colours[i] != 0) present.set(i)
        tile.present = present
        tile.contentMinute = contentMinute
        tile.state = State.READY
    }

    /**
     * Turns BlueMap's colour and height halves into one shaded picture, `imageSize` square, 0
     * wherever BlueMap has nothing.
     *
     * BlueMap's colours are flat; its web viewer shades them in a shader from the height half. Here
     * each pixel is lightened where the ground rises towards the north-west and darkened where it
     * falls, so hills read the way they do on the rest of the map.
     */
    private fun decodeColours(tile: Tile, bytes: ByteArray): IntArray {
        val image = BlueMap.readImage(bytes, BlueMap.MAX_TILE_IMAGE)
        val width = image.width
        val half = image.height / 2
        require(width == half && width == tile.tileSize + 1) { "unexpected tile shape ${width}x${image.height}" }
        val source = image.getRGB(0, 0, width, image.height, null, 0, width)
        val blocksPerPixel = tile.blocksPerPixel.toFloat()

        fun height(x: Int, z: Int): Int = source[(half + z) * width + x].toShort().toInt()

        val pixels = IntArray(width * half)
        for (z in 0 until half) {
            for (x in 0 until width) {
                val colour = source[z * width + x]
                if (colour ushr 24 == 0) continue
                val here = height(x, z)
                val rise = (here - height(x, maxOf(z - 1, 0))) + (here - height(maxOf(x - 1, 0), z))
                val shade = 1f + (rise / blocksPerPixel).coerceIn(-6f, 6f) * 0.05f
                pixels[z * width + x] = OPAQUE or
                    (channel(colour shr 16, shade) shl 16) or
                    (channel(colour shr 8, shade) shl 8) or
                    channel(colour, shade)
            }
        }
        tile.imageSize = width
        return pixels
    }

    /**
     * Averages each [factor]×[factor] square of the tile (leaving out BlueMap's overlap column and
     * row) into one pixel, ignoring pixels BlueMap has nothing at.
     */
    private fun shrink(colours: IntArray, tile: Tile, factor: Int): IntArray {
        val width = tile.imageSize
        val side = tile.tileSize / factor
        val out = IntArray(side * side)
        for (outZ in 0 until side) {
            for (outX in 0 until side) {
                var red = 0
                var green = 0
                var blue = 0
                var count = 0
                for (z in outZ * factor until (outZ + 1) * factor) {
                    for (x in outX * factor until (outX + 1) * factor) {
                        val colour = colours[z * width + x]
                        if (colour == 0) continue
                        red += (colour shr 16) and 0xFF
                        green += (colour shr 8) and 0xFF
                        blue += colour and 0xFF
                        count++
                    }
                }
                if (count > 0) {
                    out[outZ * side + outX] = OPAQUE or ((red / count) shl 16) or ((green / count) shl 8) or (blue / count)
                }
            }
        }
        return out
    }

    private fun channel(value: Int, shade: Float): Int = ((value and 0xFF) * shade).toInt().coerceIn(0, 255)

    private fun metaOf(key: Key, image: Path): Path = image.resolveSibling("${key.x}_${key.z}.meta")

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

    companion object {
        private const val OPAQUE = 0xFF shl 24
        private const val RETRY_MS = 60_000L
        /** A map's settings loading is taken as true for this long before a 404 is believed again. */
        private const val SETTINGS_TRUST_MS = 60_000L

        /** Video memory of a [side]-pixel square texture. */
        fun textureBytes(side: Int): Long = side.toLong() * side * 4
    }
}
