package com.skystormer.skysmapexposer.gui

import com.skystormer.skysmapexposer.BlueMap
import com.skystormer.skysmapexposer.BlueMapDownload
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.MapExposerClient
import com.skystormer.skysmapexposer.MapMenus
import com.skystormer.skysmapexposer.MarkerElements
import com.skystormer.skysmapexposer.Overlay
import com.skystormer.skysmapexposer.Session
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Everything in `config/skysmapexposer.json`, without typing in chat or editing the file, on three
 * tabs: Map (what is shown, and how big), Server (where BlueMap is, and downloading from it) and
 * Experimental (settings that may get you in trouble, off unless you turn them on).
 *
 * The Server tab opens on the server you are connected to: its BlueMap address, which BlueMap map to use for each
 * dimension and whether its terrain is used, and the date before which your own overworld map is
 * covered regardless (for a map left over from a previous season). On a server not set up yet it
 * starts a new entry with that server's address filled in, and "Find maps" asks the BlueMap which
 * maps it has. Done saves and applies at once.
 *
 * Built from plain vanilla widgets rather than a config library, like About Face Easy Place's.
 */
class ConfigScreen(private val parent: Screen) : FramedScreen(Component.literal("Sky's Map Exposer")) {

    /** A server entry being edited. Kept across [rebuildWidgets], which switching server causes. */
    private class Draft(
        var addresses: String,
        var url: String,
        val maps: MutableMap<String, String>,
        val off: MutableSet<String>,
        var coverOverworld: String,
        val otherCover: Map<String, Long>,
    )

    private val connectedAddress: String? = MapExposerClient.addressOf(Minecraft.getInstance())

    private val drafts: MutableList<Draft> = Config.servers.map { server ->
        Draft(
            addresses = server.addresses.joinToString(", "),
            url = server.url,
            maps = server.maps.toMutableMap(),
            off = server.off.toMutableSet(),
            coverOverworld = server.coverBefore[Config.OVERWORLD]?.let(::formatTime) ?: "",
            otherCover = server.coverBefore - Config.OVERWORLD,
        )
    }.toMutableList()

    private enum class Page(val title: String) { MAP("Map"), SERVER("Server"), EXPERIMENTAL("Experimental") }

    private var page = Page.MAP
    private var enabled = Config.enabled
    private var showMarkers = Config.showMarkers
    private var showOutlines = Config.showOutlines
    private var showPlayers = Config.showPlayers
    private var minimapPlayers = Config.minimapPlayers
    private var staleDays = formatDays(Config.staleDays)
    private var shrinkChunks = Config.minimapShrinkChunks
    private var worldMapMarkerScale = Config.worldMapMarkerScale
    private var playerHeadScale = Config.playerHeadScale
    private var minimapHeadScale = Config.minimapHeadScale
    private var minimapMarkerScale = Config.minimapMarkerScale
    private var otherDimensionCoords = Config.otherDimensionCoords
    private var minimapBiomes = Config.minimapBiomes
    private var matchWaypoints = Config.matchWaypointsToDimension
    private var guessBiomes = Config.guessBiomes
    private var shareThroughWarnings = Config.shareThroughWarnings
    private var message: Component = Component.literal(Overlay.lastSummary)
    private var selected: Int = pickInitialServer() // after message, which it may replace

    // Only the boxes of the tab showing exist; the other tab's are null.
    private var staleBox: EditBox? = null
    private var coverBox: EditBox? = null
    private var addressBox: EditBox? = null
    private var urlBox: EditBox? = null
    private val mapBoxes = HashMap<String, EditBox>()
    private var messageWidget: MultiLineTextWidget? = null

    private fun newDraft(address: String) =
        Draft(address, "", mutableMapOf(Config.OVERWORLD to "world"), mutableSetOf(Config.NETHER), "", emptyMap())

    /** The entry for the server you are on, or a new one for it, or the first. */
    private fun pickInitialServer(): Int {
        val here = connectedAddress
        if (here != null) {
            val wanted = Config.normalise(here)
            val index = drafts.indexOfFirst { draft -> splitAddresses(draft.addresses).any { Config.normalise(it) == wanted } }
            if (index >= 0) return index
            drafts.add(newDraft(here))
            message = Component.literal("$here is not set up yet. Enter its BlueMap address, then press Find maps.")
            page = Page.SERVER
            return drafts.size - 1
        }
        if (drafts.isEmpty()) drafts.add(newDraft(""))
        return 0
    }

