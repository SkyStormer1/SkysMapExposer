package com.skystormer.skysmapexposer

import net.minecraft.nbt.NbtIo
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream

/**
 * Reads the recorded biome of every [step]th block (in x and z) from one of Xaero's region files,
 * read-only, for [BiomeGuideTrainer]. Written from the file layout; the mod itself never reads
 * Xaero's files, Xaero does.
 */
object XaeroRegionSamples {

    fun read(file: Path, regionX: Int, regionZ: Int, step: Int, found: (x: Int, z: Int, biome: String) -> Unit) {
        val biomes = ArrayList<String>()
        val states = ArrayList<String>()
        ZipInputStream(BufferedInputStream(Files.newInputStream(file))).use { zip ->
            zip.nextEntry
            val input = DataInputStream(zip)
            if (input.read() != 255) throw IOException("old format")
            val version = input.readInt()
            val minor = version and 0xFFFF
            val major = version shr 16 and 0xFFFF
            if (major !in 4..7 || minor !in 5..8) throw IOException("version $major.$minor")
            while (true) {
                val chunk = input.read()
                if (chunk == -1) break
                val o = chunk shr 4
                val p = chunk and 15
                for (i in 0 until 4) for (j in 0 until 4) {
                    val first = input.readInt()
                    if (first == -1) continue
                    var next: Int? = first
                    for (x in 0 until 16) for (z in 0 until 16) {
                        val parameters = next ?: input.readInt()
                        next = null
                        val biome = pixel(parameters, input, minor, biomes, states)
                        val bx = regionX * 512 + o * 64 + i * 16 + x
                        val bz = regionZ * 512 + p * 64 + j * 16 + z
                        if (biome >= 0 && Math.floorMod(bx, step) == 0 && Math.floorMod(bz, step) == 0) {
                            found(bx, bz, biomes[biome])
                        }
                    }
                    if (minor >= 4) input.read()
                    if (minor >= 6) {
                        input.readInt()
                        if (minor >= 7) input.read()
                    }
                }
            }
        }
    }

    /**
     * Reads past one pixel and returns its biome's index in [biomes], or -1 if it has none. Block
     * states and overlay states share one palette, [states], which has to be kept up to date to
     * read on.
     */
    private fun pixel(parameters: Int, input: DataInputStream, minor: Int, biomes: ArrayList<String>, states: ArrayList<String>): Int {
        if (parameters and 1 != 0) state(parameters and 0x200000 != 0, input, states)
        if (parameters and 64 != 0) input.read()
        if (parameters and 0x1000000 != 0) input.read()
        if (parameters and 2 != 0) {
            repeat(input.read()) {
                val overlay = input.readInt()
                if (overlay and 1 != 0) state(overlay and 1024 != 0, input, states)
                if (overlay and 4 != 0) input.readInt()
                if (minor < 8 && overlay and 8 != 0) input.readInt()
            }
        }
        if (parameters and 0x100000 == 0) return -1
        if (parameters and 0x400000 == 0) return input.readInt()
        biomes.add(if (parameters and 0x800000 != 0) "id:${input.readInt()}" else input.readUTF())
        return biomes.size - 1
    }

    private fun state(isNew: Boolean, input: DataInputStream, states: ArrayList<String>) {
        if (isNew) states.add(NbtIo.read(input).getStringOr("Name", "?")) else input.readInt()
    }
}
