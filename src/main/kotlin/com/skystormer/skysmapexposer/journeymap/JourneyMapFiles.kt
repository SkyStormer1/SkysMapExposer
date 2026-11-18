package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.SafeFiles
import journeymap.client.io.FileHandler
import journeymap.client.model.region.RegionImageCache
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
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
    fun dayFolder(dimension: String): Path? {
        val world = FileHandler.getJMWorldDir(Minecraft.getInstance()) ?: return null
        return FileHandler.getDimPath(world, keyOf(dimension))?.resolve("day")
    }

    fun regionFile(folder: Path, regionX: Int, regionZ: Int): Path = folder.resolve("$regionX,$regionZ.png")

    /** Whether JourneyMap holds region ([regionX], [regionZ]) of the dimension folder [dimensionFolder] in memory. Client thread. */
    fun held(dimensionFolder: Path, regionX: Int, regionZ: Int): Boolean =
        RegionImageCache.INSTANCE.regionImageSets.any { set ->
            val coord = set.regionCoord
            coord.regionX == regionX && coord.regionZ == regionZ && coord.dimDir == dimensionFolder
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

    private fun keyOf(dimension: String): ResourceKey<net.minecraft.world.level.Level> =
        ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension))

    /** Beside a backup: there was no file before. */
    private const val ABSENT = ".none"
}
