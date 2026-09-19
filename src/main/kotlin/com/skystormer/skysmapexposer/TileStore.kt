package com.skystormer.skysmapexposer

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.textures.GpuTextureView
import net.minecraft.client.renderer.texture.DynamicTexture
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.BitSet
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import javax.imageio.ImageIO

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

        var failedAt: Long = 0

        /** The drawing plan for this tile; rebuilt by [Backfill] from time to time. */
        var mask: Backfill.Mask? = null

        var lastUsedFrame: Long = 0

        fun hasDataAt(pixelX: Int, pixelZ: Int): Boolean =
            present?.get(pixelZ * imageSize + pixelX) ?: false
    }

    private val tiles = LinkedHashMap<Key, Tile>(256, 0.75f, true)

    /** Anything fetched before this is fetched again, whatever its age. Set by `/mapexposer refresh`. */
    @Volatile
    var refetchBefore: Long = 0

    val size: Int get() = tiles.size

    fun countIn(state: State): Int = tiles.values.count { it.state == state }

    /** The tile, starting its load if it is not in memory. Client thread only. */
    fun get(key: Key, blocksPerPixel: Int, tileSize: Int, frame: Long): Tile {
        var tile = tiles[key]
        if (tile == null || (tile.state == State.FAILED && System.currentTimeMillis() - tile.failedAt > 60_000L)) {
            tile?.let(::release)
            tile = Tile(key, blocksPerPixel, tileSize)
            tiles[key] = tile
            val loading = tile
            workers.execute { load(loading) }
        } else if (tile.state == State.READY && !tile.refreshing &&
            System.currentTimeMillis() - tile.checkedAt > Config.tileRefreshSeconds * 1000L
        ) {
            tile.refreshing = true
            val refreshing = tile
            workers.execute { refresh(refreshing) }
        }
        tile.lastUsedFrame = frame
        return tile
    }

    /**
     * Asks for the tile's texture at [factor] blocks per pixel. Whatever texture it already has
     * keeps being drawn until the new one is ready, so zooming never leaves a hole.
     */
    fun requestFactor(tile: Tile, factor: Int) {
        if (tile.state != State.READY || !tile.requested.add(factor)) return
        workers.execute {
            try {
                tile.pending[factor] = shrink(decodeColours(tile, Files.readAllBytes(imageFile(tile.key))), tile, factor)
            } catch (e: Exception) {
                Log.warn("Could not read BlueMap tile {} back from disk: {}", tile.key, e.toString())
                tile.requested.remove(factor)
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
            tile.textures.remove(factor)?.close()
            tile.textures[factor] = DynamicTexture({ "skysmapexposer ${tile.key} at 1:$factor" }, image)
            // Three sizes at most: the minimap's, the world map's, and the one it is zooming to.
            if (tile.textures.size > 3) {
                tile.textures.keys.filter { it != factor && it != 1 }.take(tile.textures.size - 3).forEach { old ->
                    tile.textures.remove(old)?.close()
                    tile.requested.remove(old)
                }
            }
        }
        // Until the wanted size is ready, any size will do: better blurry than a hole.
        return (tile.textures[factor] ?: tile.textures.values.firstOrNull())?.textureView
    }

    /** Whether [tile] has a texture of [factor] waiting to be uploaded. */
    fun hasUploadWaiting(tile: Tile, factor: Int): Boolean = tile.pending.containsKey(factor)

    /** Drops the least recently drawn tiles beyond [keep], never one drawn in [frame]. */
    fun trim(keep: Int, frame: Long) {
        if (tiles.size <= keep) return
        val iterator = tiles.values.iterator()
        while (tiles.size > keep && iterator.hasNext()) {
            val tile = iterator.next()
            if (tile.lastUsedFrame == frame) break // access order: everything after this is newer
            release(tile)
            iterator.remove()
        }
    }

    fun clear() {
        tiles.values.forEach(::release)
        tiles.clear()
    }

    private fun release(tile: Tile) {
        tile.textures.values.forEach(DynamicTexture::close)
        tile.textures.clear()
        tile.pending.clear()
        tile.requested.clear()
    }

    private fun imageFile(key: Key): Path = folder.resolve("${key.map}/${key.lod}/${key.x}_${key.z}.png")

    // ---- worker threads from here on ----

    private fun load(tile: Tile) {
        val key = tile.key
        val image = imageFile(key)
        val metaFile = image.resolveSibling("${key.x}_${key.z}.meta")
        try {
            val meta = readMeta(metaFile)
            val fetchedAt = meta.getProperty("fetched")?.toLongOrNull() ?: 0L
            val fresh = fetchedAt > refetchBefore && System.currentTimeMillis() - fetchedAt < Config.tileRefreshSeconds * 1000L
            var bytes: ByteArray? = null
            var contentMinute = meta.getProperty("since")?.toIntOrNull() ?: 0

            if (fresh && meta.getProperty("empty") == "true") {
                tile.state = State.EMPTY
                return
            }
            if (fresh && Files.exists(image)) {
                bytes = Files.readAllBytes(image)
                tile.checkedAt = fetchedAt
            }

            if (bytes == null) {
                val fetched = try {
                    blueMap.tile(key.map, key.lod, key.x, key.z)
                } catch (e: IOException) {
                    // Offline or the server is down: an old picture beats none, and is not marked
                    // as fetched, so the next load tries the server again.
                    if (!Files.exists(image)) throw e
                    finish(tile, Files.readAllBytes(image), contentMinute)
                    return
                }
                if (fetched == null) {
                    meta.setProperty("empty", "true")
                    meta.setProperty("fetched", System.currentTimeMillis().toString())
                    writeMeta(metaFile, meta)
                    tile.state = State.EMPTY
                    return
                }
                bytes = fetched
                tile.checkedAt = System.currentTimeMillis()
                val hash = sha1(fetched)
                if (hash != meta.getProperty("hash") || contentMinute == 0) {
                    contentMinute = Clock.nowMinutes()
                }
                Files.createDirectories(image.parent)
                Files.write(image, fetched)
                meta.remove("empty")
                meta.setProperty("hash", hash)
                meta.setProperty("since", contentMinute.toString())
                meta.setProperty("fetched", System.currentTimeMillis().toString())
                writeMeta(metaFile, meta)
            }
            finish(tile, bytes, contentMinute)
        } catch (e: Exception) {
            tile.failedAt = System.currentTimeMillis()
            tile.state = State.FAILED
            Log.warn("Could not load BlueMap tile {}: {}", key, e.toString())
        }
    }

    /**
     * Asks the server whether a tile on screen has changed. If it has, the new picture replaces the
     * old one and is dated now; if not, nothing changes.
     */
    private fun refresh(tile: Tile) {
        val key = tile.key
        val image = imageFile(key)
        val metaFile = image.resolveSibling("${key.x}_${key.z}.meta")
        try {
            val fetched = blueMap.tile(key.map, key.lod, key.x, key.z) ?: return
            tile.checkedAt = System.currentTimeMillis()
            val meta = readMeta(metaFile)
            val hash = sha1(fetched)
            meta.setProperty("fetched", tile.checkedAt.toString())
            if (hash == meta.getProperty("hash")) {
                writeMeta(metaFile, meta)
                return
            }
            val now = Clock.nowMinutes()
            Files.write(image, fetched)
            meta.setProperty("hash", hash)
            meta.setProperty("since", now.toString())
            writeMeta(metaFile, meta)
            finish(tile, fetched, now)
            // Every size of the old picture is out of date; they are remade as they are drawn.
            tile.requested.clear()
        } catch (e: Exception) {
            tile.checkedAt = System.currentTimeMillis()
        } finally {
            tile.refreshing = false
        }
    }

    private fun finish(tile: Tile, bytes: ByteArray, contentMinute: Int) {
        val colours = decodeColours(tile, bytes)
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
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw IOException("not an image")
        val width = image.width
        val half = image.height / 2
        require(width == half) { "unexpected tile shape ${width}x${image.height}" }
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

    private fun readMeta(file: Path): Properties {
        val meta = Properties()
        if (Files.exists(file)) Files.newInputStream(file).use(meta::load)
        return meta
    }

    private fun writeMeta(file: Path, meta: Properties) {
        Files.createDirectories(file.parent)
        Files.newOutputStream(file).use { meta.store(it, null) }
    }

    private fun sha1(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val OPAQUE = 0xFF shl 24
    }
}
