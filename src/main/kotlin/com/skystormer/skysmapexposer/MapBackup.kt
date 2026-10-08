package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import xaero.map.WorldMapSession
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
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
 * [snapshot] takes every surface region of the dimension on screen at once
 * (`/mapexposer backup`), for a line to retreat to whenever you like.
 *
 * Restoring puts the file back and then drops the region out of Xaero's memory, because Xaero holds
 * regions open and would otherwise carry on drawing, and later saving, the copy it already had.
 */
object MapBackup {

    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    /** This batch's time stamp, taken once its first copy is needed. */
    private var launch: String? = null

    /**
     * Regions already copied in this batch, so a region is only ever saved in its first state. A
     * batch is one download ([forget] starts the next), or else one visit to the server.
     */
    private val kept = HashSet<String>()

    /**
     * The name a batch's folder carries for [dimension]'s cave [layer]: just the dimension for the
     * surface, so [restore] knows which of Xaero's folders the copies go back into.
     */
    fun label(dimension: String, layer: Int): String = if (layer == XaeroWriter.SURFACE) dimension else "$dimension$LAYER$layer"

    private const val LAYER = "_cave_layer_"
    private val LAYER_SUFFIX = Regex(LAYER + "(-?\\d+)$")

    /** Where Xaero keeps the regions of the dimension the map is showing. */
    fun dimensionFolder(): Path? {
        val dimension = WorldMapSession.getCurrentSession()?.mapProcessor?.mapWorld?.currentDimension ?: return null
        val multiworld = dimension.currentMultiworld ?: return null
        return dimension.mainFolderPath?.resolve(multiworld)
    }

    /**
     * Where this mod keeps its copies for the server you are on — also on one with no BlueMap,
     * where a map shared in chat can still be added.
     */
    fun backupRoot(): Path? = (Session.current?.folder
        ?: MapExposerClient.addressOf(Minecraft.getInstance())?.let(Session::folderFor))?.resolve("backups")

    /**
     * Copies region ([x], [z]) out of Xaero's region folder [from] if it has not been copied
     * already in this batch, into this batch's folder for [label] — the dimension, so that two
     * dimensions' regions of the same name never land on each other. Returns whether a copy exists
     * afterwards, which is also true when there is no file yet: a region that does not exist is
     * restored by deleting whatever gets written, and [restore] does that.
     */
    fun keep(from: Path, label: String, x: Int, z: Int): Boolean {
        val id = "$from|${key(x, z)}"
        if (id in kept) return true
        return try {
            val source = from.resolve(regionFile(x, z))
            val into = folderForThisLaunch(label) ?: return false
            Files.createDirectories(into)
            if (Files.exists(source)) {
                val copy = into.resolve(regionFile(x, z))
                Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING)
                // A copy that is not the whole file is no backup.
                if (Files.size(copy) != Files.size(source)) throw IOException("the copy of ${x}_$z came out a different size")
            } else {
                // Nothing there yet: remember that, so restoring removes whatever we add.
                Files.writeString(into.resolve(regionFile(x, z) + ".absent"), "")
            }
            kept.add(id)
            true
        } catch (e: Throwable) {
            Log.error("Could not copy region ${x}_$z before writing to it", e)
            false
        }
    }

    /**
     * Copies every surface region of the dimension on screen. The caves and the derived caches are
     * left out: nothing here writes to them, and they are the great bulk of the folder.
     */
    fun snapshot(): Pair<String, Int>? {
        return try {
            val from = dimensionFolder() ?: return null
            val name = LocalDateTime.now().format(STAMP) + "-full"
            val into = backupRoot()?.resolve(name) ?: return null
            Files.createDirectories(into)
            var copied = 0
            Files.newDirectoryStream(from) { it.fileName.toString().endsWith(".zip") }.use { entries ->
                for (entry in entries) {
                    if (!Files.isRegularFile(entry)) continue
                    Files.copy(entry, into.resolve(entry.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
                    copied++
                }
            }
            name to copied
        } catch (e: Throwable) {
            Log.error("Could not take a copy of the map", e)
            null
        }
    }

    /** Every copy that has been kept, newest first. */
    fun snapshots(): List<String> = try {
        val root = backupRoot() ?: return emptyList()
        if (Files.notExists(root)) return emptyList()
        Files.list(root).use { entries ->
            entries.filter(Files::isDirectory).map { it.fileName.toString() }.sorted().toList().reversed()
        }
    } catch (e: Throwable) {
        Log.error("Could not list the copies of the map", e)
        emptyList()
    }

    /**
     * Puts [name]'s regions back into the dimension on screen and drops them out of Xaero's memory
     * so it reads them again. Returns how many files were put back, or -1 if the copy could not be
     * read.
     */
    /**
     * Regions the last [restore] put back right around you, which Xaero keeps writing what you see
     * into and does not read again until you move away or restart.
     */
    var nearYou = 0
        private set

    fun restore(name: String): Int {
        val root = backupRoot() ?: return -1
        val from = root.resolve(name)
        // A name is one of ours or it is nothing: no walking out of the folder with it.
        if (from.normalize().parent != root.normalize() || Files.notExists(from)) return -1
        val layer = LAYER_SUFFIX.find(name)?.groupValues?.get(1)?.toIntOrNull() ?: XaeroWriter.SURFACE
        val into = XaeroWriter.layerFolder(dimensionFolder() ?: return -1, layer)
        return try {
            var restored = 0
            nearYou = 0
            val player = Minecraft.getInstance().player
            Files.list(from).use { entries ->
                for (entry in entries) {
                    if (!Files.isRegularFile(entry)) continue
                    val fileName = entry.fileName.toString()
                    val absent = fileName.endsWith(".absent")
                    val regionName = if (absent) fileName.removeSuffix(".absent") else fileName
                    val region = regionOf(regionName) ?: continue
                    if (absent) Files.deleteIfExists(into.resolve(regionName))
                    else Files.copy(entry, into.resolve(regionName), StandardCopyOption.REPLACE_EXISTING)
                    forgetPicture(into, regionName.removeSuffix(".zip"))
                    reread(region.first, region.second, layer)
                    restored++
                    if (player != null && kotlin.math.abs(region.first - (player.blockX shr 9)) <= 1 &&
                        kotlin.math.abs(region.second - (player.blockZ shr 9)) <= 1
                    ) nearYou++
                }
            }
            restored
        } catch (e: Throwable) {
            Log.error("Could not put the copy '$name' back", e)
            -1
        }
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

    /** The copies there are, newest first. */
    fun names(): List<String> = try {
        val root = backupRoot()
        if (root == null || Files.notExists(root)) emptyList()
        else Files.list(root).use { entries -> entries.toList().filter { Files.isDirectory(it) }.map { it.fileName.toString() }.sortedDescending() }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Has Xaero read region ([x], [z]) of [layer] from its file again, the next time it is on
     * screen. The region stays where Xaero keeps it (taking it out leaves a hole Xaero does not fill
     * until the game restarts); instead its picture is marked as made under other settings, which
     * is Xaero's own cue to reload a region, as after a resource pack change.
     */
    private fun reread(x: Int, z: Int, layer: Int) {
        try {
            val processor = WorldMapSession.getCurrentSession()?.mapProcessor ?: return
            val region = processor.getLeafMapRegion(layer, x, z, false) ?: return
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

    private fun folderForThisLaunch(label: String): Path? {
        val root = backupRoot() ?: return null
        if (launch == null) launch = LocalDateTime.now().format(STAMP)
        return root.resolve(batchName(label)!!)
    }
}