    override val resetTip = "Puts the Map tab's switches and sizes, and how long saved pictures are kept, back to how the mod comes. " +
        "Your servers and their BlueMap addresses are kept."

    override fun resetToDefaults() {
        keepEdits()
        enabled = true
        showMarkers = true
        showOutlines = true
        showPlayers = true
        minimapPlayers = Config.MinimapPlayers.FAR_ONLY
        shrinkChunks = 32
        worldMapMarkerScale = 1f
        playerHeadScale = 1f
        minimapHeadScale = 1f
        minimapMarkerScale = 1f
        otherDimensionCoords = true
        minimapBiomes = true
        matchWaypoints = true
        guessBiomes = true
        shareThroughWarnings = false
        staleDays = formatDays(7.0)
    }

    override fun content(top: Int) {
        staleBox = null; coverBox = null; addressBox = null; urlBox = null; messageWidget = null
        mapBoxes.clear()
        val left = width / 2 - WIDTH / 2
        var y = top

        // The tabs: the one showing is greyed out. The last takes what rounding leaves.
        val tabs = Page.entries.size
        val tabWidth = (WIDTH - GAP * (tabs - 1)) / tabs
        Page.entries.forEachIndexed { i, tab ->
            val x = left + i * (tabWidth + GAP)
            addRenderableWidget(Button.builder(Component.literal(tab.title)) { switchPage(tab) }
                .bounds(x, y, if (i < tabs - 1) tabWidth else WIDTH - (tabWidth + GAP) * i, ROW).build()
                .also { it.active = tab != page })
        }
        y += ROW + GAP * 3

        when (page) {
            Page.MAP -> mapPage(left, y)
            Page.SERVER -> serverPage(left, y)
            Page.EXPERIMENTAL -> experimentalPage(left, y)
        }
    }

    /** Settings that may get you in trouble on some servers; all off as the mod comes. */
    private fun experimentalPage(left: Int, top: Int) {
        var y = top
        addRenderableWidget(MultiLineTextWidget(left, y, Component.literal(
            "These may get you warned, muted or kicked on some servers. Use them where you know the rules."
        ), font).setMaxWidth(WIDTH))
        y += font.lineHeight * 2 + GAP * 2
        addRenderableWidget(toggle(left, y, WIDTH, "Share through spam warnings", shareThroughWarnings,
            "Off: sharing a map in chat stops as soon as the server warns about spam. On: it waits 30 s, " +
                "sends the last messages again and carries on at half speed, stopping only at a second warning.") { shareThroughWarnings = it })
    }

