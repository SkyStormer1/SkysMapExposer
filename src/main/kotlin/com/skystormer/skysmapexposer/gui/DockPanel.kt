package com.skystormer.skysmapexposer.gui

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A see-through panel on Xaero's world map that can be docked under another one, whichever mod
 * that one comes from, and made bigger.
 *
 * - The title moves the panel when dragged and folds it when clicked. Brought up under another
 *   panel, it docks there: the two then move as one, share a width and a size, and the lower one
 *   comes off again when dragged away. The panel docked onto sets the size.
 * - The bottom edge shows more or fewer lines, the right edge widens it, and the corner makes it
 *   bigger, only when dragged both right and down, smoothly up to a limit; nothing is ever
 *   pushed past the edge of the screen.
 * - The list's scroll bar can be clicked and dragged, as well as scrolled with the wheel.
 * - The panel last clicked is in front, and only it hears the mouse where two overlap.
 *
 * Panels find each other through Fabric's object share, under [SHARE_KEY], so neither mod needs
 * the other: a map of `"panel:<id>"` to one plain map per panel holding only numbers and strings
 * (see [record]). Each panel reads its partner's record and writes its own every frame; the size
 * and lines a partner changes are taken up and saved by the panel they belong to. Sky's Structure
 * Map has the same class, and the two must keep the same keys.
 */
