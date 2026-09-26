package com.stroexd.hsdecktracker.core

import com.stroexd.hsdecktracker.core.vision.OverlayLayout
import com.stroexd.hsdecktracker.core.vision.OverlaySide
import com.stroexd.hsdecktracker.core.vision.ScreenBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OverlayLayoutTest {
    // Measured on a 20:9 phone screenshot (2142×960): the right curtain starts at x≈1840 below the options button
    // (y≈80), the opponent's enlarged card reaches into the left one up to x≈330.
    private fun panel(side: OverlaySide, width: Int = 2142, height: Int = 960, cornerRadius: Int = 0, cutouts: List<ScreenBox> = emptyList()) =
        OverlayLayout.sidePanel(width, height, side, minWidth = 230, maxWidth = 480, gap = 8, cornerRadius = cornerRadius, cutouts = cutouts)

    @Test
    fun fitsIntoTheEmptyCurtains() {
        val right = panel(OverlaySide.RIGHT)
        assertTrue(right.left >= 1840 && right.right <= 2142, "$right")
        assertTrue(right.top >= 80, "below the options button: $right")
        val left = panel(OverlaySide.LEFT)
        assertTrue(left.left >= 0 && left.right < 330, "$left")
        assertEquals(952, left.bottom)
    }

    @Test
    fun roundedCornersCostHeight() {
        val box = panel(OverlaySide.LEFT, cornerRadius = 90)
        assertEquals(90, box.top)
        assertEquals(870, box.bottom)
    }

    @Test
    fun avoidsTheCamera() {
        val corner = ScreenBox(0, 20, 80, 110)
        assertEquals(118, panel(OverlaySide.LEFT, cutouts = listOf(corner)).top)
        val middle = ScreenBox(0, 430, 80, 530)
        val pushed = panel(OverlaySide.LEFT, cutouts = listOf(middle))
        assertEquals(88, pushed.left)
        assertTrue(pushed.width >= 230)
        // A camera on the other side changes nothing
        assertEquals(panel(OverlaySide.RIGHT), panel(OverlaySide.RIGHT, cutouts = listOf(middle)))
    }

    @Test
    fun narrowScreensKeepAUsableWidthAtTheEdge() {
        val box = panel(OverlaySide.RIGHT, width = 1920, height = 1080)
        assertEquals(230, box.width)
        assertEquals(1912, box.right)
    }

    @Test
    fun veryWideScreensCapTheWidthAtTheEdge() {
        val box = panel(OverlaySide.LEFT, width = 3000, height = 960)
        assertEquals(480, box.width)
        assertEquals(8, box.left)
    }

    @Test
    fun snapsToTheNearestSide() {
        assertEquals(OverlaySide.LEFT, OverlayLayout.nearestSide(300, 2142))
        assertEquals(OverlaySide.RIGHT, OverlayLayout.nearestSide(1500, 2142))
    }
}
