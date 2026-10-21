package com.skystormer.skysmapexposer

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import xaero.map.WorldMapSession
import xaero.map.region.MapRegion
import xaero.map.world.MapDimension
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Copies of Xaero's map, kept before anything of this mod's writes over it.
 *
 * Downloading BlueMap into the map writes into Xaero's own files, which is permanent and has no
 * undo of its own: get the colours wrong, or write over somewhere you had really explored, and the
 * only way back is a copy made beforehand. So one is made beforehand, region by region ([keep]):
 * each region file is copied just before it is first written, about 150 to 400 KB each, and the
 * copy is checked before anything is written. One region can then be put back without touching
 * the rest, and a whole download's regions sit together in one folder to put back at once.
 * [snapshot] takes every region of the layer on screen at once (`/mapexposer backup`), for a line
 * to retreat to whenever you like. Copies keep their file dates, which the mod dates your map by.
 *
 * Each folder of copies has a [INFO] saying exactly where they came from ([Origin]), so they go back
 * there whatever the world map is showing. Xaero may hold newer exploring of a region than its file
 * has; it is asked to save that first ([fileIsCurrent]).
 *
 * Restoring is asked for in two steps ([preview], then [restore]), and copies aside what it is
 * about to change first, so a restore can be undone like anything else. It puts the files back and
 * then has Xaero read the regions again, because Xaero holds regions open and would otherwise carry
 * on drawing, and later saving, the copy it already had.
 */
object MapBackup {

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val SHOWN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** In each folder of copies: where they came from. */
    const val INFO = "backup.json"
    private const val BEFORE_RESTORE = "before-restore-"

    /** This batch's time stamp, taken once its first copy is needed. */
    private var launch: String? = null

    /**
     * Regions already copied in this batch, so a region is only ever saved in its first state. A
     * batch is one download ([forget] starts the next), or else one visit to the server.
     */
    private val kept = HashSet<String>()

    /** Where each label's folder of this batch came from, once it has been written. */
    private val origins = HashMap<String, Origin>()

    /**
     * Where a folder of copies came from, and so where it goes back to: the dimension, Xaero's cave
     * layer, the exact folder of that layer's regions (multiworld folder and all), and the server.
     */
    class Origin(
        val dimensionId: String,
        val layer: Int,
        val folder: Path,
        /** The server's address, as [Config.normalise] writes it. */
        val server: String,
        val created: Long = System.currentTimeMillis(),
        /** Regions copied while Xaero still had newer exploring of them that it did not save in time. */
        val unsaved: Set<String> = emptySet(),
    ) {
        fun withUnsaved(region: String) = Origin(dimensionId, layer, folder, server, created, unsaved + region)

        fun write(batch: Path) {
            val json = JsonObject()
            json.addProperty("dimension", dimensionId)
            json.addProperty("layer", layer)
            json.addProperty("folder", folder.toAbsolutePath().toString())
            json.addProperty("server", server)
            json.addProperty("created", created)
            json.add("unsaved", com.google.gson.JsonArray().also { array -> unsaved.sorted().forEach(array::add) })
            SafeFiles.writeString(batch.resolve(INFO), json.toString())
        }

        companion object {
            /** [batch]'s origin; null for a folder from before 1.9.2, or one whose [INFO] is damaged. */
            fun read(batch: Path): Origin? = try {
                val file = batch.resolve(INFO)
                if (Files.notExists(file)) null
                else {
                    val json = JsonParser.parseString(Files.readString(file)).asJsonObject
                    Origin(
                        json.get("dimension").asString, json.get("layer").asInt, Path.of(json.get("folder").asString),
                        json.get("server").asString, json.get("created").asLong,
                        json.getAsJsonArray("unsaved")?.map { it.asString }?.toSet().orEmpty(),
                    )
                }
            } catch (e: Exception) {
                Log.error("Could not read $INFO of $batch", e)
                null
            }
        }
    }

    /** Where to restore a copy that does not say where it came from: the map on screen. */
    class Target(val dimensionId: String, val layer: Int, val folder: Path)

    /**
     * The name a batch's folder carries for [dimension]'s cave [layer]: just the dimension for the
     * surface.
     */
    fun label(dimension: String, layer: Int): String = if (layer == XaeroWriter.SURFACE) dimension else "$dimension$LAYER$layer"

    private const val LAYER = "_cave_layer_"

    /** This server's address as [Origin.server] keeps it; empty when not on one. */
    fun server(): String = MapExposerClient.addressOf(Minecraft.getInstance())?.let(Config::normalise).orEmpty()