    /** What is shown on the maps, how big, and the players. */
    private fun mapPage(left: Int, top: Int) {
        var y = top
        val half = (WIDTH - GAP) / 2
        val quarter = (WIDTH - GAP * 3) / 4

        addRenderableWidget(toggle(left, y, quarter, "Terrain", enabled, "Fill in your map with BlueMap's terrain.") { enabled = it })
        addRenderableWidget(toggle(left + (quarter + GAP), y, quarter, "Markers", showMarkers, "Show BlueMap's markers (shops, banners…) on the world map and minimap.") { showMarkers = it })
        addRenderableWidget(toggle(left + (quarter + GAP) * 2, y, quarter, "Borders", showOutlines, "Draw BlueMap's world border and zones on both maps.") { showOutlines = it })
        addRenderableWidget(toggle(left + (quarter + GAP) * 3, y, quarter, "Players", showPlayers, "Show other players BlueMap reports, in their own dimension.") { showPlayers = it })
        y += ROW + GAP

        addRenderableWidget(toggle(left, y, half, "Other dimension XZ", otherDimensionCoords,
            "On the world map, under the coordinates of the block under the mouse: the same block in the other dimension. " +
                "Viewing the Nether, the Overworld's (eight times as far); viewing the Overworld, the Nether's (an eighth).") { otherDimensionCoords = it })
        addRenderableWidget(toggle(left + half + GAP, y, WIDTH - half - GAP, "Biomes on minimap", minimapBiomes,
            "The biomes picked in the map panel's Terrain list are tinted on the minimap as well as the world map.") { minimapBiomes = it })
        y += ROW + GAP

        addRenderableWidget(toggle(left, y, half, "Waypoints follow map", matchWaypoints,
            "The world map shows the waypoints of the dimension it is showing, not the one you are in. This switches on " +
                "Xaero's own setting for it when you join a world; off leaves Xaero's setting as it is.") { matchWaypoints = it })
        val minimapChoice = CycleButton.builder<Config.MinimapPlayers>({ Component.literal(it.label) }, minimapPlayers)
            .withValues(Config.MinimapPlayers.entries.toList())
            .displayOnlyValue()
            .create(left + half + GAP, y, WIDTH - half - GAP, ROW, Component.literal("Minimap players")) { _, value -> minimapPlayers = value }
        minimapChoice.setTooltip(Tooltip.create(Component.literal(
            "Which players the minimap shows. BlueMap knows where everyone is, so a zoomed out minimap can keep them " +
                "on screen after they leave your render distance. Out of range leaves the ones near you to Xaero's own " +
                "radar, so nobody is drawn twice; choose All players if you have that radar switched off."
        )))
        addRenderableWidget(minimapChoice)
        y += ROW + GAP * 3

        // How old your map may get, and how far minimap markers reach.
        val staleLabel = 80
        val staleBoxWidth = 28
        label(left, y, staleLabel, "Replace after")
        staleBox = field(left + staleLabel, y, staleBoxWidth, staleDays, "7").also {
            it.setTooltip(Tooltip.create(Component.literal(
                "Days before your own map counts as out of date. After that, BlueMap's picture replaces it wherever BlueMap has changed since you were last there."
            )))
        }
        label(left + staleLabel + staleBoxWidth + 4, y, 26, "days")
        val sliderLeft = left + staleLabel + staleBoxWidth + 32
        val slider = ChunkSlider(sliderLeft, y, left + WIDTH - sliderLeft, shrinkChunks) { shrinkChunks = it }
        slider.setTooltip(Tooltip.create(Component.literal(
            "BlueMap markers show on the minimap only within this distance, smaller the further away they are. The world map still shows them all."
        )))
        addRenderableWidget(slider)
        y += ROW + GAP

        // Sizes: markers and players' heads, on the world map and on the minimap, each on its own.
        val sizeHalf = (WIDTH - GAP) / 2
        addRenderableWidget(ScaleSlider(left, y, sizeHalf, "Map icons", worldMapMarkerScale,
            "Size of BlueMap's markers on the world map.") { worldMapMarkerScale = it })
        addRenderableWidget(ScaleSlider(left + sizeHalf + GAP, y, WIDTH - sizeHalf - GAP, "Map heads", playerHeadScale,
            "Size of other players' heads on the world map and over a player you have locked on to. The same size on screen at any GUI scale.") { playerHeadScale = it })
        y += ROW + GAP
        addRenderableWidget(ScaleSlider(left, y, sizeHalf, "Minimap icons", minimapMarkerScale,
            "Size of BlueMap's markers on the minimap. They still shrink with distance.") { minimapMarkerScale = it })
        addRenderableWidget(ScaleSlider(left + sizeHalf + GAP, y, WIDTH - sizeHalf - GAP, "Minimap heads", minimapHeadScale,
            "Size of other players' heads on the minimap.") { minimapHeadScale = it })
        y += ROW + GAP * 3

        addRenderableWidget(
            Button.builder(Component.literal("Players…")) { minecraft.gui.setScreen(PlayerListScreen(this)) }
                .bounds(left, y, WIDTH, ROW)
                .tooltip(Tooltip.create(Component.literal("Everyone BlueMap can see: jump the map to them, lock on, or copy their coordinates.")))
                .build()
        )
    }

