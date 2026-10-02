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
 * - The title moves the panel when dragged and folds it when clicked. Brought up under the bottom
 *   panel of another stack, it docks there, bringing any panels docked under it along: a stack
 *   moves as one by its top title, shares one width and one size, and a lower panel comes off
 *   again, with the ones under it, when dragged away. The stack docked onto sets the size.
 * - The bottom edge shows more or fewer lines, the right edge widens it, and the corner makes it
 *   bigger or smaller (down to half size), smoothly up to a limit; nothing is ever pushed past
 *   the edge of the screen. A panel with [fixedRows] is always as tall as its content.
 * - The list's scroll bar can be clicked and dragged, as well as scrolled with the wheel.
 * - The panel last clicked is in front, and only it hears the mouse where two overlap.
 *
 * Panels find each other through Fabric's object share, under [SHARE_KEY], so no mod needs
 * another: a map of `"panel:<id>"` to one plain map per panel holding only numbers and strings
 * (see [record]). Each panel reads the records of its stack and writes its own every frame; the
 * size and lines another panel changes are taken up and saved by the panel they belong to. Saved
 * docking that no longer makes sense (two panels under one, or a loop) is undone on the first frame.
 * Sky's Map Exposer, Sky's Structure Map and Sky's Map Shapes have this same class; all three
 * must keep the same keys.
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
    /**
     * The width the panel had when its place was saved, for a mod that saves the place from the
     * right edge, so it reads back to the same spot; 0 and ignored for one that does not need it.
     */
    protected open var savedWidth: Int
        get() = 0
        set(_) {}
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
    /** True for a panel whose lines are its content, not a list: always all shown, no bottom grip, no scroll bar. */
    protected open val fixedRows: Boolean get() = false

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
        // Read back with the width it was saved with: a docked panel takes its stack's width, which
        // its own would not match, and the panel would creep sideways every time the screen is set
        // up again (as Xaero does whenever one of its menus opens).
        r["left"] = savedLeft((root()["width:$id"] as? Number)?.toInt()?.takeIf { it > 0 }
            ?: savedWidth.takeIf { it > 0 }
            ?: ceil((naturalWidth() + savedExtra) * savedScale).toInt())
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

    private fun all(): List<MutableMap<String, Any>> = others() + record

    /** The panel [r] is docked under, if it is on this screen. */
    private fun aboveOf(r: Map<String, Any>): MutableMap<String, Any>? {
        val under = r.str("under").takeIf { it.isNotEmpty() } ?: return null
        return all().firstOrNull { it !== r && it.str("id") == under }
    }

    /** The panel docked under [r]. */
    private fun belowOf(r: Map<String, Any>): MutableMap<String, Any>? =
        all().firstOrNull { it !== r && it.str("under") == r.str("id") }

    /** The stack [r] is in, from its top panel down; never loops, whatever the records say. */
    private fun stackOf(r: MutableMap<String, Any>): List<MutableMap<String, Any>> {
        var top = r
        val seen = HashSet<String>()
        seen += top.str("id")
        while (true) {
            val above = aboveOf(top) ?: break
            if (!seen.add(above.str("id"))) break
            top = above
        }
        val stack = ArrayList<MutableMap<String, Any>>()
        var next: MutableMap<String, Any>? = top
        while (next != null && stack.none { it === next }) {
            stack += next
            next = belowOf(next)
        }
        if (stack.none { it === r }) stack += r
        return stack
    }

    private fun stack() = stackOf(record)

    /** This panel and the ones docked under it: what moves when it is dragged. */
    private fun fromHereDown() = stack().let { s -> s.subList(s.indexOfFirst { it === record }, s.size) }

    /**
     * Undoes saved docking that cannot be laid out: under a panel that another one, first by id,
     * is also docked under, or under a panel that is (through others) docked under this one.
     */
    private fun untangle() {
        val r = record
        val under = r.str("under").takeIf { it.isNotEmpty() } ?: return
        val rival = others().any { it.str("under") == under && it.str("id") < id }
        var loops = false
        var walk = aboveOf(r)
        val seen = HashSet<String>()
        while (walk != null && seen.add(walk.str("id"))) {
            if (walk.str("under") == id) { loops = true; break }
            walk = aboveOf(walk)
        }
        if (rival || loops) r["under"] = ""
    }

    private fun step() = STEP

    /** [value] between [MIN_SCALE] and the limit, in steps fine enough that dragging the corner feels smooth. */
    private fun snapScale(value: Float): Float =
        ((value / STEP).roundToInt() * STEP).coerceIn(MIN_SCALE, maxOf(1f, maxScale))

    private fun sharedNatural(stack: List<Map<String, Any>>) = stack.maxOf { if (it === record) naturalWidth() else it.int("natural") }

    private fun fixedHeight() = if (open) listTop + 1 + GRIP else ROW

    private fun layout() {
        val r = record
        if (fixedRows) r["rows"] = maxOf(1, itemCount())
        untangle()
        val stack = stack()
        val index = stack.indexOfFirst { it === r }
        scale = snapScale(r.float("scale"))
        baseWidth = sharedNatural(stack) + r.int("extra")
        width = ceil(baseWidth * scale).toInt()
        val belowHeight = stack.drop(index + 1).sumOf { it.int("h") }
        val snap = snapTo
        if (index > 0) {
            // Under the stack's top panel and every panel between.
            val top = stack[0]
            x = top.int("x")
            y = top.int("y") + stack.subList(0, index).sumOf { it.int("h") }
        } else if (snap != null) {
            val onto = stackOf(snap)
            x = onto[0].int("x")
            y = onto[0].int("y") + onto.sumOf { it.int("h") }
        } else {
            x = r.int("left").coerceIn(0, maxOf(0, screen.width - width))
            y = r.int("top").coerceIn(0, maxOf(0, screen.height - ceil(ROW * scale).toInt() - belowHeight))
        }
        val room = floor(((screen.height - y - belowHeight) / scale - listTop - 1 - GRIP) / ROW).toInt()
        shownRows = if (fixedRows) maxOf(1, itemCount()) else minOf(r.int("rows"), maxOf(1, itemCount()), maxOf(1, room)).coerceAtLeast(1)
        baseHeight = if (open) listTop + shownRows * ROW + 1 + GRIP else ROW
        height = ceil(baseHeight * scale).toInt()
        // Docked, the place it would go back to is where it is, so it never jumps if the other
        // panel is missing for a frame.
        if (index > 0) { r["left"] = x; r["top"] = y }
        r["x"] = x; r["y"] = y; r["w"] = width; r["h"] = height
        r["natural"] = naturalWidth()
        r["fixed"] = fixedHeight()
        afterLayout()
    }

    /** Saves whatever changed, this panel's doing or another in its stack's. */
    private fun persist() {
        val r = record
        var changed = false
        if (savedTop != r.int("top")) { savedTop = r.int("top"); changed = true }
        if (savedLeft(width) != r.int("left")) { saveLeft(r.int("left"), width); changed = true }
        // The width the place was saved with, for reading it back (see [record]).
        root()["width:$id"] = width
        if (savedWidth != width) {
            savedWidth = width
            // A panel that does not keep it reads back 0, which is not a change worth saving.
            if (savedWidth == width) changed = true
        }
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

    private fun scrolls() = open && !fixedRows && itemCount() > shownRows
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
        open && !fixedRows && ly >= baseHeight - GRIP -> Zone.ROWS
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

        // The background fills the whole space the panel takes in the stack, which is rounded up to
        // whole units; painted after scaling, a fractional size would leave a thin gap under it.
        graphics.fill(x, y, x + width, y + height, background)
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(scale, scale)
        drawLocal(graphics, lx, ly, mouseX, mouseY, idle, partialTick)
        if (open) {
            val lit = 0xC0FFFFFF.toInt()
            val rowsLit = (idle && zone == Zone.ROWS) || drag == Drag.ROWS
            if (!fixedRows) graphics.fill(baseWidth / 2 - 8, baseHeight - GRIP + 1, baseWidth / 2 + 8, baseHeight - GRIP + 2, if (rowsLit) lit else 0x50FFFFFF)
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
            Zone.CORNER -> tip(graphics, "Drag to make it bigger or smaller", mouseX, mouseY)
            else -> {}
        }
        if (drag == Drag.CORNER) tip(graphics, "Size ${"%.2f".format(scale).trimEnd('0').trimEnd('.')}x", mouseX, mouseY)
    }

    private fun tip(graphics: GuiGraphicsExtractor, text: String, mouseX: Int, mouseY: Int) = tooltip(graphics, text, mouseX, mouseY)

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (!visible || event.button() != 0 || !isMouseOver(event.x(), event.y())) return false
        val lx = localX(event.x())
        val ly = localY(event.y())
        when (zone(lx, ly)) {
            Zone.CORNER -> {
                val stack = stack()
                val top = stack[0]
                cornerBox = intArrayOf(top.int("x"), top.int("y"), width, stack.sumOf { if (it === record) height else it.int("h") })
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

    /** In front of the other panels, as if just clicked. */
    fun toFront() {
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
                snapTo = findSnap(r.int("left"), r.int("top"))
            }
            Drag.SCROLL -> scrollTo(localY(my))
            Drag.ROWS -> r["rows"] = floor(((my - y) / scale - listTop) / ROW).toInt().coerceIn(1, maxOf(1, minOf(itemCount(), MAX_ROWS)))
            Drag.WIDTH -> {
                val stack = stack()
                val natural = sharedNatural(stack)
                val most = floor((screen.width - x) / scale - natural).toInt()
                val extra = floor((mx - x) / scale - natural).toInt().coerceIn(0, maxOf(0, most))
                for (panel in stack) panel["extra"] = extra
            }
            Drag.CORNER -> {
                val (x0, y0, w0, h0) = cornerBox
                val grow = minOf((mx - x0) / w0, (my - y0) / h0).toFloat()
                var size = snapScale(cornerScale * grow)
                val stack = stack()
                val baseStack = stack.sumOf { if (it === r) baseHeight.toDouble() else (it.int("h") / it.float("scale")).toDouble() }.toFloat()
                while (size > MIN_SCALE && (x0 + baseWidth * size > screen.width || y0 + baseStack * size > screen.height)) size -= step()
                size = snapScale(size)
                for (panel in stack) panel["scale"] = size
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

    /**
     * The panel this one would dock under at [left], [top]: the bottom panel of another stack,
     * with this one just below its bottom edge and mostly lined up with it.
     */
    private fun findSnap(left: Int, top: Int): MutableMap<String, Any>? {
        val moving = fromHereDown()
        return others().firstOrNull { o ->
            if (moving.any { it === o } || belowOf(o) != null) return@firstOrNull false
            val ox = o.int("x")
            val ow = o.int("w")
            val bottom = o.int("y") + o.int("h")
            val overlap = minOf(left + width, ox + ow) - maxOf(left, ox)
            overlap >= minOf(width, ow) / 2 && top >= bottom - LIFT && top <= bottom + REACH
        }
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

    /** Docks under [onto], taking its stack's size, then makes the whole stack fit the screen. */
    private fun dock(onto: MutableMap<String, Any>) {
        val r = record
        r["under"] = onto.str("id")
        val stack = stack()
        var size = snapScale(onto.float("scale"))
        var extra = onto.int("extra")
        val rows = stack.map { it.int("rows") }.toIntArray()
        val opens = stack.map { if (it === r) open else it.bool("open") }
        val fixed = stack.map { if (it === r) fixedHeight() else it.int("fixed") }
        val natural = sharedNatural(stack)
        fun stackWidth() = (natural + extra) * size
        fun stackHeight() = stack.indices.sumOf { fixed[it] + if (opens[it]) rows[it] * ROW else 0 } * size
        fun tallest() = stack.indices.filter { opens[it] }.maxByOrNull { rows[it] }
        while (stackWidth() > screen.width && extra > 0) extra = maxOf(0, extra - 4)
        while (stackHeight() > screen.height) {
            val t = tallest()
            when {
                t != null && rows[t] > 3 -> rows[t]--
                size > MIN_SCALE -> size = snapScale(size - step())
                t != null && rows[t] > 1 -> rows[t]--
                else -> break
            }
        }
        while (stackWidth() > screen.width && size > MIN_SCALE) size = snapScale(size - step())
        stack.forEachIndexed { i, panel ->
            panel["scale"] = size
            panel["extra"] = extra
            panel["rows"] = rows[i]
        }
        val top = stack[0]
        top["left"] = minOf(top.int("x"), (screen.width - stackWidth()).toInt()).coerceAtLeast(0)
        top["top"] = minOf(top.int("y"), (screen.height - stackHeight()).toInt()).coerceAtLeast(0)
    }

    /**
     * Takes this panel out of the share, for one that goes away while the screen stays open. Any
     * panel docked under it stays where it is, on its own.
     */
    fun leave() {
        root().remove("panel:$id")
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        defaultButtonNarrationText(output)
    }

    companion object {
        /**
         * Shows [text] as a tooltip near the mouse, kept wholly on screen. Vanilla only keeps a
         * tooltip off the right and bottom edges, so at a big GUI scale a long one ran off the top
         * or out of the side. This wraps it to fit the screen, cuts it short if it still cannot
         * fit, and places it itself.
         */
        fun tooltip(graphics: GuiGraphicsExtractor, text: String, mouseX: Int, mouseY: Int, wrap: Int = 190) {
            val font = Minecraft.getInstance().font
            val screenWidth = graphics.guiWidth()
            val screenHeight = graphics.guiHeight()
            val widest = maxOf(40, screenWidth - TIP_EDGE * 2)
            val tallest = maxOf(1, (screenHeight - TIP_EDGE * 2 - 2) / TIP_LINE)
            var lines = font.split(Component.literal(text), minOf(wrap, widest))
            // Too tall for the screen: use the whole width before giving up on any of it.
            if (lines.size > tallest && wrap < widest) lines = font.split(Component.literal(text), widest)
            if (lines.size > tallest) lines = lines.take(tallest - 1) + font.split(Component.literal("…"), widest)
            val width = lines.maxOf { font.width(it) }
            val height = lines.size * TIP_LINE + 2
            var x = mouseX + 12
            if (x + width + TIP_EDGE > screenWidth) x = mouseX - 16 - width
            x = x.coerceIn(TIP_EDGE, maxOf(TIP_EDGE, screenWidth - width - TIP_EDGE))
            val y = (mouseY - 12).coerceIn(TIP_EDGE, maxOf(TIP_EDGE, screenHeight - height - TIP_EDGE))
            // Vanilla moves a tooltip 12 right of and 12 above the point it is given.
            graphics.setTooltipForNextFrame(font, lines, x - 12, y + 12)
        }

        /** Space kept between a tooltip and the screen's edge, its border included. */
        private const val TIP_EDGE = 5

        /** Height of one line of a tooltip. */
        private const val TIP_LINE = 10

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
        /** The smallest a panel can be made: half size. */
        const val MIN_SCALE = 0.5f

        /** Shared with the other two mods: keep the key and the record's fields the same in all three. */
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
