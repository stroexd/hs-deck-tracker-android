package com.stroexd.hsdecktracker.core.vision

import kotlin.math.max
import kotlin.math.min

/** A pixel rectangle on the screen. */
data class ScreenBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

enum class OverlaySide { LEFT, RIGHT }

/**
 * Where the overlay fits next to Hearthstone's board. The board scales with the screen height and stays centered,
 * so wide phones keep empty curtains left and right. The overlay takes one of them, clear of camera cutouts, the
 * rounded display corners and the options button in the top right.
 */
object OverlayLayout {
    /** Board width per height, including where the opponent's played card appears (measured on a 20:9 phone). */
    const val BOARD_ASPECT = 1.64f

    /** Hearthstone's options button and battery indicator sit at the top of the right curtain. */
    private const val OPTIONS_BUTTON = 0.1f

    fun sidePanel(
        screenWidth: Int,
        screenHeight: Int,
        side: OverlaySide,
        minWidth: Int,
        maxWidth: Int,
        gap: Int,
        cornerRadius: Int = 0,
        cutouts: List<ScreenBox> = emptyList(),
    ): ScreenBox {
        val curtain = ((screenWidth - BOARD_ASPECT * screenHeight) / 2).toInt()
        var left = if (side == OverlaySide.LEFT) gap else screenWidth - curtain + gap
        var right = if (side == OverlaySide.LEFT) curtain - gap else screenWidth - gap
        var top = max(gap, cornerRadius)
        var bottom = screenHeight - max(gap, cornerRadius)
        if (side == OverlaySide.RIGHT) top = max(top, (screenHeight * OPTIONS_BUTTON).toInt())
        for (cutout in cutouts) {
            if (cutout.right <= left || cutout.left >= right) continue
            // A camera near a corner only costs height; one in the middle of the edge pushes the panel inwards
            when {
                cutout.bottom <= screenHeight * 0.35f -> top = max(top, cutout.bottom + gap)
                cutout.top >= screenHeight * 0.65f -> bottom = min(bottom, cutout.top - gap)
                side == OverlaySide.LEFT -> left = max(left, cutout.right + gap)
                else -> right = min(right, cutout.left - gap)
            }
        }
        val width = (right - left).coerceIn(minWidth, maxWidth)
        return if (side == OverlaySide.LEFT) {
            ScreenBox(left, top, left + width, bottom)
        } else {
            ScreenBox(right - width, top, right, bottom)
        }
    }

    fun nearestSide(centerX: Int, screenWidth: Int): OverlaySide =
        if (centerX < screenWidth / 2) OverlaySide.LEFT else OverlaySide.RIGHT
}
