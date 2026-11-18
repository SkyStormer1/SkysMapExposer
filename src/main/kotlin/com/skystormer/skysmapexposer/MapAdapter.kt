package com.skystormer.skysmapexposer

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import java.util.UUID

/**
 * What the rest of the mod asks of the map mod that is installed ([MapMods.adapter]). Everything
 * else works from BlueMap, chat and the game alone, so a map only has to answer these. Its classes
 * are only loaded through here, and only when that map is installed.
 *
 * The ones with a default are things a map may not have; the default is "this map can't".
 */
interface MapAdapter {

    /** Once, as the game starts: hooks into the map's own screens and events. */
    fun init()

    /** Once the game is up: logs which of this mod's hooks into the map did not take. */
    fun checkHooks()

    fun join()

    /** Leaving a world: everything made for it is let go. */
    fun leave()

    /** Client thread, every tick. */
    fun tick(client: Minecraft, session: Session?)

    /** Commands under `/mapexposer` that only this map has. */
    fun commands(root: LiteralArgumentBuilder<FabricClientCommandSource>)

    /** This map's lines in `/mapexposer`, after the mod's own. */
    fun status(say: (String) -> Unit)

    /** The dimension the full-screen map is showing, which need not be the one you are in. */
    fun shownDimension(): String?

    /** The dimension shown, while the map shows caves that BlueMap's picture is not drawn over. */
    fun cavesShown(session: Session): String?

    /**
     * Shows [x], [z] of [dimension] on the full-screen map, opening it over [from] if needed and
     * picking out [player]. False if it can't.
     */
    fun goTo(dimension: String, x: Int, z: Int, from: Screen?, player: UUID?): Boolean

    /** What the BlueMap layer drew last, for the settings screen and `/mapexposer`. */
    fun summary(): String = ""

    /** Whether this map's minimap shows the picked biomes too, so the map bar offers the switch for it. */
    val tintsMinimap: Boolean get() = false

    /** Biomes the map has recorded in [dimension] that this game version does not know, for the map bar's list. */
    fun unknownBiomes(dimension: String?): Set<net.minecraft.resources.ResourceKey<net.minecraft.world.level.biome.Biome>> = emptySet()

    /** Bumped whenever another of [unknownBiomes] turns up. */
    val unknownBiomesVersion: Int get() = 0

    /** Your waypoints of the dimension you are in, to share; null with none to read. */
    fun myWaypoints(): Pair<String, List<ShareableWaypoint>>? = null

    /** Adds waypoints [from] someone else to [dimension]; what to tell you. */
    fun addWaypoints(dimension: String, from: String, waypoints: List<ShareFormat.SharedWaypoint>): String =
        "This map can't take waypoints"

    /**
     * Adds shared terrain file [file] (opened as [id]) to this map; with [unexploredOnly], only where
     * it has nothing. What to tell the player, or null when it asked in chat instead.
     */
    fun addSharedTerrain(file: SharedTerrain.Opened, id: Int, unexploredOnly: Boolean, confirmed: Boolean): String? =
        "This map mod cannot take shared terrain files"

    /** How a download into the map is going, while one runs. */
    fun downloadStatus(): String? = null

    /** Stops a running download; false if none was running. */
    fun cancelDownload(): Boolean = false
}