    /** The map the world map is showing, as where a copy without an [Origin] would go. */
    fun shownTarget(): Target? = XaeroWriter.shown().first?.let { Target(it.dimensionId, it.layer, it.folder) }

    /**
     * Where this mod keeps its copies for the server you are on — also on one with no BlueMap,
     * where a map shared in chat can still be added.
     */
    fun backupRoot(): Path? = (Session.current?.folder
        ?: MapExposerClient.addressOf(Minecraft.getInstance())?.let(Session::folderFor))?.resolve("backups")

    /** What [keep] came to. */
    sealed interface Kept {
        object Yes : Kept
        /** Xaero is saving the region first; ask again on a later tick. */
        object Wait : Kept
        class No(val why: String) : Kept
    }

    /**
     * Copies region ([x], [z]) out of [origin]'s folder if it has not been copied already in this
     * batch, into this batch's folder for [label] — the dimension, so that two dimensions' regions
     * of the same name never land on each other. [Kept.Yes] means a copy exists afterwards, which
     * is also the case when there is no file yet: a region that does not exist is restored by
     * deleting whatever gets written, and [restore] does that.
     */
    fun keep(origin: Origin, label: String, x: Int, z: Int): Kept {
        val id = "${origin.folder}|${key(x, z)}"
        if (id in kept) return Kept.Yes
        val current = fileIsCurrent(origin, x, z) ?: return Kept.Wait
        return try {
            val into = folderForThisLaunch(label, origin) ?: return Kept.No("this server has no folder for copies")
            if (!current) noteUnsaved(origin, label, regionFile(x, z))
            copyAside(origin.folder, into, regionFile(x, z))
            kept.add(id)
            Kept.Yes
        } catch (e: Throwable) {
            Log.error("Could not copy region ${x}_$z before writing to it", e)
            Kept.No(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Copies region file [region] from [from] into [into], keeping its date, and checks the copy;
     * a region with no file yet is noted as absent, so restoring removes whatever is added.
     */
    private fun copyAside(from: Path, into: Path, region: String) {
        Files.createDirectories(into)
        val source = from.resolve(region)
        if (Files.exists(source)) {
            val copy = into.resolve(region)
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            // A copy that is not the whole file is no backup.
            if (Files.size(copy) != Files.size(source)) throw IOException("the copy of $region came out a different size")
        } else {
            Files.writeString(into.resolve("$region.absent"), "")
        }
    }

    // ---- Xaero's unsaved exploring ----

    /** When Xaero was asked to save each region before it is copied. */
    private val saveAsked = HashMap<String, Long>()
    private const val SAVE_FIRST_WAIT = 10_000L

    /**
     * Whether region ([x], [z]) of [origin] can be copied now: true when Xaero has nothing newer of
     * it in memory than its file, or has just saved it; null when it has been asked to save it and
     * that is under way; false when it did not save it in time, and the copy goes ahead without.
     */
    private fun fileIsCurrent(origin: Origin, x: Int, z: Int): Boolean? {
        val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return true
        val dimension = processor.mapWorld?.currentDimension ?: return true
        if (dimension.dimId?.identifier()?.toString() != origin.dimensionId) return true
        val region = dimension.layeredMapRegions.getLeaf(origin.layer, x, z) ?: return true
        val id = "${origin.folder}|${key(x, z)}"
        val now = System.currentTimeMillis()
        val asked = saveAsked[id]
        if (asked == null) {
            if (!unsavedIn(region)) return true
            saveAsked[id] = now
            XaeroWriter.finish(processor, region)
            return null
        }
        if (XaeroWriter.saved(region)) return true.also { saveAsked.remove(id) }
        if (now - asked > SAVE_FIRST_WAIT) {
            saveAsked.remove(id)
            Log.warn("Xaero did not save region {}_{} within {} s; copied its file without the newest exploring", x, z, SAVE_FIRST_WAIT / 1000)
            return false
        }
        return null
    }

    /** Whether Xaero holds changes to [region] that are not in its file yet. */
    private fun unsavedIn(region: MapRegion): Boolean = synchronized(region) {
        if (region.loadState.toInt() != 2) return false
        for (tx in 0 until 8) for (tz in 0 until 8) if (region.getChunk(tx, tz)?.wasChanged() == true) return true
        false
    }

    private fun noteUnsaved(origin: Origin, label: String, region: String) {
        val into = folderForThisLaunch(label, origin) ?: return
        val noted = (origins[label] ?: origin).withUnsaved(region)
        origins[label] = noted
        runCatching { noted.write(into) }.onFailure { Log.error("Could not note an unsaved region in $INFO", it) }
    }

    // ---- /mapexposer backup ----

    private class Snapshot(val origin: Origin, val name: String, val into: Path, val waiting: MutableList<Pair<Int, Int>>, val done: (String) -> Unit)

    private var snapshot: Snapshot? = null

    /**
     * Copies every region of the layer on screen (in the Nether usually a cave layer, not the
     * roof), once Xaero has saved what it holds of them in memory. The other layers and the
     * derived caches are left out: they are the great bulk of the folder. [done] is told the
     * outcome. Returns what to say now.
     */
    fun snapshot(done: (String) -> Unit): String {
        if (snapshot != null) return "A copy of the map is already being taken"
        val (shown, why) = XaeroWriter.shown()
        shown ?: return why!!
        return try {
            val origin = Origin(shown.dimensionId, shown.layer, shown.folder, server())
            val name = LocalDateTime.now().format(STAMP) + "-full-" + SafeFiles.name(label(shown.name, shown.layer))
            val into = backupRoot()?.resolve(name) ?: return "This server has no folder for copies"
            val regions = regionsIn(shown.folder)
            val waiting = regions.filter { (x, z) -> fileIsCurrent(origin, x, z) == null }.toMutableList()
            snapshot = Snapshot(origin, name, into, waiting, done)
            tick()
            if (snapshot == null) "" else "Having Xaero save ${waiting.size} region(s) it is holding first…"
        } catch (e: Throwable) {
            Log.error("Could not take a copy of the map", e)
            "Could not copy the map; the log says why"
        }
    }

    /** Each client tick: carries on with a copy of the map waiting for Xaero to save. */
    fun tick() {
        val taking = snapshot ?: return
        try {
            val unsaved = ArrayList<String>()
            taking.waiting.removeAll { (x, z) ->
                when (fileIsCurrent(taking.origin, x, z)) {
                    true -> true
                    false -> true.also { unsaved.add(regionFile(x, z)) }
                    null -> false
                }
            }
            var origin = taking.origin
            for (region in unsaved) origin = origin.withUnsaved(region)
            snapshot = Snapshot(origin, taking.name, taking.into, taking.waiting, taking.done)
            if (taking.waiting.isNotEmpty()) return
            snapshot = null
            Files.createDirectories(taking.into)
            var copied = 0
            for ((x, z) in regionsIn(origin.folder)) {
                copyAside(origin.folder, taking.into, regionFile(x, z))
                copied++
            }
            origin.write(taking.into)
            taking.done("Copied $copied regions of the ${MapMenus.dimensionName(origin.dimensionId) ?: origin.dimensionId} as ${taking.name}" +
                if (origin.unsaved.isEmpty()) "" else "; ${origin.unsaved.size} without Xaero's newest exploring, which it did not save in time")
        } catch (e: Throwable) {
            snapshot = null
            Log.error("Could not take a copy of the map", e)
            taking.done("Could not copy the map; the log says why")
        }
    }

    /** The regions Xaero has a file for in [folder]. */
    fun regionsIn(folder: Path): List<Pair<Int, Int>> = try {
        if (Files.notExists(folder)) emptyList()
        else Files.list(folder).use { files -> files.toList().filter(Files::isRegularFile).mapNotNull { regionOf(it.fileName.toString()) } }
    } catch (e: Exception) {
        Log.error("Could not list the map's regions in $folder", e)
        emptyList()
    }

    // ---- restoring ----

    /** The copies there are, newest first. */
    fun names(): List<String> = backupRoot()?.let(::names).orEmpty()

    fun names(root: Path): List<String> = try {
        if (Files.notExists(root)) emptyList()
        else Files.list(root).use { entries -> entries.toList().filter { Files.isDirectory(it) }.map { it.fileName.toString() }.sortedDescending() }
    } catch (e: Exception) {
        emptyList()
    }

    /** What restoring copy [name] would do: which regions go back, where to. */
    class Preview(
        val name: String,
        val batch: Path,
        /** Null for a copy from before 1.9.2, which goes into the map on screen. */
        val origin: Origin?,
        val target: Target,
        /** Region files, and `.absent` notes of regions that had no file. */
        val regions: List<String>,
    ) {
        val created: String get() = origin?.created?.let { SHOWN.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())) }
            ?: name.take(16).replace('_', ' ').let { if (it.length == 16) it.substring(0, 13) + ":" + it.substring(14) else name }
    }

    /**
     * What restoring copy [name] out of [root] would do, or why it cannot be done. [plain] is a
     * `/mapexposer restore` with no name, which never takes a copy that does not say where it came
     * from; [server] is the server you are on, and [shown] the map on screen.
     */
    fun preview(root: Path, name: String, plain: Boolean, server: String, shown: Target?, nameOf: (String) -> String = { it }): Pair<Preview?, String?> {
        val batch = root.resolve(name)
        // A name is one of ours or it is nothing: no walking out of the folder with it.
        if (batch.normalize().parent != root.normalize() || !Files.isDirectory(batch)) return null to "No copy called '$name'; /mapexposer backups lists them"
        val origin = Origin.read(batch)
        val target = if (origin == null) {
            if (plain) {
                return null to "The newest copy, $name, is from before Sky's Map Exposer 1.9.2 and does not say which map it came from. " +
                    "/mapexposer restore $name puts it into the map on screen"
            }
            Target(shown?.dimensionId ?: return null to "Open the world map on the map this copy is for first", shown.layer, shown.folder)
        } else {
            if (origin.server != server) return null to "That copy is of another server (${origin.server}); join it there to put it back"
            if (!Files.isDirectory(origin.folder)) return null to "The map folder that copy came from is gone: ${origin.folder}"
            Target(origin.dimensionId, origin.layer, origin.folder)
        }
        val regions = try {
            Files.list(batch).use { entries ->
                entries.toList().map { it.fileName.toString() }.filter { regionOf(it.removeSuffix(".absent")) != null }.sorted()
            }
        } catch (e: Exception) {
            Log.error("Could not read the copy '$name'", e)
            return null to "Could not read the copy '$name'; the log says why"
        }
        if (regions.isEmpty()) return null to "The copy '$name' has no regions in it"
        if (origin == null) Log.warn("Restoring '{}', which does not say where it came from, into the {}", name, nameOf(target.dimensionId))
        return Preview(name, batch, origin, target, regions) to null
    }

    /** What [putBack] did: the regions put back, and the copy of what they were before. */
    class Restored(val regions: List<Pair<Int, Int>>, val before: String)

    /**
     * Puts [preview]'s regions back, keeping their file dates, after copying what is there now into
     * a new folder of [root] (with its own [INFO]) to undo it with. Nothing is changed if that copy
     * cannot be made.
     */
    fun putBack(root: Path, preview: Preview, server: String, now: LocalDateTime = LocalDateTime.now()): Restored {
        val target = preview.target
        val label = preview.name.removeRange(0, minOf(preview.name.length, 20)).removePrefix("full-").let {
            var rest = it
            while (rest.startsWith(BEFORE_RESTORE)) rest = rest.removePrefix(BEFORE_RESTORE)
            rest.ifEmpty { "map" }
        }
        var before = "${now.format(STAMP)}-$BEFORE_RESTORE$label"
        var n = 2
        while (Files.exists(root.resolve(before))) before = "${now.format(STAMP)}-$BEFORE_RESTORE$label-${n++}"
        val aside = root.resolve(before)
        val files = preview.regions.map { it.removeSuffix(".absent") }
        try {
            for (region in files) copyAside(target.folder, aside, region)
            Origin(target.dimensionId, target.layer, target.folder, preview.origin?.server ?: server, now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .write(aside)
        } catch (e: Throwable) {
            throw IOException("could not copy your map aside before restoring (${e.message}), so nothing was changed", e)
        }
        val restored = ArrayList<Pair<Int, Int>>()
        for (entry in preview.regions) {
            val region = entry.removeSuffix(".absent")
            if (entry.endsWith(".absent")) Files.deleteIfExists(target.folder.resolve(region))
            else Files.copy(preview.batch.resolve(entry), target.folder.resolve(region), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            forgetPicture(target.folder, region.removeSuffix(".zip"))
            regionOf(region)?.let(restored::add)
        }
        return Restored(restored, before)
    }

    /**
     * Regions the last [restore] put back right around you, which Xaero keeps writing what you see
     * into and does not read again until you move away or restart.
     */
    var nearYou = 0
        private set

    /** Restores [preview] and has Xaero read its regions again. Returns what was done, or why not. */
    fun restore(preview: Preview): Pair<Restored?, String?> {
        if (BlueMapDownload.running) return null to "Something is being written into your map; wait for it to finish, or cancel it, first"
        val root = backupRoot() ?: return null to "This server has no folder for copies"
        return try {
            val restored = putBack(root, preview, server())
            nearYou = 0
            val player = Minecraft.getInstance().player
            val here = Minecraft.getInstance().level?.dimension()?.identifier()?.toString() == preview.target.dimensionId
            val dimension = dimensionOf(preview.target.dimensionId)
            for ((x, z) in restored.regions) {
                dimension?.let { reread(it, x, z, preview.target.layer) }
                if (here && player != null && kotlin.math.abs(x - (player.blockX shr 9)) <= 1 && kotlin.math.abs(z - (player.blockZ shr 9)) <= 1) nearYou++
            }
            Log.info("Put back {} regions of {} into {}; what was there is in {}", restored.regions.size, preview.name, preview.target.folder, restored.before)
            restored to null
        } catch (e: Throwable) {
            Log.error("Could not put the copy '${preview.name}' back", e)
            null to "Could not put the copy back: ${e.message ?: e.javaClass.simpleName}. The log says more."
        }
    }

    /** Xaero's map of dimension [id], whether or not the world map is showing it. */
    private fun dimensionOf(id: String): MapDimension? = try {
        WorldMapSession.getCurrentSession()?.mapProcessor?.mapWorld?.getDimension(ResourceKey.create(Registries.DIMENSION, Identifier.parse(id)))
    } catch (e: Throwable) {
        null
    }

    /**
     * Takes away Xaero's cached picture of a region put back. The file put back is older than that
     * picture, so Xaero would otherwise go on showing the picture (of what was there before the
     * restore) as if it were up to date. Xaero draws it again from the file.
     */
    private fun forgetPicture(folder: Path, region: String) {
        try {
            Files.list(folder).use { entries ->
                for (cache in entries.toList()) {
                    if (!Files.isDirectory(cache) || !cache.fileName.toString().startsWith("cache")) continue
                    Files.deleteIfExists(cache.resolve("$region.xwmc"))
                    Files.deleteIfExists(cache.resolve("$region.xwmc.outdated"))
                }
            }
        } catch (e: Exception) {
            Log.error("Could not take away Xaero's cached picture of region $region", e)
        }
    }

    /**
     * Has Xaero read region ([x], [z]) of [layer] of [dimension] from its file again, the next time
     * it is on screen. The region stays where Xaero keeps it (taking it out leaves a hole Xaero does
     * not fill until the game restarts); instead its picture is marked as made under other
     * settings, which is Xaero's own cue to reload a region, as after a resource pack change.
     */
    private fun reread(dimension: MapDimension, x: Int, z: Int, layer: Int) {
        try {
            val region = dimension.layeredMapRegions.getLeaf(layer, x, z) ?: return
            synchronized(region) {
                region.setHasHadTerrain()
                region.cacheHashCode = region.cacheHashCode + 1
            }
        } catch (e: Throwable) {
            Log.error("Could not have Xaero read region ${x}_$z again after putting it back", e)
        }
    }

    /** Starts a new batch: the next write to any region copies it again, into a new folder. */
    fun forget() {
        launch = null
        kept.clear()
        origins.clear()
        saveAsked.clear()
    }

    /** Leaving the server: a copy waiting for Xaero is not taken. */
    fun leave() {
        forget()
        snapshot = null
    }

    /** `0_-2.zip` for region 0, -2 — the name Xaero uses. */
    fun regionFile(x: Int, z: Int): String = "${x}_$z.zip"

    /** The region a file name is for, or null if it is not one. */
    fun regionOf(fileName: String): Pair<Int, Int>? {
        val match = REGION.matchEntire(fileName) ?: return null
        val x = match.groupValues[1].toIntOrNull() ?: return null
        val z = match.groupValues[2].toIntOrNull() ?: return null
        return x to z
    }

    private val REGION = Regex("""(-?\d{1,8})_(-?\d{1,8})\.zip""")

    private fun key(x: Int, z: Int) = "${x}_$z"

    /** The name of this batch's copies for [label], to put them back with; null if none were made. */
    fun batchName(label: String): String? = launch?.let { "$it-${SafeFiles.name(label)}" }

    /** This batch's folder for [label], made with its [INFO] the first time. */
    private fun folderForThisLaunch(label: String, origin: Origin): Path? {
        val root = backupRoot() ?: return null
        if (launch == null) launch = LocalDateTime.now().format(STAMP)
        val folder = root.resolve(batchName(label)!!)
        if (label !in origins) {
            Files.createDirectories(folder)
            origin.write(folder)
            origins[label] = origin
        }
        return folder
    }
}
