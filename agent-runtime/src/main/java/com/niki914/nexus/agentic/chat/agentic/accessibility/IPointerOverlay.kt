package com.niki914.nexus.agentic.chat.agentic.accessibility

import com.niki914.nexus.agentic.animation.PointerCurveMath.MovementMode

interface IPointerOverlay {
    /** Fade in at [x],[y] with default idle heading. Non-blocking. */
    fun show(x: Float, y: Float)

    /** Fly from current position to [x],[y] using [mode]. Suspends until animation completes. */
    suspend fun animateTo(x: Float, y: Float, mode: MovementMode = MovementMode.FLY)

    /** Fly to swipe start, then trace the swipe path. Suspends until both phases complete. */
    suspend fun showSwipe(sx: Float, sy: Float, ex: Float, ey: Float, duration: Long)

    /** Fade out and remove from window. Non-blocking. */
    fun hide()

    /** Cancel all animations and remove the view from the window immediately. */
    fun dispose()
}
