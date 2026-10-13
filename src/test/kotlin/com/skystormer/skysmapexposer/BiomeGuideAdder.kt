package com.skystormer.skysmapexposer

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Random

/**
 * Adds blocks of biomes the shipped guide has never seen (26.3's dappled forest) to it, keeping
 * every block it already has. Not a test: it only runs when told where things are:
 *
 * - `SME_ADD_BASE`: the guide to add to.
 * - `SME_ADD_POINTS`: a CSV of `area,x,z,biome`, the biome at the surface of a world that has them.
 * - `SME_ADD_TILES`: that world's finest BlueMap lowres tiles, named `x_z.png`.
 * - `SME_TRAIN_OUT`: where to write the guide.
 * - `SME_ADD_COMPARE` (optional): lowres tiles of a world made before those biomes existed; old and
 *   new guide are compared over it, where the new biomes should never be guessed.
 *
 * Before writing, it checks itself: a guide made without area [HELD_OUT] guesses that area, and
 * the old guide guesses it too, for comparison.
 */
class BiomeGuideAdder {

    private class Point(val area: Int, val x: Int, val z: Int, val biome: String)

    @Test
    fun add() {
        val basePath = System.getenv("SME_ADD_BASE")
        val pointsPath = System.getenv("SME_ADD_POINTS")
        val tiles = System.getenv("SME_ADD_TILES")
        val out = System.getenv("SME_TRAIN_OUT")
        assumeTrue(basePath != null && pointsPath != null && tiles != null && out != null, "not asked to add")

        val base = Files.newInputStream(Paths.get(basePath!!)).use(BiomeGuide::read)
        val (baseRows, baseBiomes) = base.samples()
        println("base: ${base.known} blocks, ${base.biomeCount} biomes")

        val points = Files.readAllLines(Paths.get(pointsPath!!)).filter { it.isNotBlank() }.map {
            val (area, x, z, biome) = it.split(',')
            Point(area.toInt(), x.toInt(), z.toInt(), biome)
        }
        val folder = Paths.get(tiles!!)
        val ground = LowresSource(TILE, hasSea = true) { tx, tz ->
            folder.resolve("${tx}_$tz.png").takeIf(Files::exists)?.let { BlueMapPicture.decode(tx, tz, TILE, Files.readAllBytes(it)) }
        }

        val random = Random(1)
        val learn = points.filter { it.area != HELD_OUT }
        val check = points.filter { it.area == HELD_OUT }
        val withHeldOut = guide(baseRows, baseBiomes, picked(learn, ground, random))
        report("old guide", base, check, ground)
        report("new guide", withHeldOut, check, ground)

        val guide = guide(baseRows, baseBiomes, picked(points, ground, random))
        System.getenv("SME_ADD_COMPARE")?.let { compare(base, guide, Paths.get(it)) }
        val bytes = ByteArrayOutputStream().also { guide.write(it) }.toByteArray()
        Files.write(Paths.get(out!!), bytes)
        println("wrote ${guide.known} blocks, ${guide.biomeCount} biomes, ${bytes.size / 1024} KB to $out")
    }

    /** At most [NEW_BIOME] dappled forest blocks and [CONTEXT] of the other biomes around them. */
    private fun picked(from: List<Point>, ground: LowresSource, random: Random): Pair<List<FloatArray>, List<String>> {
        val rows = ArrayList<FloatArray>()
        val biomes = ArrayList<String>()
        val scratch = FloatArray(5)
        val (new, rest) = from.partition { it.biome == DAPPLED }
        for (point in new.shuffled(random).take(NEW_BIOME) + rest.shuffled(random).take(CONTEXT)) {
            val features = FloatArray(BiomeFeatures.SIZE)
            if (!BiomeFeatures.describe(ground, point.x, point.z, features, scratch)) continue
            rows.add(features)
            biomes.add(point.biome)
        }
        return rows to biomes
    }

    private fun guide(baseRows: List<FloatArray>, baseBiomes: List<String>, added: Pair<List<FloatArray>, List<String>>): BiomeGuide {
        println("adding ${added.first.size} blocks: ${added.second.groupingBy { it }.eachCount().entries.sortedBy { -it.value }.take(6)}")
        return BiomeGuide.build(baseRows + added.first, baseBiomes + added.second)
    }

    private fun report(name: String, guide: BiomeGuide, check: List<Point>, ground: LowresSource) {
        var right = 0
        var total = 0
        var dappled = 0
        var dappledFound = 0
        var guessedDappled = 0
        for ((region, blocks) in check.groupBy { Math.floorDiv(it.x, 512) to Math.floorDiv(it.z, 512) }) {
            val guess = guide.guess(ground, region.first, region.second)
            for (block in blocks) {
                val guessed = guess.at(block.x - region.first * 512, block.z - region.second * 512) ?: continue
                total++
                if (guessed == block.biome) right++
                if (block.biome == DAPPLED) { dappled++; if (guessed == DAPPLED) dappledFound++ }
                if (guessed == DAPPLED) guessedDappled++
            }
        }
        println("%s on area %d: %.1f%% exact over %d blocks; dappled forest found %d of %d, guessed %d times"
            .format(name, HELD_OUT, 100.0 * right / total, total, dappledFound, dappled, guessedDappled))
    }

    /** Old against new over every region of [folder]'s tiles: how often they differ, and dappled guesses. */
    private fun compare(old: BiomeGuide, new: BiomeGuide, folder: java.nio.file.Path) {
        val ground = LowresSource(TILE, hasSea = true) { tx, tz ->
            folder.resolve("${tx}_$tz.png").takeIf(Files::exists)?.let { BlueMapPicture.decode(tx, tz, TILE, Files.readAllBytes(it)) }
        }
        val regions = Files.list(folder).use { files ->
            files.filter { it.fileName.toString().endsWith(".png") }.map { it.fileName.toString().removeSuffix(".png").split('_').map(String::toInt) }
                .toList().flatMap { (tx, tz) -> listOf(Math.floorDiv(tx * TILE, 512) to Math.floorDiv(tz * TILE, 512)) }.distinct()
        }
        var total = 0L
        var differ = 0L
        var dappled = 0L
        for ((rx, rz) in regions) {
            val a = old.guess(ground, rx, rz)
            val b = new.guess(ground, rx, rz)
            for (z in 0 until 512 step 4) for (x in 0 until 512 step 4) {
                val before = a.at(x, z) ?: continue
                val after = b.at(x, z) ?: continue
                total++
                if (before != after) differ++
                if (after == DAPPLED) dappled++
            }
        }
        println("compared over %d regions: %.1f%% of %d blocks guessed differently, %d (%.2f%%) as dappled forest"
            .format(regions.size, 100.0 * differ / total, total, dappled, 100.0 * dappled / total))
    }

    private companion object {
        const val TILE = 500
        const val DAPPLED = "minecraft:dappled_forest"
        const val HELD_OUT = 1
        const val NEW_BIOME = 1500
        const val CONTEXT = 500
    }
}
