package com.skystormer.skysmapexposer

import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/*
 * Small things several parts of the mod do the same way, kept in one place.
 */

/**
 * [x] and [z] in one Long, [x] in the high half, for keys of maps and sets. Only for keys kept in
 * memory: `VisitLog` saves its keys, so it keeps its own order.
 */
fun packXZ(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

/** Runs [work] on a daemon thread called [name], at low priority unless told otherwise. */
fun background(name: String, priority: Int = Thread.MIN_PRIORITY, work: () -> Unit): Thread =
    Thread(work, name).apply {
        isDaemon = true
        this.priority = priority
        start()
    }

/** A map that keeps only the [keep] entries used most lately, letting go of the rest. Not thread safe. */
fun <K, V> lruMap(keep: Int): LinkedHashMap<K, V> = object : LinkedHashMap<K, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>) = size > keep
}

/** [colour] (0xAARRGGBB) times [tint] (0xRRGGBB), channel by channel as the maps tint, keeping [colour]'s alpha. */
fun multiplyColour(colour: Int, tint: Int): Int {
    fun channel(shift: Int) = ((colour shr shift) and 0xFF) * ((tint shr shift) and 0xFF) / 255
    return (colour and -0x1000000) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** The game's key for dimension [id] ("minecraft:the_nether"). */
fun levelKey(id: String): ResourceKey<Level> = ResourceKey.create(Registries.DIMENSION, Identifier.parse(id))

/** Water itself; a waterlogged block counts as the block it is, as the maps see it. */
fun isWaterBlock(state: BlockState): Boolean = state.block == Blocks.WATER
