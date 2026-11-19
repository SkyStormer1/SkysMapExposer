package com.skystormer.skysmapexposer

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Says why BlueMap's terrain is missing while the full-screen map shows caves: BlueMap only has the
 * surface, so nothing of it is drawn there. A line on the map while it does ([line]), and once a visit
 * a line in chat with a button that switches that map to the surface ([MapAdapter.showSurface]) and
 * one that stops the chat line for good ([Config.caveNotice]). The chat line waits until the map is
 * closed, as chat is hidden behind it.
 *
 * Not for the Nether: there BlueMap's map of the inside is drawn over the cave map, which is right.
 */
object CaveNotice {

    const val LINE = "No BlueMap terrain in cave mode: BlueMap only has the surface"

    /** How long [line] stays up after the map last showed caves; long enough to bridge a missed frame. */
    private const val LINE_MILLIS = 1_500L

    @Volatile private var seenAt = 0L
    @Volatile private var seenDimension: String? = null
    private var told = false

    /** The line for the top of the full-screen map, while it shows caves. */
    fun line(): String? = LINE.takeIf { System.currentTimeMillis() - seenAt < LINE_MILLIS }

    /**
     * Each tick: notes the full-screen map showing caves where BlueMap would draw the surface (in a
     * dimension without a roof), and once back in the game after it did, says so in chat, once a visit.
     */
    fun tick(client: Minecraft) {
        val session = Session.current
        val adapter = MapMods.adapter
        if (session != null && adapter != null && adapter.fullMapOpen(client.gui.screen())) {
            adapter.cavesShown(session)?.takeUnless { SeaFloor.hasCeiling(it) }?.let { dimension ->
                seenDimension = dimension
                seenAt = System.currentTimeMillis()
            }
        }
        if (told || client.gui.screen() != null) return
        val dimension = seenDimension ?: return
        val player = client.player ?: return
        told = true
        if (!Config.caveNotice) return
        val name = Ui.dimensionName(dimension) ?: dimension
        player.sendSystemMessage(
            Component.literal("§7[Map Exposer]§r BlueMap's terrain only shows on the surface map, and your full-screen map of the $name was in cave mode.  ")
                .append(ChatButton.of("[Show the surface]", "/mapexposer surface $dimension", "Switches the full-screen map's cave mode off for the $name, so BlueMap's terrain shows. Your map's own cave button turns it back on."))
                .append(Component.literal(" "))
                .append(ChatButton.of("[Don't tell me again]", "/mapexposer cavenotice off", "The line at the top of the map still says why while it shows caves."))
        )
    }

    /** On joining or leaving a world: a new visit, told again. */
    fun forget() {
        told = false
        seenDimension = null
        seenAt = 0L
    }
}
