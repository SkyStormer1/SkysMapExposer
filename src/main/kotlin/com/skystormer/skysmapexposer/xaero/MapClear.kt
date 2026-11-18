package com.skystormer.skysmapexposer.xaero

import com.skystormer.skysmapexposer.MapBorder
import com.skystormer.skysmapexposer.Seasons
import com.skystormer.skysmapexposer.Ui
import com.skystormer.skysmapexposer.ChatButton
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.MapLayers
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import xaero.map.MapProcessor
import xaero.map.WorldMapSession
import xaero.map.region.MapBlock
import java.nio.file.Files
import java.nio.file.Path

/**
 * Taking parts of your Xaero map off it, in the dimension and layer the world map is showing:
 *  - [Kind.OLD_SEASON]: every chunk mapped before the season began ([MapAge]), whose land and
 *    biomes are no longer there;
 *  - [Kind.BORDER]: every chunk wholly beyond the world border, as the server has it now.
 *
 * Asked for in two steps: the first says what would go and offers a button, the second does it.
 * It runs as a [BlueMapDownload] job that writes nothing and clears instead, region by region, each
 * region copied aside just before it is changed, so `/mapexposer restore` puts it all back.
 *
 * Also keeps the season start itself ([setSeason]), the "Cover map before" date of each dimension.
 */
object MapClear {

    enum class Kind(val word: String) { OLD_SEASON("oldseason"), BORDER("border") }

    private class Plan(
        val dimensionId: String,
        val layer: Int,
        val regions: List<Pair<Int, Int>>,
        /** Your map from before the season / beyond the border, for the messages. */
        val what: String,
        val clears: (Int, Int) -> Boolean,
        /** What to say when [regions] is empty. */
        val nothing: String = "Nothing to clear: you have no $what",
        /** For a border, which shape is used and how big it is, so the preview shows what it goes by. */
        val shape: String? = null,
    )

    /** Says in chat what [kind] would take off the map, with a button to go ahead. */
    fun preview(kind: Kind, box: MapBorder.Box? = null): String? {
        val (plan, why) = plan(kind, box)
        plan ?: return why
        if (plan.regions.isEmpty()) return plan.nothing
        // The button is for this map: clicked while the world map shows another, it refuses.
        val command = "/mapexposer clear ${kind.word}${box?.let { " " + it.words } ?: ""} confirm \"${plan.dimensionId}\" ${plan.layer}"
        Minecraft.getInstance().player?.sendSystemMessage(
            Component.literal("Clear ${plan.what}? ${plan.shape?.let { "Border used: $it. " } ?: ""}Up to ${plan.regions.size} regions are looked at; each is copied aside first, " +
                "so /mapexposer restore can put it back.  ")
                .append(ChatButton.of("[Clear]", command, "Takes it off your map now."))
        )
        return null
    }

    /**
     * Starts clearing what [kind] covers. [previewed] is the dimension and layer the preview's
     * button was for; if the world map now shows another, nothing is cleared. Returns what to tell
     * the player.
     */
    fun confirm(kind: Kind, box: MapBorder.Box? = null, previewed: Pair<String, Int>? = null): String {
        val (plan, why) = plan(kind, box)
        plan ?: return why!!
        if (previewed != null) {
            mismatch(previewed.first, previewed.second, plan.dimensionId, plan.layer) { Ui.dimensionName(it) ?: it }?.let { return it }
        }
        if (plan.regions.isEmpty()) return plan.nothing
        val regions = plan.regions.map { (x, z) -> Triple(x, z) { Clearing(x, z, plan.clears) as XaeroWriter.Pixels? } }
        var label = ""
        label = BlueMapDownload.startLocal(
            regions, plan.dimensionId, plan.layer, false,
            title = "Clearing ${plan.what}", doing = "Clearing ${plan.what}", source = "",
        ) { tally ->
            if (tally.cleared == 0) "Nothing to clear: you have no ${plan.what}"
            else {
                val batch = MapBackup.batchName(label)
                // The action bar is gone in seconds, and the batch name is needed to undo this.
                Minecraft.getInstance().player?.sendSystemMessage(
                    Component.literal("Cleared ${tally.cleared} chunks of ${plan.what}.  ").let { message ->
                        if (batch == null) message
                        else message.append(ChatButton.of("[Restore]", "/mapexposer restore $batch", "Puts back what was cleared: /mapexposer restore $batch"))
                    }
                )
                "Cleared ${tally.cleared} chunks of ${plan.what}" + (batch?.let { "; /mapexposer restore $it puts them back" } ?: "")
            }
        } ?: return BlueMapDownload.lastRefusal
        return "Clearing ${plan.what}…"
    }

