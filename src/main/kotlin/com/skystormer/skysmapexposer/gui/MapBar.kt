package com.skystormer.skysmapexposer.gui

import com.skystormer.skysmapexposer.BiomePicks
import com.skystormer.skysmapexposer.Config
import com.skystormer.skysmapexposer.MapRefresh
import com.skystormer.skysmapexposer.Session
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import com.mojang.blaze3d.platform.InputConstants
import com.skystormer.skysmapexposer.MapMods
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.BiomeTags
import net.minecraft.world.level.biome.Biome
import java.lang.ref.WeakReference
import kotlin.math.roundToInt

/**
 * The bar on the world map, Xaero's or JourneyMap's full-screen one: this mod's switches, and a
 * biome finder.
 *
 * - The header folds the bar down to itself when clicked and moves it when dragged; **Refresh**
 *   downloads the BlueMap tiles in view again (once every 30 seconds) and **Set** opens
 *   the settings.
 * - The switches turn BlueMap's terrain, markers, borders and players on and off, for every
 *   server, as the settings do; **List** opens the player list.
 * - Below them, every biome of the dimension the map is showing, with a search box. Clicking one
 *   tints the map wherever that map knows it is ([BiomePicks]); each picked biome gets its own
 *   colour, shown beside its name. **Clear** unpicks them all.
 *
 * The list scrolls with the wheel. Moving, docking under another panel, the grips and the size
 * are [DockPanel]'s; all of it is remembered.
 */
object MapBar {

    private const val ROW = DockPanel.ROW
    private const val PAD = 2

    private const val BACKGROUND = 0x80000000.toInt()
    private const val HOVER = 0x30FFFFFF
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val OFF = 0xFF9A9A9A.toInt()

    private const val TITLE = "Map Exposer"
    private const val SET = "Set"
    private const val REFRESH = "Refresh"
    private const val CLEAR = "Clear"

    /** How wide a tooltip may be before it wraps onto another line. */
    private const val TOOLTIP_WIDTH = 190

    private class Switch(val label: String, val tip: String, val on: () -> Boolean, val press: (Screen) -> Unit)

    private val SWITCHES = listOfNotNull(
        Switch("Terrain", "BlueMap's terrain where your map is blank or old", { Config.enabled }) {
            Config.enabled = !Config.enabled; Config.save()
        },
        Switch("Markers", "BlueMap's markers: shops, banners and the like", { Config.showMarkers }) {
            Config.showMarkers = !Config.showMarkers; Config.save()
        },
        Switch("Borders", "BlueMap's outlines: the world border and zones", { Config.showOutlines }) {
            Config.showOutlines = !Config.showOutlines; Config.save()
        },
        Switch("Players", "Other players' heads, on both maps and over the ones you lock on to", { Config.showPlayers }) {
            Config.showPlayers = !Config.showPlayers; Config.save()
        },
        // Only with a minimap that shows them: Xaero's Minimap (or Fair-Play), or JourneyMap's.
        if (MapMods.adapter?.tintsMinimap == true) {
            Switch("Minimap", "The biomes you pick below, tinted on the minimap too", { Config.minimapBiomes }) {
                Config.minimapBiomes = !Config.minimapBiomes; Config.save()
            }
        } else null,
        Switch("List", "Everyone online: search, go to, lock on", { true }) { screen ->
            Minecraft.getInstance().gui.setScreen(PlayerListScreen(screen))
        },
    )

    /** The bar on the map screen open now, for the scroll hook in `GuiMapMixin`. */
    private var current = WeakReference<Panel>(null)

    fun addTo(screen: Screen) {
        val widgets = Screens.getWidgets(screen)
        val search = SearchBox()
        val panel = Panel(screen, search)
        search.setResponder { panel.searched() }
        search.covered = panel::covered
        // The panel before the box, so the box hears its own clicks once the panel passes them on.
        widgets.add(panel)
        widgets.add(search)
        current = WeakReference(panel)
    }

