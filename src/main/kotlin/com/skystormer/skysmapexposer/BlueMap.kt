package com.skystormer.skysmapexposer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO

/**
 * Talking to a BlueMap web server.
 *
 * Only BlueMap's "lowres" tiles are used. They are plain PNGs, 1 pixel per block at the finest
 * level, which is the resolution Xaero's map is drawn at; the "hires" tiles are 3D models, which a
 * flat map has no use for. A lowres tile image is `(tileSize + 1)` pixels wide and twice as tall:
 * the top half is the colour of each column seen from above, the bottom half its terrain height
 * (low 16 bits of the RGB value, signed) and block light (the next 8). The extra row and column
 * overlap the neighbouring tile.
 *
 * These are facts about the files a BlueMap server publishes, checked against a live server; no
 * BlueMap code is used.
 *
 * Everything a server sends is treated as untrusted: replies are capped in size, map ids are
 * checked before they go into a path, and layouts and images are checked before they are used.
 */
class BlueMap(baseUrl: String) : AutoCloseable {

    /** The web address with exactly one trailing slash. */
    val base: String = baseUrl.trimEnd('/') + "/"

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * How one BlueMap map lays out its lowres tiles.
     *
     * @property tileSize blocks along one side of a tile at the finest level.
     * @property lodFactor how many times more blocks each pixel covers at each coarser level.
     * @property lodCount how many levels there are; level 1 is the finest.
     */
    class MapLayout(val tileSize: Int, val lodFactor: Int, val lodCount: Int) {

        /** Blocks per pixel at [lod]. */
        fun blocksPerPixel(lod: Int): Int {
            var blocks = 1
            repeat(lod - 1) { blocks *= lodFactor }
            return blocks
        }

        /** Blocks along one side of a tile at [lod]. */
        fun tileBlocks(lod: Int): Int = tileSize * blocksPerPixel(lod)
    }

