package com.skystormer.skysmapexposer

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which of BlueMap's colours count as water, and which water as swamp water. Swamp water is written
 * two blocks deep over mud, so taking ocean for it paints every sea flat and opaque — which a
 * download onto a blank map once did.
 */
class WaterRuleTest {

    @Test
    fun `dark blue ocean is water but not swamp water`() {
        // Open ocean as BlueMap draws it: dark, clearly blue, faintly green.
        assertTrue(XaeroPalette.isWater(0.42f, -0.01f, -0.08f, 62, hasSea = true))
        assertFalse(XaeroPalette.isMurky(0.42f, -0.01f, -0.08f, 62, hasSea = true))
    }

    @Test
    fun `dark olive water at sea level is swamp water`() {
        assertTrue(XaeroPalette.isWater(0.45f, -0.01f, 0.02f, 62, hasSea = true))
        assertTrue(XaeroPalette.isMurky(0.45f, -0.01f, 0.02f, 62, hasSea = true))
    }

    @Test
    fun `the same colour above sea level, or without a sea, is not swamp water`() {
        assertFalse(XaeroPalette.isMurky(0.45f, -0.01f, 0.02f, 70, hasSea = true))
        assertFalse(XaeroPalette.isMurky(0.45f, -0.01f, 0.02f, 62, hasSea = false))
    }
}
