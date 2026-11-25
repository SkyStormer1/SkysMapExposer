package com.skystormer.skysmapexposer.gui

import com.skystormer.skysmapexposer.background
import com.skystormer.skysmapexposer.MapMods
import com.skystormer.skysmapexposer.MenuLayout
import com.skystormer.skysmapexposer.Ui
import com.skystormer.skysmapexposer.BlueMap
import com.skystormer.skysmapexposer.Clock
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.Log
import com.skystormer.skysmapexposer.Seasons
import com.skystormer.skysmapexposer.MapExposerClient
import com.skystormer.skysmapexposer.MapPrep
import com.skystormer.skysmapexposer.TileCache
import com.skystormer.skysmapexposer.Session
import com.skystormer.skysmapexposer.DownloadOffer
import com.skystormer.skysmapexposer.SettingsCheck
import com.skystormer.skysmapexposer.terrain.FillTarget
import com.skystormer.skysmapexposer.terrain.TerrainFill
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt
import kotlin.reflect.KProperty0

/**
 * Everything in `config/skysmapexposer.json`, without typing in chat or editing the file, on four
 * tabs: Map (what is shown, and how big), Server (where BlueMap is, and downloading from it), Tiles
 * (preparing the map in the background, and the tile cache) and
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

    private enum class Page(val title: String) { MAP("Map"), SERVER("Server"), TILES("Tiles"), EXPERIMENTAL("Experimental") }

    private var page = Page.MAP
    /** The settings as changed here, put into effect by Done. */
    private val edits = Config.edits()
    /** Typed as text, so a half-typed number is kept until Done checks it. */
    private var staleDays = formatDays(Config.staleDays)
    private var message: Component = Component.literal((Session.current?.let { MapExposerClient.textureMemory(it) + ". " } ?: "") + (MapMods.adapter?.summary() ?: ""))
    private var selected: Int = pickInitialServer() // after message, which it may replace

    /** Everything as it was on opening, to tell whether Cancel throws anything away. */
    private val initial = state()

    /** Switched live by the Experimental tab, so put back by Cancel. */
    private val fillOffBefore = Config.fillOff.toSet()

    /** The connected server's settings on opening: the session starts again only if they change. */
    private val serverBefore = connectedAddress?.let(Config::serverFor)?.let(::describeServer)

    /** What Done or Escape found wrong, shown until fixed: where it is, and which box. */
    private class Problem(val page: Page, val server: Int, val field: SettingsCheck.Field)

    private var problem: Problem? = null
    private var problemText: String? = null

    override val cancellable = true

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
        edits.reset(
            Config::enabled, Config::showMarkers, Config::showOutlines, Config::showPlayers, Config::minimapPlayers,
            Config::minimapShrinkChunks, Config::worldMapMarkerScale, Config::playerHeadScale, Config::minimapHeadScale,
            Config::minimapMarkerScale, Config::otherDimensionCoords, Config::otherDimensionArrow, Config::minimapBiomes, Config::pings,
            Config::matchWaypointsToDimension, Config::guessBiomes,
        )
        staleDays = formatDays(edits.default(Config::staleDays))
    }

    override fun content(top: Int) {
        staleBox = null; coverBox = null; addressBox = null; urlBox = null; messageWidget = null; fillWidget = null; prepareWidget = null; cacheWidget = null
        mapBoxes.clear()
        val left = width / 2 - WIDTH / 2
        var y = top

        // The tabs: the one showing is greyed out.
        val tabs = Columns(left, Page.entries.size)
        Page.entries.forEachIndexed { i, tab -> button(tabs.x(i), y, tabs.width(i), tab.title) { switchPage(tab) }.active = tab != page }
        y += ROW + GAP * 3

        // Why Done did not save, above whichever tab it is on.
        problemText?.let { text -> y = note(left, y, Component.literal(text).withColor(PROBLEM_COLOUR)) + GAP * 3 }

        when (page) {
            Page.MAP -> mapPage(left, y)
            Page.SERVER -> serverPage(left, y)
            Page.TILES -> tilesPage(left, y)
            Page.EXPERIMENTAL -> experimentalPage(left, y)
        }
        // The box with the problem stands out, with the cursor in it.
        problem?.takeIf { it.page == page && (it.page != Page.SERVER || it.server == selected) }?.let { p ->
            val box = when (p.field) {
                SettingsCheck.Field.STALE -> staleBox
                SettingsCheck.Field.ADDRESS -> addressBox
                SettingsCheck.Field.URL -> urlBox
                SettingsCheck.Field.COVER -> coverBox
            } ?: return@let
            box.setTextColor(PROBLEM_COLOUR)
            focused = box
        }
    }

    /** Settings that may get you in trouble on some servers, off as the mod comes, and the ping sound. */
    private fun experimentalPage(left: Int, top: Int) {
        var y = top
        note(left, y, Component.literal("These may get you warned, muted or kicked on some servers. Use them where you know the rules."))
        y += font.lineHeight * 2 + GAP * 2
        val floor = CycleButton.builder<Boolean>({ Component.literal(if (it) "High res" else "Low res") }, edits[Config::seaFloorHires])
            .withValues(false, true)
            .create(left, y, WIDTH, ROW, Component.literal("Sea floor")) { _, value -> edits[Config::seaFloorHires] = value }
        floor.setTooltip(SafeTooltip.of(
            "Low res: the sea from BlueMap's flat pictures, which are one blue all over and quick to download. " +
                "High res: close up on the world map, the sea floor under the water from BlueMap's 3D tiles, shaded the way " +
                "Xaero shades water. Those are about 90 KB for each 32 blocks square of sea, downloaded two at a time and " +
                "kept on disk, downloaded again where BlueMap's map changes. The sea on screen is downloaded at any zoom, " +
                "nearest the middle first, so zooming in later finds it ready; the first time, a stretch of sea takes a " +
                "minute or so. Needs Terrain on."))
        addRenderableWidget(floor)
        y += ROW + GAP
        addRenderableWidget(toggle(left, y, WIDTH, "Ping sound", Config::pingSound,
            "Play a bell when a ping from someone you trust arrives. Off, the beam still flashes and chat still says so."))
        y += ROW + GAP * 3
        terrainFillRows(left, y)
    }

    /**
     * Filling Bobby, Voxy and Distant Horizons from BlueMap's 3D map ([TerrainFill]): which of them
     * are installed, how far, and a start/stop button, with how it is going underneath.
     */
    private fun terrainFillRows(left: Int, top: Int) {
        var y = top
        val installed = FillTarget.installed()
        val chosen = FillTarget.chosen()
        val about = when {
            installed.isEmpty() -> "Far terrain from BlueMap: install Bobby, Voxy or Distant Horizons and this can fill " +
                "their far-away terrain from BlueMap's 3D map."
            chosen.isEmpty() -> "Far terrain from BlueMap: switch on the mods to fill below."
            else -> "Fill ${chosen.joinToString(" and ")} with terrain from BlueMap's 3D map, " +
                "only where ${if (chosen.size == 1) "it has" else "they have"} nothing of your own."
        }
        // As many lines as the text wraps to, so the buttons never sit on it.
        y = note(left, y, Component.literal(about)) + GAP * 2
        // One switch per installed mod, so only the ones you want are filled.
        if (installed.isNotEmpty()) {
            val mods = Columns(left, installed.size)
            installed.forEachIndexed { i, mod ->
                addRenderableWidget(toggle(mods.x(i), y, mods.width(i), mod, mod !in Config.fillOff,
                    "Whether the fill gives terrain to $mod.") { on ->
                    if (on) Config.fillOff.remove(mod) else Config.fillOff.add(mod)
                    rebuildWidgets()
                }.also { it.active = !TerrainFill.running })
            }
            y += ROW + GAP
        }
        val halves = Columns(left, 2)
        val size = CycleButton.builder<Int>({ Component.literal("$it blocks") }, TerrainFill.radius)
            .withValues(TerrainFill.RADII)
            .create(left, y, halves.width(0), ROW, Component.literal("Around you")) { _, value -> TerrainFill.radius = value }
        size.setTooltip(SafeTooltip.of(
            "How far from where you stand to fill, in every direction. Bigger takes longer: BlueMap's 3D tiles are " +
                "downloaded a couple at a time so the server is not slowed down."))
        size.active = chosen.isNotEmpty() && !TerrainFill.running
        addRenderableWidget(size)
        val button = if (TerrainFill.running) {
            button(halves.x(1), y, halves.width(1), "Stop filling") { TerrainFill.cancel(); rebuildWidgets() }
        } else {
            // Known not to work here (no 3D tiles): greyed out, saying why.
            button(halves.x(1), y, halves.width(1), "Fill from BlueMap", TerrainFill.knownProblem() ?: (
                "Rebuilds the chunks around you from BlueMap's 3D map and hands them to " +
                    (chosen.joinToString(" and ").ifEmpty { "the mods switched on above" }) +
                    ". Only chunks a mod has nothing for are filled, and a chunk you load for real later replaces the filled one. " +
                    "You are shown how much it downloads before it starts.")) { askToFill() }
        }
        button.active = TerrainFill.running || (chosen.isNotEmpty() && TerrainFill.knownProblem() == null && !checkingFill)
        y += ROW + GAP
        fillWidget = status(left, y, TerrainFill.status() ?: fillStatus, 3)
    }

    private var fillStatus = ""
    private var fillWidget: MultiLineTextWidget? = null
    private var checkingFill = false

    /**
     * "Fill from BlueMap": checks first that BlueMap has 3D tiles here, then shows how much it would
     * download and that it cannot be undone, and starts only on **Start**.
     */
    private fun askToFill() {
        checkingFill = true
        fillStatus = "Checking BlueMap's 3D tiles around you…"
        rebuildWidgets()
        TerrainFill.check { problem, estimate ->
            checkingFill = false
            if (problem != null || estimate == null) {
                fillStatus = problem ?: ""
                if (minecraft.gui.screen() === this) rebuildWidgets()
                return@check
            }
            if (minecraft.gui.screen() !== this) return@check
            minecraft.gui.setScreen(ConfirmScreen(
                { start ->
                    if (start) {
                        fillStatus = TerrainFill.start()
                        Log.info("Terrain fill: {}", fillStatus)
                    } else {
                        fillStatus = "Not started; nothing was downloaded."
                    }
                    minecraft.gui.setScreen(this)
                },
                Component.literal("Fill from BlueMap?"),
                Component.literal(estimate.text),
                Component.literal("Start"),
                CommonComponents.GUI_CANCEL,
            ))
        }
    }
    private var fillWasRunning = TerrainFill.running

    private var prepareWidget: MultiLineTextWidget? = null
    private var prepareWasRunning = MapPrep.running
    private var prepareSaid = ""
    private var cacheWidget: MultiLineTextWidget? = null
    private var cacheLine = "Tile cache: counting…"
    private var cacheCounted = false

    override fun tick() {
        super.tick()
        fillWidget?.setMessage(Component.literal(TerrainFill.status() ?: fillStatus))
        prepareWidget?.setMessage(Component.literal(prepareLine()))
        cacheWidget?.setMessage(Component.literal(cacheLine))
        // The buttons turn back from Stop when the fill or the preparation ends.
        if (fillWasRunning != TerrainFill.running) {
            fillWasRunning = TerrainFill.running
            if (page == Page.EXPERIMENTAL) rebuildWidgets()
        }
        if (prepareWasRunning != MapPrep.running) {
            prepareWasRunning = MapPrep.running
            if (page == Page.TILES) rebuildWidgets()
        }
    }

    private fun prepareLine(): String = MapPrep.status() ?: prepareSaid.ifEmpty {
        if (Session.current == null) "Not on a server with BlueMap." else "Not preparing the map now."
    }

    /** "Every 3 days" for 72 hours; "Off" for 0. */
    private fun everyLabel(hours: Int): String = when {
        hours == 0 -> "Off"
        hours % 168 == 0 -> if (hours == 168) "Every week" else "Every ${hours / 168} weeks"
        hours % 24 == 0 -> if (hours == 24) "Every day" else "Every ${hours / 24} days"
        else -> "Every $hours hours"
    }

    /** Preparing the whole map in the background, how often tiles are downloaded whole, the video memory tiles hold, and the tile cache on disk. */
    private fun tilesPage(left: Int, top: Int) {
        var y = top
        val halves = Columns(left, 2)
        val session = Session.current
        addRenderableWidget(toggle(left, y, halves.width(0), "Prepare map", Config::prepareMap,
            "After you join, download every tile BlueMap has, a few a second, and make it ready to draw, so the map is ready " +
                "before you open it. Tiles you are looking at always come first."))
        val button = if (MapPrep.running) {
            button(halves.x(1), y, halves.width(1), "Stop preparing") { MapPrep.cancel(); prepareSaid = "Stopped; what is done stays."; rebuildWidgets() }
        } else {
            button(halves.x(1), y, halves.width(1), "Prepare now", "Go over the whole map now: tiles already on disk and up to date are skipped.") {
                prepareSaid = session?.let { MapPrep.start(it, MapMods.adapter?.shownDimension()) } ?: ""
                rebuildWidgets()
            }
        }
        button.active = session != null
        y += ROW + GAP
        prepareWidget = status(left, y, prepareLine(), 2)
        y += font.lineHeight * 2 + GAP * 2

        val hours = edits[Config::fullDownloadHours]
        val choices = (Config.FULL_DOWNLOAD_CHOICES + hours).distinct().sortedBy { if (it == 0) Int.MAX_VALUE else it }
        val full = CycleButton.builder<Int>({ Component.literal(everyLabel(it)) }, hours)
            .withValues(choices)
            .create(left, y, WIDTH, ROW, Component.literal("Full download")) { _, value -> edits[Config::fullDownloadHours] = value }
        full.setTooltip(SafeTooltip.of(
            "Every ${Config.tileCheckMinutes} minutes a tile on screen is only asked \"has it changed?\", and downloaded " +
                "only if it has. This is how often each tile is downloaded whole anyway, in case a change was missed. " +
                "Each tile comes due a little later than this, up to half as long again, so they do not all download at once " +
                "when you join. Off: only what the checks find changed is downloaded."))
        addRenderableWidget(full)
        y += ROW + GAP * 3

        val memory = session?.let { MapExposerClient.textureMemory(it) } ?: "Texture memory: not on a server with BlueMap"
        status(left, y, memory, 2)
        y += font.lineHeight * 2 + GAP * 3

        if (!cacheCounted) {
            cacheCounted = true
            countCache()
        }
        cacheWidget = status(left, y, cacheLine, 2)
        y += font.lineHeight * 2 + GAP
        button(left, y, WIDTH, "Clear tile cache",
            "Deletes BlueMap's tiles kept on disk for this server, and the ones in memory; they are downloaded again as " +
                "they are needed. Preparing the map stops first, and starts over afterwards. Your own map is not touched.") {
            cacheLine = "Clearing the tile cache…"
            session?.clearTiles { said ->
                cacheLine = said
                countCache()
            }
        }.active = session != null
    }

    /** Counts the tile cache on another thread, so a big cache does not freeze the screen. */
    private fun countCache() {
        val session = Session.current
        val root = minecraft.gameDirectory.toPath().resolve("skysmapexposer")
        background("SkysMapExposer cache size") {
            val line = try {
                val all = TileCache.measureAll(root)
                if (session == null) "Tile cache, all servers: $all"
                else "Tile cache: ${TileCache.measure(session.folder.resolve("tiles"))} here; $all for all servers"
            } catch (e: Exception) {
                "Tile cache: could not count it (${e.message ?: e.javaClass.simpleName})"
            }
            minecraft.execute { cacheLine = line }
        }
    }

    /** What is shown on the maps, how big, and the players. */
    private fun mapPage(left: Int, top: Int) {
        var y = top
        val halves = Columns(left, 2)
        val quarters = Columns(left, 4)

        addRenderableWidget(toggle(quarters.x(0), y, quarters.width(0), "Terrain", Config::enabled, "Fill in your map with BlueMap's terrain."))
        addRenderableWidget(toggle(quarters.x(1), y, quarters.width(1), "Markers", Config::showMarkers, "Show BlueMap's markers (shops, banners…) on the world map and minimap."))
        addRenderableWidget(toggle(quarters.x(2), y, quarters.width(2), "Borders", Config::showOutlines, "Draw BlueMap's world border and zones on both maps."))
        addRenderableWidget(toggle(quarters.x(3), y, quarters.width(3), "Players", Config::showPlayers, "Show other players BlueMap reports, in their own dimension."))
        y += ROW + GAP

        addRenderableWidget(toggle(left, y, halves.width(0), "Other dimension XZ", Config::otherDimensionCoords,
            "On the world map, under the coordinates of the block under the mouse: the same block in the other dimension. " +
                "Viewing the Nether, the Overworld's (eight times as far); viewing the Overworld, the Nether's (an eighth)."))
        addRenderableWidget(toggle(halves.x(1), y, halves.width(1), "Other dimension arrow", Config::otherDimensionArrow,
            "Your arrow on the full-screen map of the Nether while you are in the Overworld, and the other way round, " +
                "where you are in that dimension's terms (an eighth, or eight times, as far out). Never on the End's map."))
        y += ROW + GAP

        addRenderableWidget(toggle(left, y, halves.width(0), "Minimap tint", Config::minimapBiomes,
            "The biomes picked in the map panel's Terrain list are tinted on the minimap as well as the world map."))
        addRenderableWidget(toggle(halves.x(1), y, halves.width(1), "Pings", Config::pings,
            "Show the spots players you trust ping, as a flashing beam. Trust someone in the player list."))
        y += ROW + GAP

        addRenderableWidget(toggle(left, y, halves.width(0), "Waypoints follow map", Config::matchWaypointsToDimension,
            "The world map shows the waypoints of the dimension it is showing, not the one you are in. This switches on " +
                "Xaero's own setting for it when you join a world; off leaves Xaero's setting as it is."))
        val minimapChoice = CycleButton.builder<Config.MinimapPlayers>({ Component.literal(it.label) }, edits[Config::minimapPlayers])
            .withValues(Config.MinimapPlayers.entries.toList())
            .displayOnlyValue()
            .create(halves.x(1), y, halves.width(1), ROW, Component.literal("Minimap players")) { _, value -> edits[Config::minimapPlayers] = value }
        minimapChoice.setTooltip(SafeTooltip.of(
            "Which players the minimap shows. BlueMap knows where everyone is, so a zoomed out minimap can keep them " +
                "on screen after they leave your render distance. Out of range leaves the ones near you to Xaero's own " +
                "radar, so nobody is drawn twice; choose All players if you have that radar switched off."
        ))
        addRenderableWidget(minimapChoice)
        y += ROW + GAP * 3

        // How old your map may get, and how far minimap markers reach.
        val staleLabel = 80
        val staleBoxWidth = 28
        label(left, y, staleLabel, "Replace after")
        staleBox = field(left + staleLabel, y, staleBoxWidth, staleDays, "7").also {
            it.setTooltip(SafeTooltip.of(
                "Days before your own map counts as out of date. After that, BlueMap's picture replaces it wherever BlueMap has changed since you were last there."
            ))
        }
        label(left + staleLabel + staleBoxWidth + 4, y, 26, "days")
        val sliderLeft = left + staleLabel + staleBoxWidth + 32
        addRenderableWidget(StepSlider(sliderLeft, y, left + WIDTH - sliderLeft, Config.MIN_SHRINK_CHUNKS.toDouble(), Config.MAX_SHRINK_CHUNKS.toDouble(), 1.0,
            edits[Config::minimapShrinkChunks].toDouble(), { "Markers within ${it.roundToInt()} chunks" },
            "BlueMap markers show on the minimap only within this distance, smaller the further away they are. The world map still shows them all.",
        ) { edits[Config::minimapShrinkChunks] = it.roundToInt() })
        y += ROW + GAP

        // Sizes: markers and players' heads, on the world map and on the minimap, each on its own.
        addRenderableWidget(scaleSlider(halves.x(0), y, halves.width(0), "Map icons", Config::worldMapMarkerScale,
            "Size of BlueMap's markers on the world map."))
        addRenderableWidget(scaleSlider(halves.x(1), y, halves.width(1), "Map heads", Config::playerHeadScale,
            "Size of other players' heads on the world map and over a player you have locked on to. The same size on screen at any GUI scale."))
        y += ROW + GAP
        addRenderableWidget(scaleSlider(halves.x(0), y, halves.width(0), "Minimap icons", Config::minimapMarkerScale,
            "Size of BlueMap's markers on the minimap. They still shrink with distance."))
        addRenderableWidget(scaleSlider(halves.x(1), y, halves.width(1), "Minimap heads", Config::minimapHeadScale,
            "Size of other players' heads on the minimap."))
        y += ROW + GAP * 3

        button(halves.x(0), y, halves.width(0), "Players…", "Everyone BlueMap can see: jump the map to them, lock on, or copy their coordinates.") {
            minecraft.gui.setScreen(PlayerListScreen(this))
        }
        val menuMap = if (MapMods.xaeroWorldMap) MenuLayout.XAERO else MenuLayout.JOURNEYMAP
        button(halves.x(1), y, halves.width(1), "Edit right-click menu…",
            "The world map's right-click menu your way: order, groups, and what is hidden under More. Also at the bottom of the menu itself.") {
            minecraft.gui.setScreen(MenuEditScreen(this, menuMap))
        }.active = MapMods.any
    }

    /** Where this server's BlueMap is, which of its maps to use, and downloading from it. */
    private fun serverPage(left: Int, top: Int) {
        var y = top
        val labelWidth = 96
        val fieldLeft = left + labelWidth
        val fieldWidth = WIDTH - labelWidth
        val switchWidth = 48

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
            it.setTooltip(SafeTooltip.of(
                "Every name you join this server by, as typed in your server list, separated by commas."
            ))
        }
        y += ROW + GAP
        label(left, y, labelWidth, "BlueMap address")
        urlBox = field(fieldLeft, y, fieldWidth, draft.url, "https://map.example.com/").also {
            it.setTooltip(SafeTooltip.of(
                "The web address of the server's BlueMap: the page you open in a browser to see the map. " +
                    "Just its port (like 8100) is enough when it runs on the server's own address."
            ))
        }
        y += ROW + GAP

        for ((dimension, name) in DIMENSIONS) {
            label(left, y, labelWidth, "$name map")
            val box = field(fieldLeft, y, fieldWidth - switchWidth - GAP, draft.maps[dimension] ?: "", "blank = none")
            box.setTooltip(SafeTooltip.of(
                "Which BlueMap map shows the $name. Find maps lists them. Its border, markers and players show even with its terrain off."
            ))
            mapBoxes[dimension] = box
            val terrain = CycleButton.onOffBuilder(dimension !in draft.off)
                .displayOnlyValue()
                .create(left + WIDTH - switchWidth, y, switchWidth, ROW, Component.literal("$name terrain")) { _, on ->
                    if (on) draft.off.remove(dimension) else draft.off.add(dimension)
                }
            terrain.setTooltip(SafeTooltip.of("Fill in the $name with this map's terrain."))
            addRenderableWidget(terrain)
            y += ROW + GAP
        }

        label(left, y, labelWidth, "Cover map before")
        val cover = field(fieldLeft, y, fieldWidth - switchWidth - GAP, draft.coverOverworld, "blank = never")
        cover.setTooltip(SafeTooltip.of(
            "Your overworld map from before this date and time (yyyy-MM-dd HH:mm) is covered by BlueMap however recent it is. For a map left over from a previous season."
        ))
        coverBox = cover
        button(left + WIDTH - switchWidth, y, switchWidth, "Now") { cover.value = formatTime(System.currentTimeMillis()) }
        y += ROW + GAP

        addRenderableWidget(toggle(left, y, WIDTH, "Downloads guess biomes", Config::guessBiomes,
            "When downloading, guess the biome of blocks you have never been to from BlueMap's colours, so grass, " +
                "leaves and water come out the right shade. Overworld only."))
        y += ROW + GAP

        messageWidget = status(left, y, message, 2)
        y += font.lineHeight * 2 + GAP

        val thirds = Columns(left, 3)
        button(thirds.x(0), y, thirds.width(0), "Find maps") { findMaps() }
        button(thirds.x(1), y, thirds.width(1), "Remove server") { removeSelected() }
        downloadButton(thirds.x(2), y, thirds.width(2))
        // With an address and no maps picked yet, they are looked up at once, once per server shown.
        if (draft.url.isNotBlank() && DIMENSIONS.all { draft.maps[it.first].isNullOrBlank() } && autoFound.add(draft)) findMaps()
    }

    /** The servers whose maps were looked up on their own already, so it happens once each. */
    private val autoFound = HashSet<Any>()

    private fun switchPage(to: Page) {
        keepEdits()
        page = to
        rebuildWidgets()
    }

    private fun downloadButton(x: Int, y: Int, width: Int) {
        val downloading = MapMods.adapter?.downloadStatus()
        if (downloading != null) {
            button(x, y, width, "Stop download", downloading) {
                MapMods.adapter?.cancelDownload()
                message = Component.literal("Stopping the download after the region it is on.")
                rebuildWidgets()
            }
            return
        }
        button(x, y, width, "Download…",
            "Saves your changes and asks in chat, as the map's right-click Download BlueMap does: the region " +
                "you are in or everything, only where your map has nothing or over it too. Each region of your " +
                "map is backed up just before it is changed.") { saveAndOfferDownload() }
            // Downloads write into the map's own files: Xaero's World Map or JourneyMap.
            .active = Session.current != null && MapMods.adapter?.canDownload == true
    }

    /** Saves as Done does, then, unless something stopped the save, asks in chat what to download ([DownloadOffer]). */
    private fun saveAndOfferDownload() {
        onClose()
        if (minecraft.gui.screen() === this) return
        val player = minecraft.player ?: return
        DownloadOffer.offer(player.blockX, player.blockZ, null, "the 512-block region you are in")
    }

    /** A slider from [min] to [max] in steps of [step], reading [label] of the value. */
    private class StepSlider(
        x: Int, y: Int, width: Int, private val min: Double, private val max: Double, private val step: Double, initial: Double,
        private val label: (Double) -> String, explanation: String, private val onChange: (Double) -> Unit,
    ) : AbstractSliderButton(x, y, width, ROW, Component.empty(), (initial - min) / (max - min)) {
        init {
            updateMessage()
            setTooltip(SafeTooltip.of(explanation))
        }
        private val stepped get() = Math.round((min + value * (max - min)) / step) * step
        override fun updateMessage() { message = Component.literal(label(stepped)) }
        override fun applyValue() = onChange(stepped)
    }

    /** [count] columns across the screen's width from [left], [GAP] apart; the last takes what rounding leaves. */
    private class Columns(private val left: Int, private val count: Int) {
        private val each = (WIDTH - GAP * (count - 1)) / count
        fun x(i: Int) = left + i * (each + GAP)
        fun width(i: Int) = if (i < count - 1) each else WIDTH - (each + GAP) * i
    }

    private fun button(x: Int, y: Int, width: Int, text: String, explanation: String? = null, onPress: () -> Unit): Button =
        addRenderableWidget(Button.builder(Component.literal(text)) { onPress() }.bounds(x, y, width, ROW)
            .also { builder -> explanation?.let { builder.tooltip(SafeTooltip.of(it)) } }.build())

    /** Text across the screen's width, as many lines as it wraps to; where the next row can start. */
    private fun note(x: Int, y: Int, text: Component): Int {
        addRenderableWidget(MultiLineTextWidget(x, y, text, font).setMaxWidth(WIDTH))
        return y + font.lineHeight * font.split(text, WIDTH).size
    }

    /** A line of how something is going, up to [rows] lines, kept to be updated. */
    private fun status(x: Int, y: Int, text: String, rows: Int) = status(x, y, Component.literal(text), rows)
    private fun status(x: Int, y: Int, text: Component, rows: Int): MultiLineTextWidget =
        addRenderableWidget(MultiLineTextWidget(x, y + 1, text, font).setMaxWidth(WIDTH).setMaxRows(rows))

    private fun toggle(x: Int, y: Int, width: Int, name: String, value: Boolean, explanation: String, onChange: (Boolean) -> Unit): CycleButton<Boolean> =
        CycleButton.onOffBuilder(value).create(x, y, width, ROW, Component.literal(name)) { _, on -> onChange(on) }
            .also { it.setTooltip(SafeTooltip.of(explanation)) }

    /** An on/off switch for one of [edits]. */
    private fun toggle(x: Int, y: Int, width: Int, name: String, setting: KProperty0<Boolean>, explanation: String) =
        toggle(x, y, width, name, edits[setting], explanation) { edits[setting] = it }

    /** A size from 25% to 300%, in steps of 5%, for one of [edits]. */
    private fun scaleSlider(x: Int, y: Int, width: Int, name: String, setting: KProperty0<Float>, explanation: String) =
        StepSlider(x, y, width, Config.MIN_SCALE.toDouble(), Config.MAX_SCALE.toDouble(), 0.05, edits[setting].toDouble(),
            { "$name ${(it * 100).roundToInt()}%" }, explanation) { edits[setting] = it.toFloat() }

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
        urlBox?.let { box ->
            val address = cleanUrl(box.value, draft.addresses)
            draft.url = address.url
            if (box.value != draft.url) box.value = draft.url
        }
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
        message = Component.literal("Removed. Done saves that; Cancel keeps the server.")
        rebuildWidgets()
    }

    /** Asks the BlueMap at the typed address which maps it has, and fills in the overworld if blank. */
    private fun findMaps() {
        val box = urlBox ?: return
        val address = cleanUrl(box.value, addressBox?.value ?: drafts[selected].addresses)
        val url = address.url
        if (url != box.value) box.value = url
        if (url.isEmpty()) {
            show("Type the BlueMap address first: the page you open in a browser.")
            return
        }
        address.problem?.let { return show("BlueMap address: $it") }
        show((address.fixed?.let { "Fixed the address ($it). " } ?: "") + "Asking $url…")
        background("SkysMapExposer find maps", Thread.NORM_PRIORITY) {
            val result = runCatching { BlueMap(url).use { it.maps() } }
            minecraft.execute {
                result.onSuccess { maps ->
                    // Each dimension's box, if empty, gets the map whose id or name says it is that dimension's.
                    for ((dimension, _) in DIMENSIONS) {
                        val box = mapBoxes[dimension] ?: continue
                        if (box.value.isBlank()) guessMap(dimension, maps)?.let { box.value = it }
                    }
                    show("Maps here: " + maps.joinToString(", ") { (id, name) -> "$id ($name)" })
                }
                result.onFailure { show("Could not reach BlueMap at $url: ${it.message ?: it.javaClass.simpleName}") }
            }
        }
    }

    /**
     * The map of [maps] (id, name) for [dimension]: one named for the Nether or the End, and for the
     * Overworld "world" or "overworld", else the first that is neither of the others.
     */
    private fun guessMap(dimension: String, maps: List<Pair<String, String>>): String? {
        fun words(map: Pair<String, String>) = (map.first + " " + map.second).lowercase().replace('_', ' ').replace('-', ' ')
        fun nether(map: Pair<String, String>) = "nether" in words(map)
        fun end(map: Pair<String, String>) = Regex("""\bend\b""").containsMatchIn(words(map))
        return when (dimension) {
            Config.NETHER -> maps.firstOrNull(::nether)?.first
            Config.END -> maps.firstOrNull { end(it) && !nether(it) }?.first
            else -> (maps.firstOrNull { it.first.lowercase() in setOf("world", "overworld") }
                ?: maps.firstOrNull { !nether(it) && !end(it) })?.first
        }
    }

    private fun show(text: String) {
        message = Component.literal(text)
        messageWidget?.setMessage(message)
    }

    /**
     * Everything this screen edits, as one string, to compare. Only what Done would save: the
     * server list as it would be saved, so an untouched new entry is no change.
     */
    private fun state(): String = listOf(
        edits, staleDays.trim(), Config.fillOff.sorted(),
        drafts.filter { splitAddresses(it.addresses).isNotEmpty() || it.url.isNotBlank() }.map { d ->
            listOf(splitAddresses(d.addresses), d.url.trim(), d.maps.toSortedMap(), d.off.sorted(), d.coverOverworld.trim(), d.otherCover.toSortedMap())
        },
    ).toString()

    /** **Cancel**: asks first if anything was changed, then leaves without saving. */
    override fun cancel() {
        keepEdits()
        if (state() == initial) return leave()
        minecraft.gui.setScreen(ConfirmScreen(
            { discard -> if (discard) leave() else minecraft.gui.setScreen(this) },
            Component.literal("Discard changes?"),
            Component.literal("Nothing changed here is saved."),
            Component.literal("Discard"),
            Component.literal("Keep editing"),
        ))
    }

    private fun leave() {
        Config.fillOff.clear()
        Config.fillOff.addAll(fillOffBefore)
        minecraft.setScreenAndShow(parent)
    }

    /**
     * What stops saving, with where it is, or null when everything can be saved. A new server entry
     * left completely empty is not a problem: it is simply not saved.
     */
    private fun check(): Pair<String, Problem>? {
        val entries = drafts.map { SettingsCheck.Entry(splitAddresses(it.addresses), it.url, it.coverOverworld) }
        val found = SettingsCheck.check(staleDays, entries, ::parseTime) ?: return null
        val where = if (found.field == SettingsCheck.Field.STALE) Problem(Page.MAP, selected, found.field)
        else Problem(Page.SERVER, found.server, found.field)
        return found.message to where
    }

    /** Done and Escape: saves, unless something is wrong, which is shown instead. */
    override fun onClose() {
        keepEdits()
        check()?.let { (said, where) ->
            problem = where
            problemText = "$said. Fix it, or Cancel to leave without saving."
            page = where.page
            selected = where.server
            rebuildWidgets()
            return
        }
        problem = null
        problemText = null
        if (Config.seaFloorHires && !edits[Config::seaFloorHires]) Session.current?.seaFloor?.clear()
        edits.apply()
        staleDays.trim().toDoubleOrNull()?.takeIf { it >= 0 }?.let { Config.staleDays = it }
        Config.servers = drafts
            .filter { splitAddresses(it.addresses).isNotEmpty() && it.url.isNotBlank() }
            .map { draft ->
                val cover = LinkedHashMap(draft.otherCover)
                parseTime(draft.coverOverworld)?.let { cover[Config.OVERWORLD] = it }
                Config.Server(splitAddresses(draft.addresses), draft.url.trim(), draft.maps.toMap(), draft.off.toSet(), cover)
            }
        Config.save()
        // Start again, as if just joined, only if this server's own settings changed: that drops
        // every tile, marker and icon in memory. Everything else is read as it is drawn.
        val serverAfter = connectedAddress?.let(Config::serverFor)?.let(::describeServer)
        if (connectedAddress != null && serverAfter != serverBefore) {
            Session.start(connectedAddress)
        } else {
            // Switching background preparation on or off takes effect at once.
            val session = Session.current
            if (session != null && Config.prepareMap && !prepareWasOn && !MapPrep.running) MapPrep.start(session, MapMods.adapter?.shownDimension())
            if (!Config.prepareMap && prepareWasOn) MapPrep.cancel()
        }
        minecraft.setScreenAndShow(parent)
    }

    private val prepareWasOn = Config.prepareMap

    private fun describeServer(server: Config.Server): String = SettingsCheck.serverKey(server)

    private fun describe(draft: Draft): String =
        splitAddresses(draft.addresses).firstOrNull() ?: "(new server)"

    private fun splitAddresses(text: String): List<String> =
        text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * The BlueMap address typed, cleaned ([SettingsCheck.blueMapAddress]) after a bare port is
     * given the server's name ([withPort]); says under the boxes what was fixed or what is wrong.
     */
    private fun cleanUrl(typed: String, addresses: String): SettingsCheck.Address {
        val address = SettingsCheck.blueMapAddress(withPort(typed, addresses))
        when {
            address.problem != null -> show("BlueMap address: ${address.problem}")
            address.fixed != null -> show("Fixed the BlueMap address: ${address.fixed}")
        }
        return if (address.problem == null) address else SettingsCheck.Address(typed.trim(), null, address.problem)
    }

    /**
     * [typed] as a BlueMap address: just a port ("25464" or ":25464") becomes
     * `http://<the server's first address>:<port>/`, as BlueMap usually runs on the same machine as
     * the game; anything else is left as typed.
     */
    private fun withPort(typed: String, addresses: String): String {
        val port = Regex(""":?(\d{1,5})""").matchEntire(typed.trim())?.groupValues?.get(1)?.toInt() ?: return typed
        if (port !in 1..65535) return typed
        val host = splitAddresses(addresses).firstOrNull()?.substringBefore(':')?.takeIf { it.isNotBlank() } ?: return typed
        return "http://$host:$port/"
    }

    private fun formatDays(days: Double): String =
        if (days == Math.floor(days)) days.toLong().toString() else days.toString()

    private fun formatTime(millis: Long): String = Seasons.format(Clock.minutesOf(millis))

    private fun parseTime(text: String): Long? = Seasons.parse(text)

    private companion object {
        const val WIDTH = 300
        const val ROW = 20
        const val GAP = 2
        const val NEW = -1
        const val PROBLEM_COLOUR = 0xFFFF5555.toInt()
        val DIMENSIONS = listOf(Config.OVERWORLD to "Overworld", Config.NETHER to "Nether", Config.END to "End")
    }
}
