package com.skystormer.skysmapexposer.journeymap

import com.skystormer.skysmapexposer.MapExposerClient
import journeymap.client.model.chunk.ChunkMD
import journeymap.client.model.chunk.SnapshotChunkMD
import journeymap.common.chunk.JMChunkSnapshot
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.dimension.DimensionType

/**
 * JourneyMap's renderers drawing chunks of a dimension you are not in ([JourneyMapOtherMaps], for a
 * map toggled to it). They are made to draw the world you are in, and read a few things from it:
 * its heights, whether it has a sky, its neighbouring chunks, and how many sections a chunk read
 * from the cache has. While one of these draws, on its own thread, those come from the dimension
 * drawn instead: its type ([type]), here, and its chunks ([OtherChunk], [neighbour]), through
 * `OtherWorldMixins`. Everything else they read comes from the chunks themselves.
 */
internal object JourneyMapOtherWorld {

    private class Drawing(val type: DimensionType, val chunks: Map<Long, ChunkMD>)

    private val drawing = ThreadLocal<Drawing?>()

    /** The type of the dimension being drawn on this thread, if it is not yours; else null. For the mixins. */
    @JvmStatic
    fun type(): DimensionType? = drawing.get()?.type

    /** Whether a dimension you are not in is being drawn on this thread. */
    @JvmStatic
    fun active(): Boolean = drawing.get() != null

    /** Neighbouring chunk [packed] of the dimension being drawn, or null where it has none read. */
    @JvmStatic
    fun neighbour(packed: Long): ChunkMD? = drawing.get()?.chunks?.get(packed)

    /** [block] with JourneyMap reading [type] as the world's, and [chunks] (by packed position) as its loaded chunks. */
    fun <T> with(type: DimensionType, chunks: Map<Long, ChunkMD>, block: () -> T): T {
        drawing.set(Drawing(type, chunks))
        try {
            return block()
        } finally {
            drawing.remove()
        }
    }

    /**
     * [dimension]'s type: the one of the same name, as the game's own dimensions have; null for one
     * whose type the client cannot tell, which is then not drawn, and for all while the hooks that
     * read a chunk at its own height and give it its own neighbours are missing (another JourneyMap).
     */
    fun typeOf(dimension: String, registries: RegistryAccess): DimensionType? = if (!hooked) null else try {
        registries.lookupOrThrow(Registries.DIMENSION_TYPE).getValue(Identifier.parse(dimension))
    } catch (e: Exception) {
        null
    }

    private val hooked by lazy {
        MapExposerClient.injected("journeymap.common.chunk.JMChunkSnapshot", "otherWorldSections") &&
            MapExposerClient.injected("journeymap.client.model.chunk.ChunkRenderContext", "otherWorldNeighbour")
    }

    /** A chunk of [dimension] (of [type]) read from JourneyMap's cache, answering for its own dimension rather than [level]'s. */
    class OtherChunk(level: ClientLevel, snapshot: JMChunkSnapshot, private val dimension: ResourceKey<Level>, private val type: DimensionType) :
        SnapshotChunkMD(level, snapshot, true) {
        override fun getWorldActualHeight(): Int = type.logicalHeight()
        override fun hasNoSky(): Boolean = !type.hasSkyLight()
        override fun getMinY(): Int = type.minY()
        override fun getDimension(): ResourceKey<Level> = dimension
    }
}
