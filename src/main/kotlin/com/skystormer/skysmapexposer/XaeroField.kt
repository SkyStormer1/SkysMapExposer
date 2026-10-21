package com.skystormer.skysmapexposer

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/**
 * A field of one of Xaero's classes, found by name the first time it is needed. Mixin's `@Shadow`
 * fields stop the game from starting when a Xaero update renames one; this way a newer Xaero
 * without it only switches off what uses it ([found]), and `/mapexposer` says so ([missing]).
 * The mod declares no highest Xaero version, so this is what keeps a new one from locking anyone out.
 */
class XaeroField(private val className: String, private val name: String, private val feature: String) {

    private val handle: Field? by lazy {
        try {
            Class.forName(className, false, XaeroField::class.java.classLoader).getDeclaredField(name).apply { isAccessible = true }
        } catch (e: Throwable) {
            synchronized(MISSING) { MISSING.add("$feature (${className.substringAfterLast('.')}.$name)") }
            Log.warn("This Xaero version has no {}.{}; {} is off", className, name, feature)
            null
        }
    }

    /** Whether this Xaero has the field; without it, whatever uses it does nothing. */
    val found: Boolean get() = handle != null

    private val static: Boolean by lazy { handle?.let { Modifier.isStatic(it.modifiers) } ?: false }

    private fun owner(of: Any?) = if (static) null else of

    fun get(of: Any?): Any? = handle?.get(owner(of))
    fun getDouble(of: Any?): Double = handle?.getDouble(owner(of)) ?: 0.0
    fun getInt(of: Any?): Int = handle?.getInt(owner(of)) ?: 0
    fun getBoolean(of: Any?): Boolean = handle?.getBoolean(owner(of)) ?: false
    fun set(of: Any?, value: Any?) { handle?.set(owner(of), value) }

    companion object {
        private val MISSING = LinkedHashSet<String>()

        /** What is off because this Xaero lacks a field it needs. */
        fun missing(): List<String> = synchronized(MISSING) { MISSING.toList() }

        /** Whether all of [fields] are there. */
        @JvmStatic
        fun all(vararg fields: XaeroField): Boolean = fields.all { it.found }
    }
}

/** The fields of Xaero's world map screen (`GuiMap`) this mod reads and moves. */
object GuiMapFields {
    private const val GUI_MAP = "xaero.map.gui.GuiMap"
    private const val CAMERA = "moving the map to a player"
    private const val DRAWING = "drawing on the world map"
    private const val MENU = "the map's right-click options"

    @JvmField val cameraX = XaeroField(GUI_MAP, "cameraX", DRAWING)
    @JvmField val cameraZ = XaeroField(GUI_MAP, "cameraZ", DRAWING)
    @JvmField val mapProcessor = XaeroField(GUI_MAP, "mapProcessor", DRAWING)
    @JvmField val rightClickX = XaeroField(GUI_MAP, "rightClickX", MENU)
    @JvmField val rightClickZ = XaeroField(GUI_MAP, "rightClickZ", MENU)
    @JvmField val rightClickDim = XaeroField(GUI_MAP, "rightClickDim", MENU)
    @JvmField val mapTileSelection = XaeroField(GUI_MAP, "mapTileSelection", MENU)
    @JvmField val cameraDestination = XaeroField(GUI_MAP, "cameraDestination", CAMERA)
    @JvmField val cameraDestinationAnimX = XaeroField(GUI_MAP, "cameraDestinationAnimX", CAMERA)
    @JvmField val cameraDestinationAnimZ = XaeroField(GUI_MAP, "cameraDestinationAnimZ", CAMERA)
    @JvmField val shouldResetCameraPos = XaeroField(GUI_MAP, "shouldResetCameraPos", CAMERA)
    @JvmField val attachedCamera = XaeroField(GUI_MAP, "attachedCamera", CAMERA)
    @JvmField val destScale = XaeroField(GUI_MAP, "destScale", CAMERA)
    @JvmField val mouseBlockPosX = XaeroField(GUI_MAP, "mouseBlockPosX", HookErrors.OTHER_DIMENSION)
    @JvmField val mouseBlockPosZ = XaeroField(GUI_MAP, "mouseBlockPosZ", HookErrors.OTHER_DIMENSION)

    /** What moving the camera needs. */
    @JvmStatic
    fun camera(): Boolean = XaeroField.all(cameraX, cameraZ, cameraDestination, cameraDestinationAnimX, cameraDestinationAnimZ, shouldResetCameraPos, attachedCamera)

    /** The minimap's wrapper around what it draws on the world map. */
    @JvmField val wrappedRenderer = XaeroField(
        "xaero.map.mods.minimap.element.MinimapElementRendererWrapper", "renderer", "hiding minimap elements on another dimension's map",
    )
}