abstract class DockPanel(protected val screen: Screen, private val id: String, title: String) :
    AbstractWidget(0, 0, 0, ROW, Component.literal(title)) {

    // What the owning mod saves.
    protected abstract fun savedLeft(width: Int): Int
    protected abstract fun saveLeft(left: Int, width: Int)
    protected abstract var savedTop: Int
    protected abstract var savedRows: Int
    protected abstract var savedOpen: Boolean
    protected abstract var savedScale: Float
    protected abstract var savedExtra: Int
    protected abstract var savedUnder: String
    protected abstract val maxScale: Float
    protected abstract fun save()

    protected abstract val background: Int
    /** Where the list starts, in the panel's own unscaled units. */
    protected abstract val listTop: Int
    /** The width the content needs, unscaled. */
    protected abstract fun naturalWidth(): Int
    protected abstract fun itemCount(): Int
    /** The first line of the list shown. */
    protected abstract var scrollOffset: Int
    /** Widgets that belong to the panel and come to the front with it. */
    protected open val companions: List<AbstractWidget> get() = emptyList()
    protected open fun afterLayout() {}

    /**
     * Draws the content at the panel's unscaled units, origin at its corner. [lx], [ly] are the
     * mouse there, far outside when it is elsewhere, covered or on a grip.
     */
    protected abstract fun drawLocal(graphics: GuiGraphicsExtractor, lx: Int, ly: Int, mouseX: Int, mouseY: Int, idle: Boolean, partialTick: Float)

    /** A click on the content at [lx], [ly]: [Click.MOVE] on the title, which moves or folds the panel. */
    protected abstract fun clickLocal(lx: Double, ly: Double): Click

    protected enum class Click { MOVE, DONE }

    private enum class Drag { NONE, MOVE, ROWS, WIDTH, CORNER, SCROLL }

    private var drag = Drag.NONE
    private var grabX = 0.0
    private var grabY = 0.0
    private var startX = 0.0
    private var startY = 0.0
    private var moved = false
    private var snapTo: MutableMap<String, Any>? = null
    private var cornerBox = IntArray(4)
    private var cornerScale = 1f
    private var wantFront = false
    private var thumbGrab = 0.0

    /** The panel's unscaled width and height, as last laid out. */
    protected var baseWidth = 0
        private set
    protected var baseHeight = ROW
        private set
    /** How many lines of the list show. */
    protected var shownRows = 1
        private set
    protected var scale = 1f
        private set
    protected val open get() = record.bool("open")

    private val screenKey = System.identityHashCode(screen)

    /** This panel's record in the share, made from the saved settings when the screen opens. */
    private val record: MutableMap<String, Any> by lazy {
        val r = HashMap<String, Any>()
        r["screen"] = screenKey
        r["id"] = id
        r["seq"] = 0
        r["rows"] = savedRows
        r["open"] = savedOpen
        r["scale"] = savedScale
        r["extra"] = savedExtra
        r["under"] = savedUnder
        r["top"] = savedTop
        r["left"] = savedLeft(ceil((naturalWidth() + savedExtra) * savedScale).toInt())
        r["x"] = 0; r["y"] = 0; r["w"] = 0; r["h"] = 0
        r["natural"] = naturalWidth()
        r["fixed"] = ROW
        root()["panel:$id"] = r
        r
    }

    init {
        ScreenEvents.beforeTick(screen).register { comeForward() }
    }

    private fun others(): List<MutableMap<String, Any>> = root().entries
        .filter { it.key.startsWith("panel:") && it.key != "panel:$id" }
        .mapNotNull { @Suppress("UNCHECKED_CAST") (it.value as? MutableMap<String, Any>) }
        .filter { it["screen"] == screenKey }

    private fun other(otherId: String): MutableMap<String, Any>? = others().firstOrNull { it.str("under") != id && it["id"] == otherId }

    /** The panel this one is docked under, if it is on this screen. */
    private fun above() = record.str("under").takeIf { it.isNotEmpty() }?.let(::other)

    /** The panel docked under this one. */
    private fun below() = others().firstOrNull { it.str("under") == id }

    private fun partner() = above() ?: below()

    private fun step() = STEP

    /** [value] between 1 and the limit, in steps fine enough that dragging the corner feels smooth. */
    private fun snapScale(value: Float): Float =
        ((value / STEP).roundToInt() * STEP).coerceIn(1f, maxOf(1f, maxScale))

    private fun sharedNatural(partner: Map<String, Any>?) = maxOf(naturalWidth(), partner?.int("natural") ?: 0)

    private fun fixedHeight() = if (open) listTop + 1 + GRIP else ROW

    private fun layout() {
        val r = record
        val above = above()
        val below = below()
        scale = snapScale(r.float("scale"))
        baseWidth = sharedNatural(above ?: below) + r.int("extra")
        width = ceil(baseWidth * scale).toInt()
        val belowHeight = below?.int("h") ?: 0
        val snap = snapTo
        if (above != null || snap != null) {
            val onto = above ?: snap!!
            x = onto.int("x")
            y = onto.int("y") + onto.int("h")
        } else {
            x = r.int("left").coerceIn(0, maxOf(0, screen.width - width))
            y = r.int("top").coerceIn(0, maxOf(0, screen.height - ceil(ROW * scale).toInt() - belowHeight))
        }
        val room = floor(((screen.height - y - belowHeight) / scale - listTop - 1 - GRIP) / ROW).toInt()
        shownRows = minOf(r.int("rows"), maxOf(1, itemCount()), maxOf(1, room)).coerceAtLeast(1)
        baseHeight = if (open) listTop + shownRows * ROW + 1 + GRIP else ROW
        height = ceil(baseHeight * scale).toInt()
        // Docked, the place it would go back to is where it is, so it never jumps if the other
        // panel is missing for a frame.
        if (above != null) { r["left"] = x; r["top"] = y }
        r["x"] = x; r["y"] = y; r["w"] = width; r["h"] = height
        r["natural"] = naturalWidth()
        r["fixed"] = fixedHeight()
        afterLayout()
    }

    /** Saves whatever changed, this panel's doing or its partner's. */
    private fun persist() {
        val r = record
        var changed = false
        if (savedTop != r.int("top")) { savedTop = r.int("top"); changed = true }
        if (savedLeft(width) != r.int("left")) { saveLeft(r.int("left"), width); changed = true }
        if (savedRows != r.int("rows")) { savedRows = r.int("rows"); changed = true }
        if (savedOpen != r.bool("open")) { savedOpen = r.bool("open"); changed = true }
        if (savedScale != r.float("scale")) { savedScale = r.float("scale"); changed = true }
        if (savedExtra != r.int("extra")) { savedExtra = r.int("extra"); changed = true }
        if (savedUnder != r.str("under")) { savedUnder = r.str("under"); changed = true }
        if (changed) save()
    }

    /** True when another panel is in front of this one at that point. */
    fun covered(mouseX: Double, mouseY: Double): Boolean {
        val seq = record.int("seq")
        return others().any {
            it.int("seq") > seq && mouseX >= it.int("x") && mouseX < it.int("x") + it.int("w") &&
                mouseY >= it.int("y") && mouseY < it.int("y") + it.int("h")
        }
    }

    override fun isMouseOver(mouseX: Double, mouseY: Double): Boolean =
        visible && mouseX >= x && mouseY >= y && mouseX < x + width && mouseY < y + height && !covered(mouseX, mouseY)

    private enum class Zone { CORNER, WIDTH, ROWS, SCROLL, CONTENT }

    private fun scrolls() = open && itemCount() > shownRows
    private val trackLeft get() = baseWidth - EDGE - BAR
    private val trackHeight get() = shownRows * ROW
    private fun thumbHeight() = maxOf(4, trackHeight * shownRows / maxOf(1, itemCount()))
    private fun thumbTop(): Int {
        val most = itemCount() - shownRows
        return listTop + if (most > 0) (trackHeight - thumbHeight()) * scrollOffset / most else 0
    }

    private fun zone(lx: Double, ly: Double): Zone = when {
        open && lx >= baseWidth - CORNER && ly >= baseHeight - CORNER -> Zone.CORNER
        open && lx >= baseWidth - EDGE && ly >= ROW -> Zone.WIDTH
        scrolls() && lx >= trackLeft - 2 && ly >= listTop && ly < listTop + trackHeight -> Zone.SCROLL
        open && ly >= baseHeight - GRIP -> Zone.ROWS
        else -> Zone.CONTENT
    }

    private fun localX(mouseX: Double) = (mouseX - x) / scale
    private fun localY(mouseY: Double) = (mouseY - y) / scale

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        layout()
        if (drag == Drag.NONE) persist()
        val idle = drag == Drag.NONE
        val over = isMouseOver(mouseX.toDouble(), mouseY.toDouble())
        val lxd = localX(mouseX.toDouble())
        val lyd = localY(mouseY.toDouble())
        val zone = if (over) zone(lxd, lyd) else null
        val onContent = (over && zone == Zone.CONTENT) || drag == Drag.MOVE
        val lx = if (onContent) floor(lxd).toInt() else FAR
        val ly = if (onContent) floor(lyd).toInt() else FAR

        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(scale, scale)
        graphics.fill(0, 0, baseWidth, baseHeight, background)
        drawLocal(graphics, lx, ly, mouseX, mouseY, idle, partialTick)
        if (open) {
            val lit = 0xC0FFFFFF.toInt()
            val rowsLit = (idle && zone == Zone.ROWS) || drag == Drag.ROWS
            graphics.fill(baseWidth / 2 - 8, baseHeight - GRIP + 1, baseWidth / 2 + 8, baseHeight - GRIP + 2, if (rowsLit) lit else 0x50FFFFFF)
            if (scrolls()) {
                val thumbLit = (idle && zone == Zone.SCROLL) || drag == Drag.SCROLL
                graphics.fill(trackLeft, listTop, trackLeft + BAR, listTop + trackHeight, 0x40FFFFFF)
                graphics.fill(trackLeft, thumbTop(), trackLeft + BAR, thumbTop() + thumbHeight(), if (thumbLit) 0xFFFFFFFF.toInt() else 0xC0FFFFFF.toInt())
            }
            if ((idle && zone == Zone.WIDTH) || drag == Drag.WIDTH) graphics.fill(baseWidth - 1, ROW, baseWidth, baseHeight - CORNER, lit)
            val cornerColour = if ((idle && zone == Zone.CORNER) || drag == Drag.CORNER) 0xFFFFFFFF.toInt() else 0x70FFFFFF
            graphics.fill(baseWidth - 2, baseHeight - 2, baseWidth - 1, baseHeight - 1, cornerColour)
            graphics.fill(baseWidth - 4, baseHeight - 2, baseWidth - 3, baseHeight - 1, cornerColour)
            graphics.fill(baseWidth - 2, baseHeight - 4, baseWidth - 1, baseHeight - 3, cornerColour)
        }
        pose.popMatrix()

        // Where it will dock if let go.
        snapTo?.let { onto ->
            graphics.outline(onto.int("x") - 1, onto.int("y") - 1, onto.int("w") + 2, onto.int("h") + height + 2, 0xFFFFFFFF.toInt())
        }
        if (idle) when (zone) {
            Zone.ROWS -> tip(graphics, "Drag to show more or fewer lines", mouseX, mouseY)
            Zone.WIDTH -> tip(graphics, "Drag to widen", mouseX, mouseY)
            Zone.CORNER -> tip(graphics, "Drag right and down together to make it bigger", mouseX, mouseY)
            else -> {}
        }
        if (drag == Drag.CORNER) tip(graphics, "Size ${"%.2f".format(scale).trimEnd('0').trimEnd('.')}x", mouseX, mouseY)
    }

    private fun tip(graphics: GuiGraphicsExtractor, text: String, mouseX: Int, mouseY: Int) {
        val font = Minecraft.getInstance().font
        graphics.setTooltipForNextFrame(font, font.split(Component.literal(text), 190), mouseX, maxOf(mouseY, 16))
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (!visible || event.button() != 0 || !isMouseOver(event.x(), event.y())) return false
        val lx = localX(event.x())
        val ly = localY(event.y())
        when (zone(lx, ly)) {
            Zone.CORNER -> {
                val top = above() ?: record
                val bottom = if (top === record) below() else record
                cornerBox = intArrayOf(top.int("x"), top.int("y"), width, height + (if (bottom === record) top.int("h") else bottom?.int("h") ?: 0))
                cornerScale = scale
                begin(Drag.CORNER, event)
            }
            Zone.WIDTH -> begin(Drag.WIDTH, event)
            Zone.ROWS -> begin(Drag.ROWS, event)
            Zone.SCROLL -> {
                // On the thumb it is held where it was taken; on the track it jumps there first.
                val top = thumbTop()
                thumbGrab = if (ly >= top && ly < top + thumbHeight()) ly - top else thumbHeight() / 2.0
                begin(Drag.SCROLL, event)
                scrollTo(ly)
            }
            Zone.CONTENT -> when (clickLocal(lx, ly)) {
                Click.MOVE -> {
                    grabX = event.x() - x
                    grabY = event.y() - y
                    begin(Drag.MOVE, event)
                }
                Click.DONE -> {}
            }
        }
        toFront()
        layout()
        // Taken here, so the map underneath does not also get the click.
        return true
    }

    private fun begin(kind: Drag, event: MouseButtonEvent) {
        drag = kind
        startX = event.x()
        startY = event.y()
        moved = false
    }

    private fun toFront() {
        val r = root()
        val seq = ((r["seq"] as? Number)?.toInt() ?: 0) + 1
        r["seq"] = seq
        record["seq"] = seq
        wantFront = true
    }

    /** Last in the screen's widgets, so drawn over the others; done on a tick, never mid-click. */
    private fun comeForward() {
        if (!wantFront) return
        wantFront = false
        val widgets = Screens.getWidgets(screen)
        for (widget in listOf<AbstractWidget>(this) + companions) {
            if (widgets.remove(widget)) widgets.add(widget)
        }
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val r = record
        val mx = event.x()
        val my = event.y()
        when (drag) {
            Drag.NONE -> return false
            Drag.MOVE -> {
                val distance = abs(mx - startX) + abs(my - startY)
                if (distance > 3) moved = true
                if (r.str("under").isNotEmpty()) {
                    // Docked: it takes a deliberate pull to come off.
                    if (distance < PULL) return true
                    r["under"] = ""
                }
                r["left"] = (mx - grabX).toInt().coerceIn(0, maxOf(0, screen.width - width))
                r["top"] = (my - grabY).toInt().coerceIn(0, maxOf(0, screen.height - ROW))
                snapTo = if (below() == null) findSnap(r.int("left"), r.int("top")) else null
            }
            Drag.SCROLL -> scrollTo(localY(my))
            Drag.ROWS -> r["rows"] = floor(((my - y) / scale - listTop) / ROW).toInt().coerceIn(1, maxOf(1, minOf(itemCount(), MAX_ROWS)))
            Drag.WIDTH -> {
                val natural = sharedNatural(partner())
                val most = floor((screen.width - x) / scale - natural).toInt()
                val extra = floor((mx - x) / scale - natural).toInt().coerceIn(0, maxOf(0, most))
                r["extra"] = extra
                partner()?.set("extra", extra)
            }
            Drag.CORNER -> {
                val (x0, y0, w0, h0) = cornerBox
                val grow = minOf((mx - x0) / w0, (my - y0) / h0).toFloat()
                var size = snapScale(cornerScale * grow)
                val partner = partner()
                val baseStack = baseHeight + (partner?.let { it.int("h") / it.float("scale") } ?: 0f)
                while (size > 1f && (x0 + baseWidth * size > screen.width || y0 + baseStack * size > screen.height)) size -= step()
                size = snapScale(size)
                r["scale"] = size
                partner?.set("scale", size)
            }
        }
        layout()
        return true
    }

    /** Scrolls so the thumb's grabbed point is at [ly]. */
    private fun scrollTo(ly: Double) {
        val most = itemCount() - shownRows
        val travel = trackHeight - thumbHeight()
        if (most <= 0 || travel <= 0) return
        scrollOffset = ((ly - thumbGrab - listTop) / travel * most).roundToInt().coerceIn(0, most)
    }

    /** The panel this one would dock under at [left], [top]: just below its bottom edge and mostly lined up with it. */
    private fun findSnap(left: Int, top: Int): MutableMap<String, Any>? = others().firstOrNull { o ->
        if (o.str("under") == id || others().any { it !== o && it.str("under") == o["id"] }) return@firstOrNull false
        val ox = o.int("x")
        val ow = o.int("w")
        val bottom = o.int("y") + o.int("h")
        val overlap = minOf(left + width, ox + ow) - maxOf(left, ox)
        overlap >= minOf(width, ow) / 2 && top >= bottom - LIFT && top <= bottom + REACH
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (drag == Drag.NONE) return false
        val r = record
        if (drag == Drag.MOVE && !moved) r["open"] = !open
        snapTo?.let { dock(it) }
        snapTo = null
        drag = Drag.NONE
        layout()
        persist()
        return true
    }

    /** Docks under [onto], taking its size, then makes the pair fit the screen. */
    private fun dock(onto: MutableMap<String, Any>) {
        val r = record
        r["under"] = onto.str("id")
        r["scale"] = onto.float("scale")
        r["extra"] = onto.int("extra")
        var size = snapScale(onto.float("scale"))
        var extra = onto.int("extra")
        var myRows = r.int("rows")
        var theirRows = onto.int("rows")
        val myOpen = open
        val theirOpen = onto.bool("open")
        val myFixed = fixedHeight()
        val theirFixed = onto.int("fixed")
        val natural = maxOf(naturalWidth(), onto.int("natural"))
        fun stackWidth() = (natural + extra) * size
        fun stackHeight() = (myFixed + (if (myOpen) myRows * ROW else 0) + theirFixed + (if (theirOpen) theirRows * ROW else 0)) * size
        while (stackWidth() > screen.width && extra > 0) extra = maxOf(0, extra - 4)
        while (stackHeight() > screen.height) {
            when {
                maxOf(myRows, theirRows) > 3 -> if (myRows >= theirRows) myRows-- else theirRows--
                size > 1f -> size = snapScale(size - step())
                maxOf(myRows, theirRows) > 1 -> if (myRows >= theirRows) myRows-- else theirRows--
                else -> break
            }
        }
        while (stackWidth() > screen.width && size > 1f) size = snapScale(size - step())
        r["scale"] = size; onto["scale"] = size
        r["extra"] = extra; onto["extra"] = extra
        r["rows"] = myRows; onto["rows"] = theirRows
        onto["left"] = minOf(onto.int("x"), (screen.width - stackWidth()).toInt()).coerceAtLeast(0)
        onto["top"] = minOf(onto.int("y"), (screen.height - stackHeight()).toInt()).coerceAtLeast(0)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        defaultButtonNarrationText(output)
    }

    companion object {
        const val ROW = 11
        const val GRIP = 4
        private const val EDGE = 2
        /** The scroll bar's width, just inside the edge that widens the panel. */
        const val BAR = 2
        private const val CORNER = 5
        private const val PULL = 12
        private const val LIFT = 6
        private const val REACH = 24
        private const val MAX_ROWS = 40
        private const val FAR = -10_000
        /** Sizes go in hundredths: as smooth as the edges, without saving long fractions. */
        private const val STEP = 0.01f

        /** Shared with Sky's Structure Map: keep the key and the record's fields the same in both. */
        private const val SHARE_KEY = "skysmaps:panels"

        private fun root(): MutableMap<String, Any> {
            val share = FabricLoader.getInstance().objectShare
            @Suppress("UNCHECKED_CAST")
            (share.get(SHARE_KEY) as? MutableMap<String, Any>)?.let { return it }
            val fresh = HashMap<String, Any>()
            share.put(SHARE_KEY, fresh)
            return fresh
        }

        private fun Map<String, Any>.int(key: String) = (this[key] as? Number)?.toInt() ?: 0
        private fun Map<String, Any>.float(key: String) = (this[key] as? Number)?.toFloat() ?: 1f
        private fun Map<String, Any>.bool(key: String) = this[key] as? Boolean ?: true
        private fun Map<String, Any>.str(key: String) = this[key] as? String ?: ""
    }
}
