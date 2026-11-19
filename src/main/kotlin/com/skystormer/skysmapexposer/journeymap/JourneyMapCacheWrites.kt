package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.isWaterBlock
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.SharedTerrain
import journeymap.common.chunk.JMChunkCache
import journeymap.common.chunk.JMChunkSnapshot
import journeymap.common.chunk.JMChunkStorage
import net.minecraft.client.Minecraft
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.DataLayer
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainerFactory
import net.minecraft.world.level.levelgen.Heightmap
import java.lang.reflect.Constructor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.EnumMap
import java.util.concurrent.Executors

/**
 * Downloaded and shared map areas written into JourneyMap's chunk cache, as JourneyMap writes the
 * chunks you explore, so they are there for whatever reads it: saving a terrain file
 * ([JourneyMapTerrainSave]), JourneyMap's line under the mouse, and its own redrawing. Only the top
 * of each column is known (its block, height, biome, light and any water over it), so these chunks
 * hold that and air beneath.
 *
 * Never over a chunk JourneyMap cached itself: only where it has none, or over one of these, so the
 * real blocks of what you explored always win, and exploring a chunk replaces one of these with the
 * real thing. Each is marked in the block light of its lowest layer (which lies in bedrock or void
 * in any real chunk, unlit), so it can be told apart: [ADDED], or [WITHDRAWN] once an undo has taken
 * it back. JourneyMap's cache cannot drop a chunk, so an undo writes an empty one in its place
 * ([withdraw]), which saving leaves out.
 *
 * Written through JourneyMap's own cache for the dimension it has open (the one you are in), and
 * through a cache store of its kind for any other, which JourneyMap does not have open then.
 * Hooks into JourneyMap's inner classes: if they change, this stops with a line in the log, and
 * only saving loses the downloaded and shared areas, as before.
 */
internal object JourneyMapCacheWrites {

    enum class Kind { ABSENT, REAL, ADDED, WITHDRAWN }

    /** Where the mark goes in the lowest layer, and its two forms. */
    private val MARK_AT = arrayOf(intArrayOf(0, 0), intArrayOf(15, 15), intArrayOf(0, 15), intArrayOf(15, 0))
    private val ADDED = intArrayOf(15, 9, 4, 12)
    private val WITHDRAWN = intArrayOf(15, 9, 4, 11)

    /** False once JourneyMap's classes turned out not to be as expected; nothing more is tried. */
    @Volatile
    var working = true
        private set

    private val construct: Constructor<JMChunkSnapshot>? by lazy {
        try {
            JMChunkSnapshot::class.java.getDeclaredConstructor(
                ChunkPos::class.java, Array<LevelChunkSection>::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Map::class.java, Array<DataLayer>::class.java,
            ).apply { isAccessible = true }
        } catch (e: Throwable) {
            stop("JourneyMap's chunk snapshot is not built as expected", e)
            null
        }
    }

    private fun stop(why: String, e: Throwable) {
        if (!working) return
        working = false
        Log.warn("Downloaded and shared areas will not be kept in JourneyMap's chunk cache ({}): {}", why, e.toString())
    }

    /** Which kind chunk [chunk] of [JourneyMapChunkCache] is, read with its light. */
    fun kindOf(chunk: JourneyMapChunkCache.Chunk?): Kind {
        chunk ?: return Kind.ABSENT
        return kindByLight { x, z -> chunk.lightAt(x, chunk.minBuild, z) }
    }

    private fun kindOf(snapshot: JMChunkSnapshot): Kind = try {
        val bottom = snapshot.getBlockLight(0)
        if (bottom == null) Kind.REAL else kindByLight { x, z -> bottom.get(x, 0, z) }
    } catch (e: Throwable) {
        Kind.REAL
    }

    private inline fun kindByLight(light: (Int, Int) -> Int): Kind {
        val seen = IntArray(4) { light(MARK_AT[it][0], MARK_AT[it][1]) }
        return when {
            seen.contentEquals(ADDED) -> Kind.ADDED
            seen.contentEquals(WITHDRAWN) -> Kind.WITHDRAWN
            else -> Kind.REAL
        }
    }

