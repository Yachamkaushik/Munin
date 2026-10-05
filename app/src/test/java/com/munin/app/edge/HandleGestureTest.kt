package com.munin.app.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandleGestureTest {
    private fun g() = HandleGesture(touchSlopPx = 8, screenHeightPx = 2000, handleHeightPx = 200)

    @Test fun aQuickTouchWithoutMovingIsATap() {
        val g = g(); g.onDown(500f, 800); assertEquals(800, g.onMove(504f)); assertTrue(g.onUp())
    }

    @Test fun movingPastTheSlopIsADragAndNeverATap() {
        val g = g(); g.onDown(500f, 800)
        assertEquals(900, g.onMove(600f))
        assertFalse(g.onUp())
    }

    @Test fun theHandleStaysOnScreen() {
        val g = g(); g.onDown(500f, 800)
        assertEquals(0, g.onMove(-5000f))
        assertEquals(1800, g.onMove(9000f))
        assertEquals(0, g.clamp(-1)); assertEquals(1800, g.clamp(5000))
    }

    @Test fun startsAboveTheMiddle() = assertEquals(720, HandleGesture.initialTop(2000, 200))
}
