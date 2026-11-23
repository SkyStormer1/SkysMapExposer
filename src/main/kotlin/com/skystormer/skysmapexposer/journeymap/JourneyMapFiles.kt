package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.levelKey
import com.skystormer.skysmapexposer.SafeFiles
import com.mojang.blaze3d.platform.NativeImage
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.mixin.journeymap.ImageHolderAccessor
import journeymap.client.io.FileHandler
import journeymap.client.model.map.MapType
import journeymap.client.model.region.ImageHolder
import journeymap.client.model.region.LodChainRegistry
import journeymap.client.model.region.LodRegionCache
import journeymap.client.model.region.RegionCoord
import journeymap.client.model.region.RegionImageCache
import net.minecraft.client.Minecraft
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO

/**
 * JourneyMap's map on disk: one 512-block square picture per region and map type, in
 * `<world>/<dimension>/day/x,z.png`, see-through where it has nothing. Folders come from JourneyMap
 * itself ([FileHandler]); file names only ever from region numbers.
 *
 * JourneyMap keeps the regions near you and on screen in memory and writes them back over the
 * file, so a region it holds ([held]) is never written: what was meant for it waits until it lets go.
 */
internal object JourneyMapFiles {

    const val REGION = 512

    /** JourneyMap's folder for [dimension]'s day map in the world you are in, or null before it has one. */
    fun dayFolder(dimension: String): Path? = dimensionFolder(dimension)?.resolve("day")

    /** JourneyMap's chunk cache of [dimension] in the world you are in: one file per region, `c.x.z.jmc`. */
    fun chunkCacheFolder(dimension: String): Path? = dimensionFolder(dimension)?.resolve("chunk_cache")

    /** JourneyMap's folder for [dimension] in the world you are in: its map types' folders are inside. */
    fun dimensionFolder(dimension: String): Path? {
        val world = FileHandler.getJMWorldDir(Minecraft.getInstance()) ?: return null
        return FileHandler.getDimPath(world, levelKey(dimension))
    }

    fun regionFile(folder: Path, regionX: Int, regionZ: Int): Path = folder.resolve("$regionX,$regionZ.png")

    /** Whether JourneyMap holds region ([regionX], [regionZ]) of the dimension folder [dimensionFolder] in memory. Client thread. */
    fun held(dimensionFolder: Path, regionX: Int, regionZ: Int): Boolean =
        RegionImageCache.INSTANCE.regionImageSets.any { set ->
            val coord = set.regionCoord
            coord.regionX == regionX && coord.regionZ == regionZ && coord.dimDir == dimensionFolder
        }

    /**
     * JourneyMap's day picture of region ([regionX], [regionZ]) of [dimension], as it holds it in
     * memory; null if it does not hold it. Client thread.
     */
    fun heldDay(dimensionFolder: Path, dimension: String, regionX: Int, regionZ: Int): ImageHolder? {
        val set = RegionImageCache.INSTANCE.regionImageSets.firstOrNull { set ->
            val coord = set.regionCoord
            coord.regionX == regionX && coord.regionZ == regionZ && coord.dimDir == dimensionFolder
        } ?: return null
        return set.getExistingHolder(MapType.day(levelKey(dimension)))
    }

    /** [holder]'s picture, `REGION` square, ARGB; null if it has none to read. Client thread. */
    fun pixelsOf(holder: ImageHolder): IntArray? {
        val image = holder.image ?: return null
        if (image.width != REGION || image.height != REGION) return null
        val pixels = IntArray(REGION * REGION)
        for (z in 0 until REGION) for (x in 0 until REGION) pixels[z * REGION + x] = image.getPixel(x, z)
        return pixels
    }

    /**
     * Hands [holder] [pixels] as its new picture, shown at once and saved by JourneyMap
     * ([ImageHolderAccessor]); false if this JourneyMap does not take it. Client thread.
     */
    fun replaceHeld(holder: ImageHolder, pixels: IntArray): Boolean {
        val accessor = (holder as Any) as? ImageHolderAccessor ?: return false
        val image = NativeImage(REGION, REGION, false)
        return try {
            for (z in 0 until REGION) for (x in 0 until REGION) image.setPixel(x, z, pixels[z * REGION + x])
            accessor.`skysmapexposer$setImage`(image)
            true
        } catch (e: LinkageError) {
            image.close()
            false
        } catch (e: RuntimeException) {
            image.close()
            Log.warn("JourneyMap would not take a new picture of a region: {}", e.toString())
            false
        }
    }

