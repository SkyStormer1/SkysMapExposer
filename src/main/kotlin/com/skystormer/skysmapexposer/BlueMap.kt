package com.skystormer.skysmapexposer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
 */
class BlueMap(baseUrl: String) {

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
        val response = get("maps/$map/settings.json", HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()} for $map settings")
        val lowres = JsonParser.parseString(response.body()).asJsonObject.getAsJsonObject("lowres")
        return MapLayout(
            tileSize = lowres.getAsJsonArray("tileSize")[0].asInt,
            lodFactor = lowres.get("lodFactor").asInt,
            lodCount = lowres.get("lodCount").asInt,
        )
    }

    /**
     * The maps this BlueMap has, as id to display name, from `settings.json` and each map's own
     * settings. Blocking; call it off the client thread.
     */
    @Throws(IOException::class)
    fun maps(): List<Pair<String, String>> {
        val response = get("settings.json", HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()} for settings.json")
        val ids = JsonParser.parseString(response.body()).asJsonObject.getAsJsonArray("maps").map { it.asString }
        return ids.map { id ->
            val name = try {
                val map = get("maps/$id/settings.json", HttpResponse.BodyHandlers.ofString())
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
        val response = get(tilePath(map, lod, tileX, tileZ), HttpResponse.BodyHandlers.ofByteArray())
        return when (response.statusCode()) {
            200 -> response.body()
            204, 404 -> null
            else -> throw IOException("HTTP ${response.statusCode()} for tile $map/$lod/$tileX,$tileZ")
        }
    }

    /** A map's live markers, `maps/<map>/live/markers.json`, or null if it has none. Blocking. */
    @Throws(IOException::class)
    fun markers(map: String): JsonObject? {
        val response = get("maps/$map/live/markers.json", HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        return JsonParser.parseString(response.body()).asJsonObject
    }

    /** Who is where, `maps/<map>/live/players.json`, or null if BlueMap does not share it. Blocking. */
    @Throws(IOException::class)
    fun players(map: String): JsonObject? {
        val response = get("maps/$map/live/players.json", HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        return JsonParser.parseString(response.body()).asJsonObject
    }

    /** Any other file the web app serves, such as a marker icon; null if missing. Blocking. */
    @Throws(IOException::class)
    fun file(path: String): ByteArray? {
        val response = get(path.trimStart('/'), HttpResponse.BodyHandlers.ofByteArray())
        return if (response.statusCode() == 200) response.body() else null
    }

    private fun <T> get(path: String, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val request = HttpRequest.newBuilder(URI.create(base + path))
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
            "maps/$map/tiles/$lod/x${splitDigits(tileX)}/z${splitDigits(tileZ)}.png"

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