    /**
     * The bar on a screen that draws and handles only its own widgets (JourneyMap's full-screen map,
     * which takes every widget of its screen for one of its buttons): kept off the screen's widget
     * list, drawn after the screen and offered the mouse and keys first, through Fabric's screen
     * events. What the bar or its search box takes, the map underneath never sees.
     */
    fun attachTo(screen: Screen) {
        val search = SearchBox()
        val panel = Panel(screen, search)
        search.setResponder { panel.searched() }
        search.covered = panel::covered
        current = WeakReference(panel)
        under = null
        ScreenEvents.afterExtract(screen).register { _, graphics, mouseX, mouseY, partialTick ->
            panel.extractRenderState(graphics, mouseX, mouseY, partialTick)
            // The screen drew its tooltip before this: the bar's own would be dropped with the
            // frame, so it is drawn now, over the bar.
            graphics.extractDeferredElements(mouseX, mouseY, partialTick)
        }
        ScreenMouseEvents.allowMouseClick(screen).register { _, event ->
            when {
                search.visible && search.isMouseOver(event.x(), event.y()) -> {
                    search.isFocused = true
                    search.mouseClicked(event, false)
                    false
                }
                panel.mouseClicked(event, false) -> false
                else -> {
                    search.isFocused = false
                    true
                }
            }
        }
        ScreenMouseEvents.allowMouseDrag(screen).register { _, event, dragX, dragY -> !panel.mouseDragged(event, dragX, dragY) }
        ScreenMouseEvents.allowMouseRelease(screen).register { _, event -> !panel.mouseReleased(event) }
        ScreenMouseEvents.allowMouseScroll(screen).register { _, mouseX, mouseY, _, vertical -> !scrolled(mouseX, mouseY, vertical) }
        // While the search box has the focus, typing is the box's, not the map's keys.
        ScreenKeyboardEvents.allowKeyPress(screen).register { _, event ->
            if (!search.isFocused) return@register true
            if (event.key() == InputConstants.KEY_ESCAPE) search.isFocused = false else search.keyPressed(event)
            false
        }
        ScreenKeyboardEvents.allowCharType(screen).register { _, event ->
            if (!search.isFocused) return@register true
            search.charTyped(event)
            false
        }
    }

    /**
     * The bar on a menu that opens over the map as a screen of its own (JourneyMap's right-click
     * menu): drawn under the menu, by [drawUnder] from the menu's own drawing, and not offered the
     * mouse, so the menu is never covered or has its clicks taken.
     */
    fun showUnder(screen: Screen) {
        val panel = Panel(screen, SearchBox())
        under = WeakReference(panel)
    }

    private var under: WeakReference<Panel>? = null

    /** Draws the bar of [showUnder] for [screen], before the menu on it is drawn. */
    @JvmStatic
    fun drawUnder(screen: Any, graphics: GuiGraphicsExtractor, partialTick: Float) {
        val panel = under?.get()?.takeIf { it.isOn(screen) } ?: return
        // The mouse far away: nothing under the menu lights up or shows a tooltip.
        panel.extractRenderState(graphics, -10_000, -10_000, partialTick)
    }

    /** Called before Xaero zooms the map: true when the wheel was over the bar and scrolled it. */
    @JvmStatic
    fun scrolled(mouseX: Double, mouseY: Double, amount: Double): Boolean {
        val panel = current.get() ?: return false
        if (!panel.visible || !panel.isMouseOver(mouseX, mouseY)) return false
        panel.scroll(amount)
        return true
    }

    /**
     * The search box. A real widget of the screen, so that while it has the focus Xaero knows text
     * is being typed and keeps its own keys to itself, but drawn by the panel at the panel's size.
     */
    class SearchBox : EditBox(Minecraft.getInstance().font, 0, 0, 0, ROW, Component.literal("Search biomes")) {
        var covered: (Double, Double) -> Boolean = { _, _ -> false }
        private var drawing = false

        init {
            setBordered(false)
            setHint(Component.literal("Search biomes…"))
            setMaxLength(40)
        }