    /** Reads `maps/<map>/settings.json`. Blocking; call it off the client thread. */
    @Throws(IOException::class)
    fun layout(map: String): MapLayout {
        val response = getText("maps/${checkMapId(map)}/settings.json")
        if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()} for $map settings")
        val lowres = JsonParser.parseString(response.body()).asJsonObject.getAsJsonObject("lowres")
            ?: throw IOException("$map has no lowres tiles")
        val layout = MapLayout(
            tileSize = lowres.getAsJsonArray("tileSize")[0].asInt,
            lodFactor = lowres.get("lodFactor").asInt,
            lodCount = lowres.get("lodCount").asInt,
        )
        // Sizes no BlueMap uses would overflow or loop for ever further on.
        if (layout.tileSize !in 1..MAX_TILE_SIZE || layout.lodFactor !in 1..MAX_LOD_FACTOR || layout.lodCount !in 1..MAX_LOD_COUNT) {
            throw IOException("$map has an unusable layout: tiles ${layout.tileSize}, factor ${layout.lodFactor}, levels ${layout.lodCount}")
        }
        return layout
    }

    /**
     * The maps this BlueMap has, as id to display name, from `settings.json` and each map's own
     * settings. Blocking; call it off the client thread.
     */
    @Throws(IOException::class)
    fun maps(): List<Pair<String, String>> {
        val response = getText("settings.json")
        if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()} for settings.json")
        // Ids that could not be a folder name are left out rather than trusted.
        val ids = JsonParser.parseString(response.body()).asJsonObject.getAsJsonArray("maps")
            .map { it.asString }.filter(::isMapId).take(MAX_MAPS)
        return ids.map { id ->
            val name = try {
                val map = getText("maps/$id/settings.json")
                JsonParser.parseString(map.body()).asJsonObject.get("name")?.asString ?: id
            } catch (e: Exception) {
                id
            }
            id to name
        }
    }

    /**
     * Fetches one lowres tile image. Blocking; call it off the client thread.
     *
     * @return the PNG, or null if BlueMap has never rendered anything there (it answers 204).
     */
    @Throws(IOException::class)
    fun tile(map: String, lod: Int, tileX: Int, tileZ: Int): ByteArray? {
        val response = getBytes(tilePath(map, lod, tileX, tileZ))
        return when (response.statusCode()) {
            200 -> response.body()
            204, 404 -> null
            else -> throw IOException("HTTP ${response.statusCode()} for tile $map/$lod/$tileX,$tileZ")
        }
    }

    /**
     * How one map lays out its hires tiles: [tileSize] blocks a side, with tile (0, 0) starting at
     * block ([offsetX], [offsetZ]).
     */
    class HiresLayout(val tileSize: Int, val offsetX: Int, val offsetZ: Int) {
        /** The tile holding block [block] along one axis. */
        fun tileOf(block: Int, offset: Int): Int = Math.floorDiv(block - offset, tileSize)
    }

    /** Reads the hires part of `maps/<map>/settings.json`. Blocking. */
    @Throws(IOException::class)
    fun hiresLayout(map: String): HiresLayout {
        val response = getText("maps/${checkMapId(map)}/settings.json")
        if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()} for $map settings")
        val hires = JsonParser.parseString(response.body()).asJsonObject.getAsJsonObject("hires")
            ?: throw IOException("$map has no hires tiles")
        val size = hires.getAsJsonArray("tileSize")[0].asInt
        val translate = hires.getAsJsonArray("translate")
        val layout = HiresLayout(size, translate?.get(0)?.asInt ?: 0, translate?.get(1)?.asInt ?: 0)
        if (layout.tileSize !in 1..MAX_HIRES_TILE || layout.offsetX !in 0 until size || layout.offsetZ !in 0 until size) {
            throw IOException("$map has an unusable hires layout")
        }
        return layout
    }

    /**
     * Fetches one hires tile, BlueMap's 3D model of [HiresLayout.tileSize] blocks a side, as it is
     * sent (usually gzip-compressed). Blocking.
     *
     * @return the file, or null if BlueMap has never rendered anything there.
     */
    @Throws(IOException::class)
    fun hiresTile(map: String, tileX: Int, tileZ: Int): ByteArray? {
        val response = getBytes("maps/${checkMapId(map)}/tiles/0/x${splitDigits(tileX)}/z${splitDigits(tileZ)}.prbm")
        return when (response.statusCode()) {
            200 -> response.body()
            204, 404 -> null
            else -> throw IOException("HTTP ${response.statusCode()} for hires tile $map/$tileX,$tileZ")
        }
    }

    /**
     * The textures a map's hires tiles refer to by number, as resource paths such as
     * `minecraft:block/stone`, from `maps/<map>/textures.json`. Blocking.
     */
    @Throws(IOException::class)
    fun textures(map: String): List<String> {
        val response = getBytes("maps/${checkMapId(map)}/textures.json")
        if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()} for $map textures")
        val text = String(gunzipIfNeeded(response.body(), MAX_BODY), Charsets.UTF_8)
        return JsonParser.parseString(text).asJsonArray.map { entry ->
            entry.asJsonObject.get("resourcePath")?.asString ?: ""
        }
    }

    /** A map's live markers, `maps/<map>/live/markers.json`, or null if it has none. Blocking. */
    @Throws(IOException::class)
    fun markers(map: String): JsonObject? {
        val response = getText("maps/${checkMapId(map)}/live/markers.json")
        if (response.statusCode() != 200) return null
        return JsonParser.parseString(response.body()).asJsonObject
    }

    /** Who is where, `maps/<map>/live/players.json`, or null if BlueMap does not share it. Blocking. */
    @Throws(IOException::class)
    fun players(map: String): JsonObject? {
        val response = getText("maps/${checkMapId(map)}/live/players.json")
        if (response.statusCode() != 200) return null
        return JsonParser.parseString(response.body()).asJsonObject
    }

    /** Any other file the web app serves, such as a marker icon; null if missing. Blocking. */
    @Throws(IOException::class)
    fun file(path: String): ByteArray? {
        val response = getBytes(path.trimStart('/'))
        return if (response.statusCode() == 200) response.body() else null
    }

    /**
     * Stops the web client and its background thread now, rather than whenever the garbage
     * collector gets to it. Nothing can be fetched afterwards.
     */
    override fun close() {
        client.shutdownNow()
    }

    /** A reply, read whole on the calling thread, but never more than [MAX_BODY] bytes of it. */
    private class Reply<T>(private val status: Int, private val content: T) {
        fun statusCode() = status
        fun body() = content
    }

    private fun getBytes(path: String): Reply<ByteArray> {
        val response = get(path, HttpResponse.BodyHandlers.ofInputStream())
        return Reply(response.statusCode(), readCapped(response.body()))
    }

    private fun getText(path: String): Reply<String> {
        val reply = getBytes(path)
        return Reply(reply.statusCode(), String(reply.body(), Charsets.UTF_8))
    }

    private fun <T> get(path: String, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val uri = try {
            URI.create(base + path)
        } catch (e: IllegalArgumentException) {
            throw IOException("not a usable address: $path", e)
        }
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "SkysMapExposer (Minecraft mod)")
            .GET()
            .build()
        try {
            return client.send(request, handler)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("interrupted", e)
        }
    }

    companion object {
        /**
         * BlueMap splits each coordinate's digits into folders, so tile (-12, 3) at level 1 is
         * `tiles/1/x-1/2/z3.png`. This keeps any one folder on the server small.
         */
        fun tilePath(map: String, lod: Int, tileX: Int, tileZ: Int): String =
            "maps/${checkMapId(map)}/tiles/$lod/x${splitDigits(tileX)}/z${splitDigits(tileZ)}.png"

        private const val MAX_BODY = 32 * 1024 * 1024
        private const val MAX_TILE_SIZE = 4096

        /** The biggest lowres tile image: `(tileSize + 1)` wide and twice as tall. */
        const val MAX_TILE_IMAGE = (MAX_TILE_SIZE + 1) * 2

        /** The biggest marker icon worth drawing. */
        const val MAX_ICON = 1024
        private const val MAX_LOD_FACTOR = 64
        private const val MAX_LOD_COUNT = 16
        private const val MAX_MAPS = 64
        private val MAP_ID = Regex("[A-Za-z0-9_.-]{1,64}")

        /** Whether [id] is safe to use as a BlueMap map id, in a web address and as a folder name. */
        fun isMapId(id: String): Boolean = MAP_ID.matches(id) && id.any { it != '.' }

        @Throws(IOException::class)
        fun checkMapId(id: String): String = if (isMapId(id)) id else throw IOException("not a map id: $id")

        private fun readCapped(stream: InputStream): ByteArray = stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_BODY) throw IOException("reply too big")
            }
            out.toByteArray()
        }

        /**
         * Decodes a PNG (or any image ImageIO reads), refusing one wider or taller than
         * [maxSide] before decoding it, so a small file claiming a huge picture cannot use up memory.
         */
        @Throws(IOException::class)
        fun readImage(bytes: ByteArray, maxSide: Int): BufferedImage {
            val input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)) ?: throw IOException("not an image")
            input.use {
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: throw IOException("not an image")
                try {
                    reader.input = input
                    val width = reader.getWidth(0)
                    val height = reader.getHeight(0)
                    if (width !in 1..maxSide || height !in 1..maxSide) throw IOException("image too big: ${width}x$height")
                    return reader.read(0)
                } finally {
                    reader.dispose()
                }
            }
        }

        private const val MAX_HIRES_TILE = 1024

        /** [bytes], unpacked if they are gzip, never to more than [limit] bytes. */
        @Throws(IOException::class)
        fun gunzipIfNeeded(bytes: ByteArray, limit: Int): ByteArray {
            if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return bytes
            java.util.zip.GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > limit) throw IOException("unpacks too big")
                }
                return out.toByteArray()
            }
        }

        private fun splitDigits(value: Int): String {
            val text = value.toString()
            val path = StringBuilder()
            for ((index, character) in text.withIndex()) {
                path.append(character)
                if (character.isDigit() && index < text.length - 1) path.append('/')
            }
            return path.toString()
        }
    }
}
