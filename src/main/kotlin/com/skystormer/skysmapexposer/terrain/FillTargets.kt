package com.skystormer.skysmapexposer.terrain

import com.seibel.distanthorizons.api.DhApi
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper
import de.johni0702.minecraft.bobby.FakeChunkManager
import de.johni0702.minecraft.bobby.FakeChunkStorage
import de.johni0702.minecraft.bobby.ext.ClientChunkCacheExt
import me.cortex.voxy.common.world.WorldSection
import me.cortex.voxy.common.world.other.Mapper
import me.cortex.voxy.common.world.service.VoxelIngestService
import me.cortex.voxy.commonImpl.VoxyCommon
import me.cortex.voxy.commonImpl.WorldIdentifier
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.PalettedContainerFactory
import net.minecraft.world.level.chunk.UpgradeData
import net.minecraft.world.ticks.LevelChunkTicks
import java.util.concurrent.TimeUnit

/**
 * Somewhere rebuilt chunks go: Bobby, Voxy or Distant Horizons. Each is touched only when that mod
 * is installed (its classes are compiled against, never shipped), and each only gets chunks it
 * has nothing better for.
 */
interface FillTarget {
    val name: String

    /** Whether this chunk should be built for this mod: never over real data. Called off the client thread. */
    fun wants(pos: ChunkPos): Boolean

    /** Hands one chunk over. Called off the client thread. */
    fun accept(chunk: BuiltChunk)

    /** Whether the mod is still working through what it was given, so the fill should wait. */
    fun busy(): Boolean = false

    /** After each small area. */
    fun afterBatch() {}

    companion object {
        fun isInstalled(mod: String): Boolean = FabricLoader.getInstance().isModLoaded(mod)

        /** The installed mods switched on in the settings. */
        fun chosen(): List<String> = installed().filter { it !in com.skystormer.skysmapexposer.Config.fillOff }

        /** The installed mods' names, for the settings screen. */
        fun installed(): List<String> = buildList {
            if (isInstalled("bobby")) add("Bobby")
            if (isInstalled("voxy")) add("Voxy")
            if (isInstalled("distanthorizons")) add("Distant Horizons")
        }

        /**
         * The targets ready in [level] right now, with a note for each installed one that is not.
         * Call on the client thread. Each mod is asked itself whether it already has a chunk.
         */
        fun ready(level: ClientLevel, factory: PalettedContainerFactory): Pair<List<FillTarget>, List<String>> {
            val targets = ArrayList<FillTarget>()
            val notes = ArrayList<String>()
            if ("Bobby" in chosen()) BobbyTarget.create(level, factory).fold({ targets += it }, { notes += "Bobby: ${it.message}" })
            if ("Voxy" in chosen()) VoxyTarget.create(level).fold({ targets += it }, { notes += "Voxy: ${it.message}" })
            if ("Distant Horizons" in chosen()) DhTarget.create(level).fold({ targets += it }, { notes += "Distant Horizons: ${it.message}" })
            return targets to notes
        }
    }
}

/**
 * Bobby: chunks go through Bobby's own storage, the same object Bobby saves to, so its region
 * files are never written behind its back. Only chunks Bobby has nothing saved for are written; a
 * chunk you later load for real replaces ours as usual. Chunks carry a [MARKER] tag.
 */
class BobbyTarget private constructor(
    private val manager: FakeChunkManager,
    private val storage: FakeChunkStorage,
    private val factory: PalettedContainerFactory,
) : FillTarget {
    override val name = "Bobby"
    private var lastReload = 0L

    override fun wants(pos: ChunkPos): Boolean =
        storage.loadTag(pos).get(30, TimeUnit.SECONDS).isEmpty

    override fun accept(chunk: BuiltChunk) = storage.save(chunk.pos, tag(chunk))

    override fun afterBatch() {
        // Shows what was just written, near you, without waiting for you to move.
        val now = System.currentTimeMillis()
        if (now - lastReload < 5_000L) return
        lastReload = now
        Minecraft.getInstance().execute { manager.loadMissingChunksFromCache() }
    }

    private fun tag(chunk: BuiltChunk): CompoundTag {
        val root = CompoundTag()
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version())
        root.putInt("xPos", chunk.pos.x())
        root.putInt("yPos", chunk.minSectionY)
        root.putInt("zPos", chunk.pos.z())
        root.putBoolean("isLightOn", true)
        root.putString("Status", "full")
        root.putBoolean(MARKER, true)
        val sections = ListTag()
        val blockCodec = factory.blockStatesContainerCodec()
        val biomeCodec = factory.biomeContainerCodec()
        for (i in chunk.sections.indices) {
            val section = CompoundTag()
            section.putByte("Y", (chunk.minSectionY + i).toByte())
            section.put("block_states", blockCodec.encodeStart(NbtOps.INSTANCE, chunk.sections[i].states).getOrThrow())
            section.put("biomes", biomeCodec.encodeStart(NbtOps.INSTANCE, chunk.sections[i].biomes).getOrThrow())
            if (!chunk.blockLight[i].isEmpty) section.putByteArray("BlockLight", chunk.blockLight[i].data)
            if (!chunk.skyLight[i].isEmpty) section.putByteArray("SkyLight", chunk.skyLight[i].data)
            sections.add(section)
        }
        // Open sky over the top of the world.
        val above = CompoundTag()
        above.putByte("Y", (chunk.minSectionY + chunk.sections.size).toByte())
        above.putByteArray("SkyLight", ByteArray(2048) { 0xFF.toByte() })
        sections.add(above)
        root.put("sections", sections)
        root.put("block_entities", ListTag())
        return root
    }

    companion object {
        /** Marks a chunk this mod made, so other tools (and Sky's Structure Map) can tell it from a real one. */
        const val MARKER = "skysmapexposer_bluemap"

        fun create(level: ClientLevel, factory: PalettedContainerFactory): Result<FillTarget> {
            val manager = (level.chunkSource as? ClientChunkCacheExt)?.bobby_getFakeChunkManager()
                ?: return Result.failure(IllegalStateException("not active in this world (is it switched on?)"))
            val storage = manager.storage
                ?: return Result.failure(IllegalStateException("its \"dynamic multi-world\" setting is on, which this cannot write into"))
            return Result.success(BobbyTarget(manager, storage, factory))
        }
    }
}

