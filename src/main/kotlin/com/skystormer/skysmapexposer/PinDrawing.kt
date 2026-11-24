package com.skystormer.skysmapexposer

//? if >=26.3 {
import com.mojang.renderpearl.api.textures.GpuTextureView
//?} else {
/*import com.mojang.blaze3d.textures.GpuTextureView
*///?}
import com.mojang.blaze3d.vertex.BufferBuilder
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.joml.Matrix4f

/** Quads for pin pictures, shared by the world map and minimap renderers. */
object PinDrawing {

    /** A picture inside a texture: [u1]..[u2], [v1]..[v2], drawn at up to [width]×[height] units. */
    class Icon(val view: GpuTextureView, val u1: Float, val v1: Float, val u2: Float, val v2: Float, val width: Int, val height: Int)

    /** A ping's beam colour at [brightness], 0 to 1: a beacon's pale blue. */
    fun beamColour(brightness: Float): Int = ((brightness * 255).toInt().coerceIn(0, 255) shl 24) or 0x7FE8FF

    /**
     * [ping]'s beam on a map: a column rising [height] units above [from], the top of its head,
     * flashing with it and paler towards the top. Each map's graphics has its own [fill].
     */
    inline fun beam(ping: Markers.Ping, from: Int, height: Int, fill: (Int, Int, Int, Int, Int) -> Unit) {
        val lit = PlayerPings.brightness(ping)
        val step = height / BEAM_STEPS
        for (i in 0 until BEAM_STEPS) {
            val bottom = -from - i * step
            fill(-BEAM_HALF, bottom - step, BEAM_HALF, bottom, beamColour(lit * (BEAM_STEPS * 2 - i) / (BEAM_STEPS * 2)))
        }
    }

    const val BEAM_STEPS = 8
    const val BEAM_HALF = 3

    /** Draws [icon] centred on the pose's origin, [size] units along its longer side. */
    fun quad(buffer: BufferBuilder, matrix: Matrix4f, icon: Icon, size: Float) {
        val longest = maxOf(icon.width, icon.height).coerceAtLeast(1)
        val halfW = size * icon.width / longest / 2f
        val halfH = size * icon.height / longest / 2f
        buffer.addVertex(matrix, -halfW, halfH, 0f).setColor(1f, 1f, 1f, 1f).setUv(icon.u1, icon.v2)
        buffer.addVertex(matrix, halfW, halfH, 0f).setColor(1f, 1f, 1f, 1f).setUv(icon.u2, icon.v2)
        buffer.addVertex(matrix, halfW, -halfH, 0f).setColor(1f, 1f, 1f, 1f).setUv(icon.u2, icon.v1)
        buffer.addVertex(matrix, -halfW, -halfH, 0f).setColor(1f, 1f, 1f, 1f).setUv(icon.u1, icon.v1)
    }

    /**
     * A player's face from their skin, as the tab list has it: the 8×8 face at (8, 8) of the 64×64
     * skin. Null for a player the client has no skin for.
     */
    fun face(uuid: java.util.UUID): Icon? {
        val minecraft = Minecraft.getInstance()
        val info = minecraft.connection?.getPlayerInfo(uuid) ?: return null
        val path = info.skin.body().texturePath()
        val view = minecraft.textureManager.getTexture(path).textureView
        return Icon(view, 8f / 64f, 8f / 64f, 16f / 64f, 16f / 64f, 8, 8)
    }

    /** Where a player's skin lives, or null while the client is still fetching it. */
    fun skin(uuid: java.util.UUID): Identifier? =
        Minecraft.getInstance().connection?.getPlayerInfo(uuid)?.skin?.body()?.texturePath()

    /**
     * A player's face, and the hat layer over it, drawn on a screen or the HUD at [size] pixels —
     * the same two 8×8 patches of the skin the tab list uses. Anyone whose skin has not arrived
     * gets a plain square, so a row or a marker never silently loses its picture.
     */
    fun face(graphics: GuiGraphicsExtractor, uuid: java.util.UUID, x: Int, y: Int, size: Int) {
        val skin = skin(uuid)
        if (skin == null) {
            graphics.fill(x, y, x + size, y + size, NO_SKIN)
            return
        }
        graphics.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 8f, 8f, size, size, 8, 8, 64, 64, -1)
        graphics.blit(RenderPipelines.GUI_TEXTURED, skin, x, y, 40f, 8f, size, size, 8, 8, 64, 64, -1)
    }

    private const val NO_SKIN = 0xFF7F7F7F.toInt()
}
