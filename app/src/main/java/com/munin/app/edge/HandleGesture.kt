package com.munin.app.edge

import kotlin.math.abs

/**
 * Tells a tap from a drag on the edge handle, and keeps the handle on screen while it is dragged vertically. Pure, so it is unit tested.
 * Feed it the touch's raw Y; it answers with where the handle should be, and on release whether it was a tap.
 */
class HandleGesture(private val touchSlopPx: Int, private val screenHeightPx: Int, private val handleHeightPx: Int) {
    private var downY = 0f
    private var startTop = 0
    private var moved = false

    fun onDown(rawY: Float, currentTop: Int) { downY = rawY; startTop = currentTop; moved = false }

    /** The handle's new top edge. */
    fun onMove(rawY: Float): Int {
        if (abs(rawY - downY) > touchSlopPx) moved = true
        return if (moved) clamp(startTop + (rawY - downY).toInt()) else startTop
    }

    /** True when the finger went down and up without dragging: the user meant to open search. */
    fun onUp(): Boolean = !moved

    fun clamp(top: Int): Int = top.coerceIn(0, (screenHeightPx - handleHeightPx).coerceAtLeast(0))

    companion object {
        /** Where the handle starts: a little above the vertical middle, where a thumb rests. */
        fun initialTop(screenHeightPx: Int, handleHeightPx: Int): Int = ((screenHeightPx - handleHeightPx) * 0.4f).toInt().coerceAtLeast(0)
    }
}
