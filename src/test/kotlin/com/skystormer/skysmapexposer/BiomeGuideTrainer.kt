package com.skystormer.skysmapexposer

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Random

/**
 * Makes the biome guide that ships with the mod (`src/main/resources/skysmapexposer/biomes/`),
 * from a Xaero map whose biomes were recorded in the game and the BlueMap lowres tiles over it.
 * Not a test: it only runs when told where those are, through the environment:
 *
 * - `SME_TRAIN_XAERO`: a folder of Xaero region files (`x_z.zip`), read-only.
 * - `SME_TRAIN_TILES`: folders of finest lowres tiles, named `x_z.png` or `t_x_z.png`, `;` between.
 * - `SME_TRAIN_OUT`: where to write the guide.
 *
 * Before writing, it checks itself the honest way: a guide made from half the regions (a
 * checkerboard) guesses the other half, and the score is printed.
 */
class BiomeGuideTrainer {

    private class Known(val x: Int, val z: Int, val biome: String, val regionX: Int, val regionZ: Int)

    @Test
    fun train() {
        val xaero = System.getenv("SME_TRAIN_XAERO")
        val tiles = System.getenv("SME_TRAIN_TILES")
        val out = System.getenv("SME_TRAIN_OUT")
        assumeTrue(xaero != null && tiles != null && out != null, "not asked to train")

        val known = ArrayList<Known>()
        Files.newDirectoryStream(Paths.get(xaero!!), "*_*.zip").use { files ->
            for (file in files) {
                val (rx, rz) = file.fileName.toString().removeSuffix(".zip").split('_').map(String::toInt)
                try {
                    XaeroRegionSamples.read(file, rx, rz, STEP) { x, z, biome ->
                        if (!biome.startsWith("minecraft:")) return@read
                        known.add(Known(x, z, biome, rx, rz))
                    }
                } catch (e: Exception) {
                    println("skipped ${file.fileName}: $e")
                }
            }
        }
        println("${known.size} blocks with a recorded biome")

        val folders = tiles!!.split(';').map { Paths.get(it) }
        val ground = LowresSource(TILE, hasSea = true) { tx, tz -> loadTile(folders, tx, tz) }
        val random = Random(1)

        // Held-out check: learn from even regions, guess the odd ones.
        val learnFrom = known.filter { (it.regionX + it.regionZ) and 1 == 0 }
        val check = known.filter { (it.regionX + it.regionZ) and 1 != 0 }
        val halfGuide = guideFrom(learnFrom, ground, random)
        var right = 0
        var total = 0
        val byRegion = check.groupBy { it.regionX to it.regionZ }
        for ((region, blocks) in byRegion.entries.shuffled(random).take(CHECK_REGIONS)) {
            val guess = halfGuide.guess(ground, region.first, region.second)
            for (block in blocks) {
                val guessed = guess.at(block.x - region.first * 512, block.z - region.second * 512) ?: continue
                total++
                if (guessed == block.biome) right++
            }
        }
        println("HELD OUT: %.1f%% exact over %d blocks in %d regions".format(100.0 * right / total, total, minOf(CHECK_REGIONS, byRegion.size)))

        val guide = guideFrom(known, ground, random)
        val bytes = ByteArrayOutputStream().also { guide.write(it) }.toByteArray()
        Files.createDirectories(Paths.get(out!!).parent)
        Files.write(Paths.get(out), bytes)
        println("wrote ${guide.known} blocks, ${guide.biomeCount} biomes, ${bytes.size / 1024} KB to $out")
    }

    private fun guideFrom(from: List<Known>, ground: LowresSource, random: Random): BiomeGuide {
        val picked = from.shuffled(random).take(ROWS * 2)
            .sortedWith(compareBy({ Math.floorDiv(it.x, TILE) }, { Math.floorDiv(it.z, TILE) }))
        val samples = ArrayList<FloatArray>()
        val biomes = ArrayList<String>()
        val scratch = FloatArray(5)
        for (block in picked) {
            val features = FloatArray(BiomeFeatures.SIZE)
            if (!BiomeFeatures.describe(ground, block.x, block.z, features, scratch)) continue
            samples.add(features)
            biomes.add(block.biome)
        }
        val keep = samples.indices.shuffled(random).take(ROWS)
        return BiomeGuide.build(keep.map { samples[it] }, keep.map { biomes[it] })
    }

    private fun loadTile(dirs: List<Path>, tx: Int, tz: Int): BlueMapPicture.Tile? {
        for (dir in dirs) {
            for (name in listOf("${tx}_$tz.png", "t_${tx}_$tz.png")) {
                val file = dir.resolve(name)
                if (Files.exists(file)) return BlueMapPicture.decode(tx, tz, TILE, Files.readAllBytes(file))
            }
        }
        return null
    }

    private companion object {
        const val STEP = 8
        const val TILE = 500
        const val ROWS = 12_000
        const val CHECK_REGIONS = 12
    }
}