    /**
     * One job's writes into [dimension]'s cache, on a thread of their own, in the order handed over;
     * the chunks written are listed in [ledger] for an undo.
     */
    class Batch(private val dimension: String, private val ledger: Path) {
        private val thread = Executors.newSingleThreadExecutor { Thread(it, "SkysMapExposer JourneyMap cache").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
        private var target: Target? = null
        @Volatile var written = 0
            private set
        private val waiting = java.util.concurrent.atomic.AtomicInteger()

        /** Regions handed over and not yet written. */
        val pending: Int get() = waiting.get()

        /**
         * The columns of [region] that [keep] (`x * 16 + z` within each chunk, by chunk index) allows,
         * into the cache wherever it has no chunk of its own.
         */
        fun add(region: SharedTerrain.Region, keep: (chunkIndex: Int, column: Int) -> Boolean) {
            if (!working) return
            waiting.incrementAndGet()
            thread.execute {
                try {
                    val target = target ?: Target.of(dimension)?.also { target = it } ?: return@execute
                    val kinds = target.kinds(region.regionX, region.regionZ)
                    val lines = StringBuilder()
                    for (index in 0 until 32 * 32) {
                        val chunk = region.chunks[index] ?: continue
                        if (kinds[index] == Kind.REAL) continue
                        val cx = region.regionX * 32 + (index and 31)
                        val cz = region.regionZ * 32 + (index shr 5)
                        val snapshot = target.build(cx, cz, chunk, { keep(index, it) }, ADDED) ?: continue
                        // JourneyMap may have mapped it for real since the region was read.
                        if (!target.stillFree(cx, cz)) continue
                        target.put(snapshot)
                        written++
                        lines.append(cx).append(' ').append(cz).append('\n')
                    }
                    if (lines.isNotEmpty()) Files.writeString(ledger, lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                } catch (e: Throwable) {
                    stop("writing region ${region.regionX}, ${region.regionZ}", e)
                } finally {
                    waiting.decrementAndGet()
                }
            }
        }

        /** Once everything handed over is written: the files closed, and [done] told how many chunks went in. */
        fun close(done: (Int) -> Unit) {
            thread.execute {
                try { target?.close() } catch (e: Throwable) { Log.warn("Could not close JourneyMap's chunk cache store: {}", e.toString()) }
                done(written)
            }
            thread.shutdown()
        }
    }

    /**
     * Takes back the chunks an undone job wrote (listed in its [ledger]) from [dimension]'s cache:
     * each still as written becomes an empty marked one; any explored since is left alone. Off the
     * client thread; how many were taken back.
     */
    fun withdraw(dimension: String, ledger: Path): Int {
        if (!working || !Files.isRegularFile(ledger)) return 0
        val target = Target.of(dimension) ?: return 0
        var taken = 0
        try {
            val byRegion = Files.readAllLines(ledger).mapNotNull { line ->
                line.trim().split(' ').takeIf { it.size == 2 }?.let { (x, z) -> x.toIntOrNull()?.let { cx -> z.toIntOrNull()?.let { cz -> cx to cz } } }
            }.toSet().groupBy { (cx, cz) -> Math.floorDiv(cx, 32) to Math.floorDiv(cz, 32) }
            val empty = SharedTerrain.SharedChunk()
            for ((region, chunks) in byRegion) {
                val kinds = target.kinds(region.first, region.second)
                for ((cx, cz) in chunks) {
                    val index = (cz - region.second * 32) * 32 + (cx - region.first * 32)
                    if (kinds[index] != Kind.ADDED) continue
                    target.put(target.build(cx, cz, empty, { false }, WITHDRAWN, evenIfEmpty = true) ?: continue)
                    taken++
                }
            }
            Files.deleteIfExists(ledger)
        } catch (e: Throwable) {
            stop("taking back an undone job's chunks", e)
        } finally {
            try { target.close() } catch (e: Throwable) { Log.warn("Could not close JourneyMap's chunk cache store: {}", e.toString()) }
        }
        return taken
    }

    /** Where [dimension]'s chunks go: JourneyMap's own open cache, or a store of the folder. */
    private class Target(
        private val dimension: String,
        private val folder: Path,
        private val registries: RegistryAccess,
        private val minBuild: Int,
        private val maxBuild: Int,
    ) {
        /**
         * Whether JourneyMap has this dimension's cache open (you are in it), asked at each use: a
         * trip through a portal during a job changes it, and its files must never have two writers.
         */
        private val open: Boolean
            get() = Minecraft.getInstance().level?.dimension()?.identifier()?.toString() == dimension

        private val factory = PalettedContainerFactory.create(registries)
        private val biomes = registries.lookupOrThrow(Registries.BIOME)
        private val holders = HashMap<ResourceKey<Biome>, Holder<Biome>?>()
        private var store: JMChunkStorage? = null

        companion object {
            fun of(dimension: String): Target? {
                val level = Minecraft.getInstance().level ?: return null
                val folder = JourneyMapFiles.chunkCacheFolder(dimension) ?: return null
                val open = level.dimension().identifier().toString() == dimension
                val (min, max) = if (open) level.minY to level.maxY + 1 else heights(level.registryAccess(), dimension)
                return Target(dimension, folder, level.registryAccess(), min, max)
            }

            /** The build heights of [dimension], from its dimension type; the usual ones if unknown. */
            private fun heights(registries: RegistryAccess, dimension: String): Pair<Int, Int> {
                val type = try {
                    registries.lookupOrThrow(Registries.DIMENSION_TYPE).getValue(Identifier.parse(dimension))
                } catch (e: Exception) {
                    null
                }
                if (type != null) return type.minY() to type.minY() + type.height()
                return if (dimension == "minecraft:overworld") -64 to 320 else 0 to 256
            }
        }

        /** Each chunk of region ([rx], [rz]) by kind, `z * 32 + x`: from the file, and JourneyMap's memory where it has newer. */
        fun kinds(rx: Int, rz: Int): Array<Kind> {
            // Only the light is needed, for the mark: the blocks are left undecoded.
            val read = JourneyMapChunkCache.readRegion(JourneyMapChunkCache.file(folder, rx, rz), registries, withLight = true)
            val kinds = Array(32 * 32) { kindOf(read[it]) }
            if (open) for (i in kinds.indices) {
                val snapshot = JMChunkCache.INSTANCE.getMemOnly(ChunkPos(rx * 32 + (i and 31), rz * 32 + (i shr 5))) ?: continue
                kinds[i] = kindOf(snapshot)
            }
            return kinds
        }

        /** False if JourneyMap now holds a chunk of its own at ([cx], [cz]) in memory. */
        fun stillFree(cx: Int, cz: Int): Boolean {
            if (!open) return true
            val held = JMChunkCache.INSTANCE.getMemOnly(ChunkPos(cx, cz)) ?: return true
            return kindOf(held) != Kind.REAL
        }

        /**
         * Chunk ([cx], [cz]) holding the columns of [chunk] that [keep] allows, marked [mark]; null
         * if none are, unless [evenIfEmpty].
         */
        fun build(cx: Int, cz: Int, chunk: SharedTerrain.SharedChunk, keep: (Int) -> Boolean, mark: IntArray, evenIfEmpty: Boolean = false): JMChunkSnapshot? {
            val make = construct ?: return null
            val count = (maxBuild - minBuild) shr 4
            val minSection = minBuild shr 4
            val biomeLayers = Array(count) { factory.createForBiomes() }
            val sections = Array(count) { LevelChunkSection(factory.createForBlockStates(), biomeLayers[it]) }
            val light = arrayOfNulls<DataLayer>(count)
            val surface = IntArray(256) { minBuild }
            val cellBiome = arrayOfNulls<Holder<Biome>>(16)
            var any = false
            fun set(x: Int, y: Int, z: Int, state: net.minecraft.world.level.block.state.BlockState) {
                if (y < minBuild || y >= maxBuild) return
                sections[(y shr 4) - minSection].setBlockState(x, y and 15, z, state, false)
            }
            for (x in 0 until 16) for (z in 0 until 16) {
                val i = x * 16 + z
                val state = chunk.state[i] ?: continue
                if (!keep(i)) continue
                any = true
                val ground = chunk.height[i].toInt()
                set(x, ground, z, state)
                // Over the ground, as deep as the file has it: water from the bottom, and any other
                // layer (ice, glass, leaves) a block each at the top.
                val overlays = chunk.overlays[i].orEmpty()
                var top = maxOf(ground, chunk.top[i].toInt())
                if (top == ground && overlays.isNotEmpty()) top = ground + overlays.sumOf { if (isWaterBlock(it.state)) it.opacity.coerceAtLeast(1) else 1 }
                val solid = overlays.filterNot { isWaterBlock(it.state) }
                val water = overlays.firstOrNull { isWaterBlock(it.state) }
                var y = ground + 1
                val waterTop = top - solid.size
                if (water != null) while (y <= waterTop) set(x, y++, z, water.state)
                for (layer in solid) set(x, y++, z, layer.state)
                val highest = maxOf(ground, y - 1)
                surface[x + z * 16] = (highest + 1).coerceIn(minBuild, maxBuild)
                val lit = chunk.light[i].toInt() and 15
                if (lit > 0 && highest + 1 < maxBuild) {
                    val above = highest + 1
                    (light[(above shr 4) - minSection] ?: DataLayer().also { light[(above shr 4) - minSection] = it }).set(x, above and 15, z, lit)
                }
                val cell = (x shr 2) * 4 + (z shr 2)
                if (cellBiome[cell] == null) cellBiome[cell] = chunk.biome[i]?.let(::holder)
            }
            if (!any && !evenIfEmpty) return null
            // Each 4 × 4 cell of columns gets the biome of the first column of it known, at every height.
            for (cell in 0 until 16) {
                val biome = cellBiome[cell] ?: continue
                for (layer in biomeLayers) for (by in 0 until 4) layer.set(cell shr 2, by, cell and 3, biome)
            }
            val bottom = light[0] ?: DataLayer().also { light[0] = it }
            for (m in MARK_AT.indices) bottom.set(MARK_AT[m][0], 0, MARK_AT[m][1], mark[m])
            val heights = EnumMap<Heightmap.Types, IntArray>(Heightmap.Types::class.java)
            heights[Heightmap.Types.WORLD_SURFACE] = surface
            heights[Heightmap.Types.MOTION_BLOCKING] = surface.copyOf()
            return make.newInstance(ChunkPos(cx, cz), sections, minSection, minBuild, maxBuild, heights, light)
        }

        fun put(snapshot: JMChunkSnapshot) {
            if (open) {
                // JourneyMap opened it since this job began: this job's own store lets go first.
                store?.let { it.flush(); it.close() }
                store = null
                JMChunkCache.INSTANCE.put(snapshot, registries)
            } else {
                (store ?: JMChunkStorage(folder).also { store = it }).write(snapshot, registries)
            }
        }

        fun close() {
            if (open) JMChunkCache.INSTANCE.flush()
            store?.let { it.flush(); it.close() }
            store = null
        }

        private fun holder(key: ResourceKey<Biome>): Holder<Biome>? = holders.getOrPut(key) { biomes.get(key).orElse(null) }
    }

    /** The ledger of a job whose backups go in [backups], listing the chunks it put in the cache. */
    fun ledgerIn(backups: Path): Path = backups.resolve(LEDGER)

    private const val LEDGER = "journeymap-cache.txt"
}