    /** Where this server's BlueMap is, which of its maps to use, and downloading from it. */
    private fun serverPage(left: Int, top: Int) {
        var y = top
        val labelWidth = 96
        val fieldLeft = left + labelWidth
        val fieldWidth = WIDTH - labelWidth
        val switchWidth = 48
        val quarter = (WIDTH - GAP * 3) / 4

        val choices = drafts.indices.toList() + NEW
        addRenderableWidget(
            CycleButton.builder<Int>({ index -> Component.literal(if (index == NEW) "+ Add a server" else describe(drafts[index])) }, selected)
                .withValues(choices)
                .create(left, y, WIDTH, ROW, Component.literal("Server")) { _, index -> switchTo(index) }
        )
        y += ROW + GAP

        val draft = drafts[selected]
        label(left, y, labelWidth, "Server address")
        addressBox = field(fieldLeft, y, fieldWidth, draft.addresses, "play.example.com, 1.2.3.4").also {
            it.setTooltip(Tooltip.create(Component.literal(
                "Every name you join this server by, as typed in your server list, separated by commas."
            )))
        }
        y += ROW + GAP
        label(left, y, labelWidth, "BlueMap address")
        urlBox = field(fieldLeft, y, fieldWidth, draft.url, "https://map.example.com/").also {
            it.setTooltip(Tooltip.create(Component.literal(
                "The web address of the server's BlueMap: the page you open in a browser to see the map."
            )))
        }
        y += ROW + GAP

        for ((dimension, name) in DIMENSIONS) {
            label(left, y, labelWidth, "$name map")
            val box = field(fieldLeft, y, fieldWidth - switchWidth - GAP, draft.maps[dimension] ?: "", "blank = none")
            box.setTooltip(Tooltip.create(Component.literal(
                "Which BlueMap map shows the $name. Find maps lists them. Its border, markers and players show even with its terrain off."
            )))
            mapBoxes[dimension] = box
            val terrain = CycleButton.onOffBuilder(dimension !in draft.off)
                .displayOnlyValue()
                .create(left + WIDTH - switchWidth, y, switchWidth, ROW, Component.literal("$name terrain")) { _, on ->
                    if (on) draft.off.remove(dimension) else draft.off.add(dimension)
                }
            terrain.setTooltip(Tooltip.create(Component.literal("Fill in the $name with this map's terrain.")))
            addRenderableWidget(terrain)
            y += ROW + GAP
        }

        label(left, y, labelWidth, "Cover map before")
        val cover = field(fieldLeft, y, fieldWidth - switchWidth - GAP, draft.coverOverworld, "blank = never")
        cover.setTooltip(Tooltip.create(Component.literal(
            "Your overworld map from before this date and time (yyyy-MM-dd HH:mm) is covered by BlueMap however recent it is. For a map left over from a previous season."
        )))
        coverBox = cover
        addRenderableWidget(
            Button.builder(Component.literal("Now")) { cover.value = formatTime(System.currentTimeMillis()) }
                .bounds(left + WIDTH - switchWidth, y, switchWidth, ROW).build()
        )
        y += ROW + GAP

        addRenderableWidget(toggle(left, y, WIDTH, "Downloads guess biomes", guessBiomes,
            "When downloading, guess the biome of blocks you have never been to from BlueMap's colours, so grass, " +
                "leaves and water come out the right shade. Overworld only.") { guessBiomes = it })
        y += ROW + GAP

        messageWidget = MultiLineTextWidget(left, y + 1, message, font).setMaxWidth(WIDTH).setMaxRows(2).also { addRenderableWidget(it) }
        y += font.lineHeight * 2 + GAP

        val third = (WIDTH - GAP * 2) / 3
        addRenderableWidget(Button.builder(Component.literal("Find maps")) { findMaps() }.bounds(left, y, third, ROW).build())
        addRenderableWidget(Button.builder(Component.literal("Remove server")) { removeSelected() }.bounds(left + third + GAP, y, third, ROW).build())
        addRenderableWidget(downloadButton(left + (third + GAP) * 2, y, WIDTH - (third + GAP) * 2))
    }

    private fun switchPage(to: Page) {
        keepEdits()
        page = to
        rebuildWidgets()
    }

