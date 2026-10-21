package com.skystormer.skysmapexposer

import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Locked players' heads go under the rest of the HUD. Xaero's Minimap attaches itself after
 * [VanillaHudElements.MISC_OVERLAYS], and the hotbar, health and XP bars all come after it too, so
 * drawing the heads just before it keeps every one of them on top.
 */
class HudOrderTest {

    @Test
    fun `locked players' heads are drawn before the minimap, hotbar, health and XP bars`() {
        assertEquals(VanillaHudElements.MISC_OVERLAYS, PlayerBeacon.DRAWN_BEFORE)
    }
}