    /**
     * Why a clear previewed for [dimensionId]'s [layer] cannot go ahead while the world map shows
     * [shownDimension]'s [shownLayer]; null when they are the same map.
     */
    internal fun mismatch(dimensionId: String, layer: Int, shownDimension: String, shownLayer: Int, nameOf: (String) -> String): String? {
        val name = nameOf(dimensionId)
        fun layerName(l: Int) = if (l == MapLayers.SURFACE) "the surface" else "cave layer $l"
        return when {
            dimensionId != shownDimension -> "This clear is for the $name; switch the world map there and click again."
            layer != shownLayer -> "This clear is for ${layerName(layer)} of the $name, but the world map shows ${layerName(shownLayer)}; switch back and click again."
            else -> null
        }
    }

    private fun plan(kind: Kind, box: MapBorder.Box?): Pair<Plan?, String?> {
        val (shown, why) = XaeroWriter.shown()
        shown ?: return null to why
        val dimensionId = shown.dimensionId
        val name = shown.name
        val layer = shown.layer
        val folder = shown.folder
        val all = regionsIn(folder)
        return when (kind) {
            Kind.OLD_SEASON -> {
                val age = MapAge.of(dimensionId, folder)
                    ?: return null to "No season start is set for the $name; /mapexposer season set <yyyy-MM-dd HH:mm> sets it"
                Plan(dimensionId, layer, all.filter { (x, z) -> age.mayHoldOld(x, z) }, "$name map from before ${format(age.seasonStart)}", age::isOld) to null
            }
            Kind.BORDER -> {
                val border = MapBorder.border(box, dimensionId, name)
                val shape = border.first ?: return null to border.second
                val regions = all.filter { (x, z) -> !shape.inside(x * 512.0, z * 512.0, x * 512.0 + 512, z * 512.0 + 512) }
                Log.info("Clear past border in the $name, by ${shape.from}: ${regions.size} of ${all.size} regions reach past it")
                Plan(dimensionId, layer, regions, shape.what, { cx, cz -> shape.beyond(cx * 16.0, cz * 16.0, cx * 16.0 + 16, cz * 16.0 + 16) }, shape.nothing, shape.from) to null
            }
        }
    }

    /** The regions Xaero has a file for in [folder]. */
    fun seasons(): String = Seasons.list()

    fun setSeason(text: String?): String = Seasons.set(text, shownDimension(), busy = BlueMapDownload.running)

    /** The dimension the world map is showing, or else the one you are in. */
    private fun shownDimension(): String? =
        WorldMapSession.getCurrentSession()?.mapProcessor?.mapWorld?.currentDimension?.dimId?.identifier()?.toString()
            ?: Minecraft.getInstance().level?.dimension()?.identifier()?.toString()

    private fun format(minute: Int): String = Seasons.format(minute)

    private fun regionsIn(folder: Path): List<Pair<Int, Int>> = try {
        if (Files.notExists(folder)) emptyList()
        else Files.list(folder).use { files -> files.toList().mapNotNull { MapBackup.regionOf(it.fileName.toString()) } }
    } catch (e: Exception) {
        Log.error("Could not list the map's regions in $folder", e)
        emptyList()
    }

    /** Nothing to write, and chunks to take off. */
    private class Clearing(override val regionX: Int, override val regionZ: Int, private val clear: (Int, Int) -> Boolean) : XaeroWriter.Pixels {
        override fun countInChunk(chunkInRegionX: Int, chunkInRegionZ: Int) = 0
        override fun block(processor: MapProcessor, px: Int, pz: Int, old: MapBlock?, tally: XaeroWriter.Tally): MapBlock? = null
        override fun clears(chunkX: Int, chunkZ: Int) = clear(chunkX, chunkZ)
    }

    // ---- the season start ----


}
