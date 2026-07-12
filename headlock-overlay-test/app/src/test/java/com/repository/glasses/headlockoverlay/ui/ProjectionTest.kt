package com.repository.glasses.headlockoverlay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionTest {
    private val d = 1e-2f

    @Test
    fun pxPerDegFromWidth() {
        val p = Projection(horizontalFovDeg = 28f)
        assertEquals(640f / 28f, p.pxPerDeg(640), d)
    }

    @Test
    fun centerPanelIsCenterScreen() {
        val p = Projection(horizontalFovDeg = 28f)
        assertEquals(320f, p.screenX(panelYawDeg = 0f, offsetYawDeg = 0f, viewWidthPx = 640), d)
    }

    @Test
    fun panelAtHalfFovIsRightEdge() {
        val p = Projection(horizontalFovDeg = 28f)
        assertEquals(640f, p.screenX(panelYawDeg = 14f, offsetYawDeg = 0f, viewWidthPx = 640), d)
    }

    @Test
    fun panelAtNegativeHalfFovIsLeftEdge() {
        val p = Projection(horizontalFovDeg = 28f)
        assertEquals(0f, p.screenX(panelYawDeg = -14f, offsetYawDeg = 0f, viewWidthPx = 640), d)
    }

    @Test
    fun offsetShiftsOppositeToHead() {
        val p = Projection(horizontalFovDeg = 28f)
        val expected = 320f - 10f * (640f / 28f)
        assertEquals(expected, p.screenX(panelYawDeg = 0f, offsetYawDeg = -10f, viewWidthPx = 640), d)
    }

    @Test
    fun screenYTopMiddleBottom() {
        val p = Projection(horizontalFovDeg = 28f)
        assertEquals(0f, p.screenY(panelPitchDeg = 14f, offsetPitchDeg = 0f, viewWidthPx = 640, viewHeightPx = 640), d)
        assertEquals(640f, p.screenY(panelPitchDeg = -14f, offsetPitchDeg = 0f, viewWidthPx = 640, viewHeightPx = 640), d)
        assertEquals(320f, p.screenY(panelPitchDeg = 0f, offsetPitchDeg = 0f, viewWidthPx = 640, viewHeightPx = 640), d)
    }

    @Test
    fun mockPanelsSanity() {
        assertEquals(11, MockPanels.PANELS.size)
        val centers = MockPanels.PANELS.filter { it.role == PanelRole.CENTER }
        assertEquals(1, centers.size)
        val c = centers.single()
        assertEquals(0f, c.yawDeg, d)
        assertEquals(0f, c.pitchDeg, d)
        for (panel in MockPanels.PANELS) {
            assertTrue("title must be non-blank", panel.title.isNotBlank())
            assertTrue("lines must be non-empty", panel.lines.isNotEmpty())
        }
    }
}