    private fun downloadButton(x: Int, y: Int, width: Int): Button {
        if (BlueMapDownload.running) {
            return Button.builder(Component.literal("Stop download")) {
                BlueMapDownload.cancel()
                message = Component.literal("Stopping the download after the region it is on.")
                rebuildWidgets()
            }
                .bounds(x, y, width, ROW)
                .tooltip(Tooltip.create(Component.literal(BlueMapDownload.status() ?: "A download from BlueMap is running.")))
                .build()
        }
        val button = Button.builder(Component.literal("Download…")) { confirmDownload() }
            .bounds(x, y, width, ROW)
            .tooltip(Tooltip.create(Component.literal(
                "Download from BlueMap into your own Xaero map, for the dimension the world map is showing: " +
                    "only the chunks you have never explored, or everything. Each region of your map is backed up " +
                    "just before it is changed."
            )))
            .build()
        button.active = Session.current != null
        return button
    }

    private fun confirmDownload() {
        keepEdits()
        val dimension = MarkerElements.worldMapDimension()?.let(MapMenus::dimensionName) ?: "current"
        minecraft.gui.setScreen(DownloadScreen(dimension) { said ->
            if (said != null) message = Component.literal(said)
            minecraft.gui.setScreen(this)
        })
    }

    /**
     * How far away, in chunks, BlueMap markers are shown on the minimap.
     * Snaps to whole chunks.
     */
    private class ChunkSlider(x: Int, y: Int, width: Int, initial: Int, private val onChange: (Int) -> Unit) :
        AbstractSliderButton(x, y, width, ROW, Component.empty(), toSlider(initial)) {

        init {
            updateMessage()
        }

        private val chunks: Int
            get() = (Config.MIN_SHRINK_CHUNKS + value * (Config.MAX_SHRINK_CHUNKS - Config.MIN_SHRINK_CHUNKS)).roundToInt()

        override fun updateMessage() {
            message = Component.literal("Markers within $chunks chunks")
        }

        override fun applyValue() = onChange(chunks)

        companion object {
            fun toSlider(chunks: Int): Double =
                (chunks - Config.MIN_SHRINK_CHUNKS).toDouble() / (Config.MAX_SHRINK_CHUNKS - Config.MIN_SHRINK_CHUNKS)
        }
    }

    /** A size from 25% to 300%, in steps of 5%. */
    private class ScaleSlider(
        x: Int, y: Int, width: Int, private val name: String, initial: Float, explanation: String,
        private val onChange: (Float) -> Unit,
    ) : AbstractSliderButton(x, y, width, ROW, Component.empty(), toSlider(initial)) {

        init {
            updateMessage()
            setTooltip(Tooltip.create(Component.literal(explanation)))
        }

        private val scale: Float
            get() {
                val raw = Config.MIN_SCALE + value.toFloat() * (Config.MAX_SCALE - Config.MIN_SCALE)
                return (raw * 20).roundToInt() / 20f
            }

        override fun updateMessage() {
            message = Component.literal("$name ${(scale * 100).roundToInt()}%")
        }

        override fun applyValue() = onChange(scale)

        companion object {
            fun toSlider(scale: Float): Double =
                ((scale - Config.MIN_SCALE) / (Config.MAX_SCALE - Config.MIN_SCALE)).toDouble()
        }
    }

    private fun toggle(x: Int, y: Int, width: Int, name: String, value: Boolean, explanation: String, onChange: (Boolean) -> Unit): CycleButton<Boolean> =
        CycleButton.onOffBuilder(value).create(x, y, width, ROW, Component.literal(name)) { _, on -> onChange(on) }
            .also { it.setTooltip(Tooltip.create(Component.literal(explanation))) }

    private fun label(x: Int, y: Int, width: Int, text: String) {
        addRenderableWidget(StringWidget(x, y + 6, width, font.lineHeight, Component.literal(text), font))
    }

    private fun field(x: Int, y: Int, width: Int, value: String, hint: String): EditBox {
        val box = EditBox(font, x, y, width, ROW, Component.literal(hint))
        box.setMaxLength(256)
        box.setValue(value)
        box.setHint(Component.literal(hint))
        return addRenderableWidget(box)
    }

    /** Copies what is typed into the selected draft, before the widgets are thrown away. */
    private fun keepEdits() {
        staleBox?.let { staleDays = it.value }
        val draft = drafts[selected]
        addressBox?.let { draft.addresses = it.value }
        urlBox?.let { draft.url = it.value }
        for ((dimension, box) in mapBoxes) {
            if (box.value.isBlank()) draft.maps.remove(dimension) else draft.maps[dimension] = box.value.trim()
        }
        coverBox?.let { draft.coverOverworld = it.value }
    }