    /** Writes [pixels] into [backup] as a held region's picture before it is changed, unless it already has one. */
    fun backUpHeld(pixels: IntArray, backup: Path) {
        val absent = backup.resolveSibling(backup.fileName.toString() + ABSENT)
        if (Files.exists(backup) || Files.exists(absent)) return
        Files.createDirectories(backup.parent)
        write(backup, pixels)
    }

    /** A backup's picture ([backUp], [backUpHeld]): its pixels, all see-through if there was no file; null if there is no backup. */
    fun backupPixels(backup: Path): IntArray? {
        val absent = backup.resolveSibling(backup.fileName.toString() + ABSENT)
        return when {
            Files.exists(backup) -> read(backup)
            Files.exists(absent) -> IntArray(REGION * REGION)
            else -> null
        }
    }

    /**
     * A region's picture, `REGION` square, ARGB; empty (all see-through) if there is no file yet;
     * null if the file is not a region picture this can read, which is then never touched.
     */
    fun read(file: Path): IntArray? {
        if (!Files.exists(file)) return IntArray(REGION * REGION)
        val image = try {
            Files.newInputStream(file).use { ImageIO.read(it) }
        } catch (e: IOException) {
            null
        } ?: return null
        if (image.width != REGION || image.height != REGION) return null
        return image.getRGB(0, 0, REGION, REGION, null, 0, REGION)
    }

    fun write(file: Path, pixels: IntArray) {
        val image = BufferedImage(REGION, REGION, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, REGION, REGION, pixels, 0, REGION)
        val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        SafeFiles.write(file, bytes)
    }

    /**
     * Copies [file] into [backup] before it is changed, or notes there was none, so putting it back
     * can remove what was written. Never copies over an earlier backup of the same file: the first
     * one is the region as it was before any of this.
     */
    fun backUp(file: Path, backup: Path) {
        val absent = backup.resolveSibling(backup.fileName.toString() + ABSENT)
        if (Files.exists(backup) || Files.exists(absent)) return
        Files.createDirectories(backup.parent)
        if (Files.exists(file)) Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES)
        else Files.createFile(absent)
    }

    /** Puts [file] back from [backup]: the copy, or no file if there was none. False if there is no backup of it. */
    fun restore(file: Path, backup: Path): Boolean {
        val absent = backup.resolveSibling(backup.fileName.toString() + ABSENT)
        return when {
            Files.exists(backup) -> { SafeFiles.write(file, Files.readAllBytes(backup)); true }
            Files.exists(absent) -> { Files.deleteIfExists(file); true }
            else -> false
        }
    }

    /**
     * Tells JourneyMap that [dimension]'s day map region ([regionX], [regionZ]) in [folder] was written
     * ([exists]) or taken away outside it, as it tells itself when it saves one: the region goes on (or
     * off) the list of files it draws, which it reads once and so would never show a region new since
     * (a download into a cleared map stayed black at every zoom), and its zoomed-out copies are made
     * again, or it would keep drawing the old ones zoomed out.
     */
    fun written(dimension: String, folder: Path, regionX: Int, regionZ: Int, exists: Boolean) =
        written(MapType.day(levelKey(dimension)), folder, regionX, regionZ, exists)

    /** [written] for any of JourneyMap's map types ([type]), whose region files are in [folder]. */
    fun written(type: MapType, folder: Path, regionX: Int, regionZ: Int, exists: Boolean) {
        try {
            if (exists) LodChainRegistry.INSTANCE.registerRegion(type, regionX, regionZ)
            else LodChainRegistry.INSTANCE.removeRegion(type, regionX, regionZ)
            LodChainRegistry.INSTANCE.invalidate(type, regionX, regionZ, folder.toFile())
            val world = FileHandler.getJMWorldDir(Minecraft.getInstance()) ?: return
            val coord = RegionCoord.fromRegionPos(world, regionX, regionZ, type.dimension)
            for (lod in 1..MAX_LOD) LodRegionCache.INSTANCE.invalidate(coord, type, lod)
        } catch (e: Throwable) {
            if (!warnedWritten) Log.warn("Could not tell JourneyMap about a region written into its map; it shows once you rejoin: {}", e.toString())
            warnedWritten = true
        }
    }

    @Volatile private var warnedWritten = false

    /** JourneyMap's levels of zoomed-out copies, as its own regions invalidate them. */
    private const val MAX_LOD = 7

    /** Beside a backup: there was no file before. */
    private const val ABSENT = ".none"
}
