package com.skystormer.skysmapexposer.gui

import com.skystormer.skysmapexposer.BlueMap
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.MapExposerClient
import com.skystormer.skysmapexposer.Overlay
import com.skystormer.skysmapexposer.Session
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineTextWidget
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Everything in `config/skysmapexposer.json`, without typing in chat or editing the file.
 *
 * Opens on the server you are connected to: its BlueMap address, which BlueMap map to use for each
 * dimension and whether its terrain is used, and the date before which your own overworld map is
 * covered regardless (for a map left over from a previous season). On a server not set up yet it
 * starts a new entry with that server's address filled in, and "Find maps" asks the BlueMap which
 * maps it has. Done saves and applies at once.
 *
 * Built from plain vanilla widgets rather than a config library, like About Face Easy Place's.
 */
class ConfigScreen(private val parent: Screen) : Screen(Component.literal("Sky's Map Exposer")) {

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

    private var enabled = Config.enabled
    private var showMarkers = Config.showMarkers
    private var showOutlines = Config.showOutlines
    private var showPlayers = Config.showPlayers
    private var staleDays = formatDays(Config.staleDays)
    private var message: Component = Component.literal(Overlay.lastSummary)
    private var selected: Int = pickInitialServer() // after message, which it may replace

    private lateinit var staleBox: EditBox
    private lateinit var coverBox: EditBox
    private lateinit var addressBox: EditBox
    private lateinit var urlBox: EditBox
    private val mapBoxes = HashMap<String, EditBox>()
    private lateinit var messageWidget: MultiLineTextWidget

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
            return drafts.size - 1
        }
        if (drafts.isEmpty()) drafts.add(newDraft(""))
        return 0
    }

    override fun init() {
        val left = width / 2 - WIDTH / 2
        val labelWidth = 104
        val fieldLeft = left + labelWidth
        val fieldWidth = WIDTH - labelWidth
        var y = maxOf(4, height / 2 - 150)

        addRenderableWidget(StringWidget(left, y, WIDTH, ROW, title, font))
        y += ROW + GAP

        val quarter = (WIDTH - GAP * 3) / 4
        addRenderableWidget(toggle(left, y, quarter, "Terrain", enabled) { enabled = it })
        addRenderableWidget(toggle(left + (quarter + GAP), y, quarter, "Markers", showMarkers) { showMarkers = it })
        addRenderableWidget(toggle(left + (quarter + GAP) * 2, y, quarter, "Borders", showOutlines) { showOutlines = it })
        addRenderableWidget(toggle(left + (quarter + GAP) * 3, y, quarter, "Players", showPlayers) { showPlayers = it })
        y += ROW + GAP

        label(left, y, labelWidth, "Replace after (days)")
        staleBox = field(fieldLeft, y, fieldWidth, staleDays, "7")
        y += ROW + GAP * 3

        val choices = drafts.indices.toList() + NEW
        addRenderableWidget(
            CycleButton.builder<Int>({ index -> Component.literal(if (index == NEW) "+ Add a server" else describe(drafts[index])) }, selected)
                .withValues(choices)
                .create(left, y, WIDTH, ROW, Component.literal("Server")) { _, index -> switchTo(index) }
        )
        y += ROW + GAP

        val draft = drafts[selected]
        label(left, y, labelWidth, "Addresses")
        addressBox = field(fieldLeft, y, fieldWidth, draft.addresses, "mc.example.com, 1.2.3.4")
        y += ROW + GAP
        label(left, y, labelWidth, "BlueMap address")
        urlBox = field(fieldLeft, y, fieldWidth, draft.url, "http://example.com:8100/")
        y += ROW + GAP

        mapBoxes.clear()
        val switchWidth = 56
        for ((dimension, name) in DIMENSIONS) {
            label(left, y, labelWidth, "$name map")
            mapBoxes[dimension] = field(fieldLeft, y, fieldWidth - switchWidth - GAP, draft.maps[dimension] ?: "", "blank = none")
            addRenderableWidget(
                CycleButton.onOffBuilder(dimension !in draft.off)
                    .displayOnlyValue()
                    .create(left + WIDTH - switchWidth, y, switchWidth, ROW, Component.literal("$name terrain")) { _, on ->
                        if (on) draft.off.remove(dimension) else draft.off.add(dimension)
                    }
            )
            y += ROW + GAP
        }

        label(left, y, labelWidth, "Cover overworld before")
        coverBox = field(fieldLeft, y, fieldWidth - switchWidth - GAP, draft.coverOverworld, "blank = never")
        addRenderableWidget(
            Button.builder(Component.literal("Now")) { coverBox.value = formatTime(System.currentTimeMillis()) }
                .bounds(left + WIDTH - switchWidth, y, switchWidth, ROW).build()
        )
        y += ROW + GAP

        val half = (WIDTH - GAP) / 2
        addRenderableWidget(Button.builder(Component.literal("Find maps")) { findMaps() }.bounds(left, y, half, ROW).build())
        addRenderableWidget(Button.builder(Component.literal("Remove this server")) { removeSelected() }.bounds(left + half + GAP, y, half, ROW).build())
        y += ROW + GAP

        messageWidget = MultiLineTextWidget(left, y, message, font).setMaxWidth(WIDTH).setMaxRows(3)
        addRenderableWidget(messageWidget)
        y += font.lineHeight * 3 + GAP

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }.bounds(left, y, WIDTH, ROW).build())
    }

    private fun toggle(x: Int, y: Int, width: Int, name: String, value: Boolean, onChange: (Boolean) -> Unit): CycleButton<Boolean> =
        CycleButton.onOffBuilder(value).create(x, y, width, ROW, Component.literal(name)) { _, on -> onChange(on) }

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
        staleDays = staleBox.value
        val draft = drafts[selected]
        draft.addresses = addressBox.value
        draft.url = urlBox.value
        for ((dimension, box) in mapBoxes) {
            if (box.value.isBlank()) draft.maps.remove(dimension) else draft.maps[dimension] = box.value.trim()
        }
        draft.coverOverworld = coverBox.value
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
        val url = urlBox.value.trim()
        if (url.isEmpty()) {
            show("Type the BlueMap address first: the page you open in a browser.")
            return
        }
        show("Asking $url…")
        Thread({
            val result = runCatching { BlueMap(url).maps() }
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
        messageWidget.setMessage(message)
    }

    override fun onClose() {
        keepEdits()
        Config.enabled = enabled
        Config.showMarkers = showMarkers
        Config.showOutlines = showOutlines
        Config.showPlayers = showPlayers
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
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val DIMENSIONS = listOf(Config.OVERWORLD to "Overworld", Config.NETHER to "Nether", Config.END to "End")
    }
}