    private fun switchTo(index: Int) {
        keepEdits()
        if (index == NEW) {
            drafts.add(newDraft(""))
            selected = drafts.size - 1
        } else {
            selected = index
        }
        message = Component.literal("")
        rebuildWidgets()
    }

    private fun removeSelected() {
        drafts.removeAt(selected)
        if (drafts.isEmpty()) drafts.add(newDraft(connectedAddress ?: ""))
        selected = selected.coerceAtMost(drafts.size - 1)
        message = Component.literal("Removed. Press Done to save.")
        rebuildWidgets()
    }

    /** Asks the BlueMap at the typed address which maps it has, and fills in the overworld if blank. */
    private fun findMaps() {
        val url = urlBox?.value?.trim() ?: return
        if (url.isEmpty()) {
            show("Type the BlueMap address first: the page you open in a browser.")
            return
        }
        show("Asking $url…")
        Thread({
            val result = runCatching { BlueMap(url).use { it.maps() } }
            minecraft.execute {
                result.onSuccess { maps ->
                    val overworld = mapBoxes[Config.OVERWORLD]
                    if (overworld != null && overworld.value.isBlank()) {
                        (maps.firstOrNull { it.first == "world" } ?: maps.firstOrNull())?.let { overworld.value = it.first }
                    }
                    show("Maps here: " + maps.joinToString(", ") { (id, name) -> "$id ($name)" })
                }
                result.onFailure { show("Could not reach BlueMap at $url: ${it.message ?: it.javaClass.simpleName}") }
            }
        }, "SkysMapExposer find maps").apply { isDaemon = true }.start()
    }

    private fun show(text: String) {
        message = Component.literal(text)
        messageWidget?.setMessage(message)
    }

    override fun onClose() {
        keepEdits()
        Config.enabled = enabled
        Config.showMarkers = showMarkers
        Config.showOutlines = showOutlines
        Config.showPlayers = showPlayers
        Config.minimapPlayers = minimapPlayers
        Config.minimapShrinkChunks = shrinkChunks
        Config.worldMapMarkerScale = worldMapMarkerScale
        Config.playerHeadScale = playerHeadScale
        Config.minimapHeadScale = minimapHeadScale
        Config.minimapMarkerScale = minimapMarkerScale
        Config.otherDimensionCoords = otherDimensionCoords
        Config.minimapBiomes = minimapBiomes
        Config.matchWaypointsToDimension = matchWaypoints
        Config.guessBiomes = guessBiomes
        Config.shareThroughWarnings = shareThroughWarnings
        staleDays.trim().toDoubleOrNull()?.takeIf { it >= 0 }?.let { Config.staleDays = it }
        Config.servers = drafts
            .filter { splitAddresses(it.addresses).isNotEmpty() && it.url.isNotBlank() }
            .map { draft ->
                val cover = LinkedHashMap(draft.otherCover)
                parseTime(draft.coverOverworld)?.let { cover[Config.OVERWORLD] = it }
                Config.Server(splitAddresses(draft.addresses), draft.url.trim(), draft.maps.toMap(), draft.off.toSet(), cover)
            }
        Config.save()
        // Start again with the new settings, as if just joined.
        if (connectedAddress != null) Session.start(connectedAddress)
        minecraft.setScreenAndShow(parent)
    }

    private fun describe(draft: Draft): String =
        splitAddresses(draft.addresses).firstOrNull() ?: "(new server)"

    private fun splitAddresses(text: String): List<String> =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun formatDays(days: Double): String =
        if (days == Math.floor(days)) days.toLong().toString() else days.toString()

    private fun formatTime(millis: Long): String =
        TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()))

    private fun parseTime(text: String): Long? = try {
        if (text.isBlank()) null
        else LocalDateTime.parse(text.trim(), TIME).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val WIDTH = 300
        const val ROW = 20
        const val GAP = 2
        const val NEW = -1
        /** How tall each tab is, to centre it; both fit in 278 scaled pixels, so they show at large GUI scales. */
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val DIMENSIONS = listOf(Config.OVERWORLD to "Overworld", Config.NETHER to "Nether", Config.END to "End")
    }
}