        override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
            if (drawing) super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick)
        }

        override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean = super.isMouseOver(mouseX, mouseY) && !covered(mouseX, mouseY)

        /** Draws the box at the panel's own units, then puts back where it is on screen for the mouse. */
        fun drawAt(graphics: GuiGraphicsExtractor, left: Int, top: Int, boxWidth: Int, mouseX: Int, mouseY: Int, partialTick: Float) {
            val sx = x
            val sy = y
            val sw = width
            val sh = height
            x = left; y = top; width = boxWidth; height = ROW
            drawing = true
            try {
                extractWidgetRenderState(graphics, mouseX, mouseY, partialTick)
            } finally {
                drawing = false
                x = sx; y = sy; width = sw; height = sh
            }
        }
    }

    private class Biomes(val dimension: String?, val unknownVersion: Int, val all: List<Pair<ResourceKey<Biome>, String>>)

    class Panel(screen: Screen, private val search: SearchBox) : DockPanel(screen, "skysmapexposer:bar", TITLE) {

        private var offset = 0
        override var scrollOffset: Int
            get() = offset
            set(value) { offset = value }
        private var biomes = Biomes(null, 0, emptyList())

        private val font get() = Minecraft.getInstance().font

        // Kept as a share of the room the screen leaves (as well as in GUI units, for older
        // versions), so the bar stays in the same place on screen at every GUI scale. A place
        // saved only in GUI units (an older version) is taken as it is at the scale in use now.
        override fun savedLeft(width: Int): Int {
            val room = maxOf(0, screen.width - width)
            if (Config.barLeftShare < 0) {
                Config.barLeftShare = if (room > 0) (Config.barLeft.toDouble() / room).coerceIn(0.0, 1.0) else 0.0
                Config.save()
                return Config.barLeft
            }
            return (Config.barLeftShare * room).roundToInt()
        }
        override fun saveLeft(left: Int, width: Int) {
            Config.barLeft = left
            val room = screen.width - width
            Config.barLeftShare = if (room > 0) (left.toDouble() / room).coerceIn(0.0, 1.0) else 0.0
        }
        override var savedTop: Int
            get() {
                val room = maxOf(0, screen.height - ROW)
                if (Config.barTopShare < 0) {
                    Config.barTopShare = if (room > 0) (Config.barTop.toDouble() / room).coerceIn(0.0, 1.0) else 0.0
                    Config.save()
                    return Config.barTop
                }
                return (Config.barTopShare * room).roundToInt()
            }
            set(value) {
                Config.barTop = value
                val room = screen.height - ROW
                Config.barTopShare = if (room > 0) (value.toDouble() / room).coerceIn(0.0, 1.0) else 0.0
            }
        override var savedRows: Int
            get() = Config.barRows
            set(value) { Config.barRows = value }
        override var savedOpen: Boolean
            get() = Config.barOpen
            set(value) { Config.barOpen = value }
        override var savedScale: Float
            get() = Config.barScale
            set(value) { Config.barScale = value }
        override var savedExtra: Int
            get() = Config.barExtra
            set(value) { Config.barExtra = value }
        override var savedUnder: String
            get() = Config.barUnder
            set(value) { Config.barUnder = value }
        override val maxScale get() = Config.panelMaxScale
        override fun save() = Config.save()

        override val background = BACKGROUND
        override val listTop = ROW * 3 + 1
        override val companions: List<AbstractWidget> get() = listOf(search)

        /**
         * The biomes that belong to the dimension the map is showing, by the game's own tags, by
         * name; every biome for a dimension without a tag. Biomes the map has that this game version
         * does not know are added as they are noticed (the map adapter's `unknownBiomes`). Worked out again
         * when the map changes dimension or another of those turns up.
         */
        private fun allBiomes(): List<Pair<ResourceKey<Biome>, String>> {
            val shown = MapMods.adapter?.shownDimension()
            val version = (MapMods.adapter?.unknownBiomesVersion ?: 0)
            if (shown == biomes.dimension && version == biomes.unknownVersion && biomes.all.isNotEmpty()) return biomes.all
            val registry = Minecraft.getInstance().level?.registryAccess()?.lookupOrThrow(Registries.BIOME) ?: return emptyList()
            val tag = when (shown) {
                "minecraft:the_nether" -> BiomeTags.IS_NETHER
                "minecraft:the_end" -> BiomeTags.IS_END
                "minecraft:overworld" -> BiomeTags.IS_OVERWORLD
                else -> null
            }
            val holders = registry.listElements().toList()
            val inDimension = tag?.let { t -> holders.filter { it.`is`(t) } }?.ifEmpty { null } ?: holders
            val all = (inDimension.map { it.key() } + MapMods.adapter?.unknownBiomes(shown).orEmpty()).distinct()
                .map { it to nameOf(it) }.sortedBy { it.second.lowercase() }
            if (shown != biomes.dimension) offset = 0
            biomes = Biomes(shown, version, all)
            return all
        }

        /** What the list shows: the biomes matching the search, picked ones first. */
        private fun listed(): List<Pair<ResourceKey<Biome>, String>> {
            val wanted = search.value.trim().lowercase()
            val matching = allBiomes().filter { wanted.isEmpty() || wanted in it.second.lowercase() }
            val (picked, rest) = matching.partition { BiomePicks.isPicked(it.first) }
            return picked + rest
        }

        fun searched() {
            offset = 0
        }

        private fun switchWidth(label: String) = font.width(label) + 4

        private fun switchesWidth() = SWITCHES.sumOf { switchWidth(it.label) + PAD } + PAD

        override fun naturalWidth(): Int {
            val header = 3 + font.width("- $TITLE") + 8 + switchWidth(REFRESH) + PAD + switchWidth(SET) + PAD
            return maxOf(header, switchesWidth(), 120)
        }

        override fun itemCount() = listed().size

        /**
         * Not over the search box: the screen hands a click only to the first widget under the
         * mouse, so the panel has to leave the box's rectangle to the box.
         */
        override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean =
            super.isMouseOver(mouseX, mouseY) && !(search.visible && search.isMouseOver(mouseX, mouseY))

        private fun searchWidth() = baseWidth - 6 - (if (BiomePicks.any) switchWidth(CLEAR) + PAD else 0)

        override fun afterLayout() {
            offset = offset.coerceIn(0, maxOf(0, itemCount() - shownRows))
            search.visible = open
            if (!open && search.isFocused) search.isFocused = false
            // Where the box is on screen, for the mouse: the whole row up to Clear. It is drawn by the panel.
            search.x = x + (3 * scale).toInt()
            search.y = y + (ROW * 2 * scale).toInt()
            search.width = (searchWidth() * scale).toInt()
            search.height = (ROW * scale).toInt()
        }

        fun scroll(amount: Double) {
            if (!open) return
            offset = (offset - Math.signum(amount).toInt()).coerceIn(0, maxOf(0, itemCount() - shownRows))
        }

        private val setLeft get() = baseWidth - PAD - switchWidth(SET)
        private val refreshLeft get() = setLeft - PAD - switchWidth(REFRESH)
        private val clearLeft get() = baseWidth - PAD - switchWidth(CLEAR)

        /** Which switch of the second row [lx] is on, or null. */
        private fun switchAt(lx: Double): Switch? {
            var left = PAD
            for (switch in SWITCHES) {
                val w = switchWidth(switch.label)
                if (lx >= left && lx < left + w) return switch
                left += w + PAD
            }
            return null
        }

        override fun drawLocal(graphics: GuiGraphicsExtractor, lx: Int, ly: Int, mouseX: Int, mouseY: Int, idle: Boolean, partialTick: Float) {
            val listed = listed()
            val inside = lx in 0 until baseWidth
            val mx = lx.toDouble()

            // Header: fold and move, and the settings.
            val overHeader = inside && ly in 0 until ROW
            val overSet = overHeader && mx >= setLeft
            val overRefresh = overHeader && !overSet && mx >= refreshLeft
            if (overHeader && !overSet && !overRefresh) graphics.fill(0, 0, refreshLeft - 1, ROW, HOVER)
            graphics.text(font, if (open) "- $TITLE" else "+ $TITLE", 3, 2, WHITE, false)
            // Greyed out while it cools down.
            button(graphics, REFRESH, refreshLeft, 0, Session.current != null && MapRefresh.secondsLeft() == 0, overRefresh)
            button(graphics, SET, setLeft, 0, true, overSet)
            if (idle && overSet) tooltip(graphics, "Settings: servers, BlueMap addresses, sizes, downloads", mouseX, mouseY)
            else if (idle && overRefresh) tooltip(graphics, MapRefresh.tip(), mouseX, mouseY)
            else if (idle && overHeader) tooltip(graphics, "Click to fold the bar away or open it. Drag to move it, or under another panel to dock it there.", mouseX, mouseY)
            if (!open) return

            // The switches.
            val overSwitches = inside && ly in ROW until ROW * 2
            var left = PAD
            for (switch in SWITCHES) {
                val hovered = overSwitches && switchAt(mx) === switch
                button(graphics, switch.label, left, ROW, switch.on(), hovered)
                if (idle && hovered) {
                    val state = if (switch.label == "List") "" else if (switch.on()) ": on" else ": off"
                    tooltip(graphics, switch.tip + state, mouseX, mouseY)
                }
                left += switchWidth(switch.label) + PAD
            }

            // The search box and the Clear button beside it.
            val searchTop = ROW * 2
            graphics.fill(2, searchTop + 1, baseWidth - 2, searchTop + ROW, 0x40000000)
            search.drawAt(graphics, 3, searchTop + 2, searchWidth(), lx, ly, partialTick)
            if (BiomePicks.any) {
                val overClear = inside && ly in searchTop until searchTop + ROW && mx >= clearLeft
                button(graphics, CLEAR, clearLeft, searchTop, false, overClear)
                if (idle && overClear) tooltip(graphics, "Stop highlighting every biome", mouseX, mouseY)
            }

            // The biomes, scrolled.
            val rows = shownRows
            if (listed.isEmpty()) {
                graphics.text(font, if (search.value.isBlank()) "No biomes known yet" else "No biome matches", 3, listTop + 2, OFF, false)
            }
            for (i in 0 until rows) {
                val (biome, name) = listed.getOrNull(i + offset) ?: break
                val top = listTop + i * ROW
                val colour = BiomePicks.colourOf(biome)
                val over = idle && inside && ly >= top && ly < top + ROW
                if (over) graphics.fill(0, top, baseWidth, top + ROW, HOVER)
                if (colour != null) graphics.fill(3, top + 2, 10, top + 9, colour or 0xFF000000.toInt())
                else graphics.outline(3, top + 2, 7, 7, 0x80FFFFFF.toInt())
                graphics.text(font, font.plainSubstrByWidth(name, baseWidth - 18), 14, top + 2, if (colour != null) WHITE else 0xFFD0D0D0.toInt(), false)
                if (over) tooltip(graphics, (if (colour != null) "Stop highlighting " else "Highlight ") + name + " on the map", mouseX, mouseY)
            }

        }

        private fun button(graphics: GuiGraphicsExtractor, label: String, left: Int, top: Int, on: Boolean, hovered: Boolean) {
            val fill = when {
                on && hovered -> 0x80FFFFFF.toInt()
                on -> 0x60FFFFFF
                hovered -> 0x30FFFFFF
                else -> 0x20FFFFFF
            }
            graphics.fill(left, top + 1, left + switchWidth(label), top + ROW - 1, fill)
            val lit = on || label == SET || label == CLEAR || label == "List"
            graphics.text(font, label, left + 2, top + 2, if (lit) WHITE else OFF, false)
        }

        /**
         * A tooltip wrapped onto several lines, and never above the top of the screen: the game
         * puts tooltips a little above the mouse, which near the top edge ran them off screen.
         */
        private fun tooltip(graphics: GuiGraphicsExtractor, text: String, mouseX: Int, mouseY: Int) {
            DockPanel.tooltip(graphics, text, mouseX, mouseY, TOOLTIP_WIDTH)
        }

        override fun clickLocal(lx: Double, ly: Double): Click {
            when {
                ly < ROW -> if (lx >= setLeft) {
                    Minecraft.getInstance().gui.setScreen(ConfigScreen(screen))
                    return Click.DONE
                } else if (lx >= refreshLeft) {
                    MapRefresh.press()
                    return Click.DONE
                } else {
                    search.isFocused = false
                    // The title: a drag moves the bar, a click without one folds it (on release).
                    return Click.MOVE
                }
                ly < ROW * 2 -> switchAt(lx)?.press?.invoke(screen)
                ly < ROW * 3 -> {
                    // The rest of this row is the search box's, which the mouse reaches directly.
                    if (BiomePicks.any && lx >= clearLeft) BiomePicks.clear()
                }
                else -> {
                    val row = ((ly - listTop) / ROW).toInt()
                    val listed = listed()
                    if (row in 0 until shownRows) listed.getOrNull(row + offset)?.let { BiomePicks.toggle(it.first) }
                }
            }
            search.isFocused = false
            return Click.DONE
        }
    }

    /**
     * The biome's name in the game's language, as its own screens show it; one this version has no
     * name for is named from its id (`dappled_forest` is "Dappled Forest").
     */
    private fun nameOf(key: ResourceKey<Biome>): String = BiomePicks.nameOf(key)
}