/**
 * Voxy: each section goes straight into Voxy's own ingest, with the light BlueMap drew. A chunk is
 * only filled when Voxy has nothing but air for it: Voxy keeps the world in 32-block cubes, and the
 * chunk's quarter of each is looked at.
 */
class VoxyTarget private constructor(
    private val world: WorldIdentifier,
    private val minY: Int,
    private val height: Int,
) : FillTarget {
    override val name = "Voxy"

    override fun wants(pos: ChunkPos): Boolean {
        val engine = world.nullable ?: return true
        val offsetX = (pos.x() and 1) * 16
        val offsetZ = (pos.z() and 1) * 16
        for (cubeY in Math.floorDiv(minY, 32)..Math.floorDiv(minY + height - 1, 32)) {
            val cube = engine.acquireIfExists(0, pos.x() shr 1, cubeY, pos.z() shr 1) ?: continue
            try {
                if (cube.nonEmptyBlockCount == 0) continue
                val data = cube._unsafeGetRawDataArray()
                for (y in 0 until 32) for (z in offsetZ until offsetZ + 16) for (x in offsetX until offsetX + 16) {
                    if (!Mapper.isAir(data[WorldSection.getIndex(x, y, z)])) return false
                }
            } finally {
                cube.release()
            }
        }
        return true
    }

    override fun accept(chunk: BuiltChunk) {
        for (i in chunk.sections.indices) {
            val section = chunk.sections[i]
            if (section.hasOnlyAir() && chunk.blockLight[i].isEmpty) continue
            VoxelIngestService.rawIngest(world, section, chunk.pos.x(), chunk.minSectionY + i, chunk.pos.z(),
                chunk.blockLight[i], chunk.skyLight[i])
        }
    }

    override fun busy(): Boolean = (VoxyCommon.getInstance()?.ingestService?.taskCount ?: 0) > 2_000

    companion object {
        fun create(level: ClientLevel): Result<FillTarget> {
            if (!VoxyCommon.isAvailable() || VoxyCommon.getInstance() == null) {
                return Result.failure(IllegalStateException("switched off in its settings"))
            }
            val world = WorldIdentifier.of(level) ?: return Result.failure(IllegalStateException("has no world open"))
            return Result.success(VoxyTarget(world, level.minY, level.height))
        }
    }
}

/**
 * Distant Horizons: each chunk is handed to its API as a chunk of the level you are in, which DH
 * turns into its own far-away terrain as if the chunk had been loaded.
 */
class DhTarget private constructor(
    private val level: ClientLevel,
    private val wrapper: IDhApiLevelWrapper,
) : FillTarget {
    override val name = "Distant Horizons"

    /** Only where DH has nothing but air for the chunk. */
    override fun wants(pos: ChunkPos): Boolean {
        val result = DhApi.Delayed.terrainRepo.getAllTerrainDataAtChunkPos(wrapper, pos.x(), pos.z(), null)
        val columns = result.payload ?: return true
        if (!result.success) return true
        for (row in columns) for (column in row ?: continue) for (point in column ?: continue) {
            if (point != null && !point.blockStateWrapper.isAir) return false
        }
        return true
    }

    override fun accept(chunk: BuiltChunk) {
        val sections = Array(chunk.sections.size) { chunk.sections[it].copy() }
        val levelChunk = LevelChunk(level, chunk.pos, UpgradeData.EMPTY, LevelChunkTicks(), LevelChunkTicks(), 0L, sections, null, null)
        val result = DhApi.Delayed.terrainRepo.overwriteChunkDataAsync(wrapper, arrayOf<Any>(levelChunk, level))
        if (!result.success) throw IllegalStateException(result.message)
    }

    companion object {
        fun create(level: ClientLevel): Result<FillTarget> {
            val proxy = DhApi.Delayed.worldProxy
            if (proxy == null || !proxy.worldLoaded()) return Result.failure(IllegalStateException("has no world loaded"))
            val wrapper = proxy.allLoadedLevelWrappers.firstOrNull { it.wrappedMcObject === level }
                ?: return Result.failure(IllegalStateException("has not loaded this dimension yet"))
            return Result.success(DhTarget(level, wrapper))
        }
    }
}
