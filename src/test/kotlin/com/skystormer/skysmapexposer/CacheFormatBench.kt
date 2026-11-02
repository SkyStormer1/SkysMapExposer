package com.skystormer.skysmapexposer

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.Deflater

/**
 * Measures the ready-to-draw tile file ([TileFile]) against the PNGs BlueMap sends, on real tiles.
 * Not a test: it only runs when `SME_BENCH_TILES` names a folder of finest lowres tiles (`x_z.png`).
 * Prints, per variant: size on disk against the PNGs, and load time for the sizes the maps ask for
 * most against reading, decoding, shading and shrinking the PNG.
 */
class CacheFormatBench {

    @Test
    fun measure() {
        val folder = System.getenv("SME_BENCH_TILES")
        assumeTrue(folder != null, "not asked to measure")
        val pngs = Files.list(Paths.get(folder!!)).use { s -> s.filter { it.fileName.toString().endsWith(".png") }.sorted().toList() }
        val sample = pngs.filterIndexed { i, _ -> i % maxOf(1, pngs.size / 60) == 0 }.take(60)
        val tileSize = 500
        val factors = TilePixels.factorsFor(tileSize)
        val scratch = Files.createTempDirectory("smet-bench")

        fun pngLoad(file: Path, factor: Int): IntArray {
            val colours = TilePixels.decode(Files.readAllBytes(file), tileSize, 1)
            return TilePixels.shrink(colours, tileSize + 1, tileSize, factor)
        }

        // Warm up the JIT on both paths.
        repeat(3) { for (f in sample.take(10)) pngLoad(f, 1) }

        val pngBytes = sample.sumOf { Files.size(it) }
        val pngTime = LongArray(3)
        val asked = listOf(1, 4, 20)
        for (file in sample) for ((i, factor) in asked.withIndex()) {
            val t = System.nanoTime(); pngLoad(file, factor); pngTime[i] += System.nanoTime() - t
        }
        println("Tiles: ${sample.size} of ${pngs.size}; PNGs ${pngBytes / 1024} KB")
        println("PNG load (read, decode, shade, shrink) ms per tile at 1:1, 1:4, 1:20: " +
            pngTime.joinToString { "%.2f".format(it / 1e6 / sample.size) })

        for (layout in TileFile.Layout.values()) for (level in listOf(Deflater.BEST_SPEED, 6, Deflater.BEST_COMPRESSION)) {
            var total = 0L
            var finest = 0L
            var buildNanos = 0L
            val files = sample.map { png ->
                val colours = TilePixels.decode(Files.readAllBytes(png), tileSize, 1)
                val t = System.nanoTime()
                val bytes = TileFile.encode("x", colours, tileSize + 1, tileSize, factors, level, layout)
                buildNanos += System.nanoTime() - t
                total += bytes.size
                val out = scratch.resolve("${layout}_${level}_${png.fileName}.smet")
                Files.write(out, bytes)
                finest += TileFile.header(out).sections.getValue(1)[1]
                out
            }
            repeat(2) { for (f in files.take(10)) TileFile.pixels(f, 1, layout) }
            val times = LongArray(3)
            for (file in files) for ((i, factor) in asked.withIndex()) {
                val t = System.nanoTime()
                TileFile.presence(file)
                TileFile.pixels(file, factor, layout)
                times[i] += System.nanoTime() - t
            }
            println(
                "%-6s level %d: all sizes %.2f× PNG, 1:1 alone %.2f×; load ms %s (%s× faster); build %.1f ms".format(
                    layout, level, total.toDouble() / pngBytes, finest.toDouble() / pngBytes,
                    times.joinToString { "%.2f".format(it / 1e6 / sample.size) },
                    times.indices.joinToString("/") { "%.1f".format(pngTime[it].toDouble() / times[it]) },
                    buildNanos / 1e6 / sample.size,
                ),
            )
        }
        // Temporary memory per load, for 500-block tiles: the PNG path holds the decoded image,
        // its pixel array and the shaded picture; the file path the inflated bytes and the pixels.
        val image = 501L * 1002 * 4
        println("Peak extra memory per load: PNG about ${(image * 2 + 501L * 501 * 4) / 1024} KB; tile file at 1:1 about ${(500L * 500 * 7) / 1024} KB")
        scratch.toFile().deleteRecursively()
    }
}
