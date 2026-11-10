package com.niki914.zafiro.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.hypot

/** Pure hit-test for the close zone, so the "dropped on the X" rule is unit-tested. */
internal object DismissZone {
    fun isOver(ballCx: Float, ballCy: Float, targetCx: Float, targetCy: Float, radiusPx: Float): Boolean =
        hypot(ballCx - targetCx, ballCy - targetCy) <= radiusPx
}

/**
 * The circular "X" that appears at the bottom of the screen while the floating ball is being dragged. Dropping the ball on it
 * hides the ball (the owner turns it back on in Settings > Floating ball). Added/removed with the drag so it never lingers.
 */
internal class FloatingBallDismissTarget(
    private val context: Context,
    private val wm: WindowManager,
) {
    private val density = context.resources.displayMetrics.density
    private val sizePx = (64 * density).toInt()
    private val bottomMarginPx = (56 * density).toInt()
    private val hitRadiusPx = 56 * density
    private var view: TextView? = null
    private var highlighted = false

    private val centerX: Float get() = context.resources.displayMetrics.widthPixels / 2f
    private val centerY: Float get() = context.resources.displayMetrics.heightPixels - bottomMarginPx - sizePx / 2f

    fun show() {
        if (view != null) return
        val v = TextView(context).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC333333.toInt()) }
        }
        val lp = WindowManager.LayoutParams(
            sizePx, sizePx, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = bottomMarginPx
        }
        runCatching { wm.addView(v, lp); view = v }
    }

    /** @return true while the ball centre is inside the close zone (the target also grows and turns red). */
    fun update(ballCenterX: Float, ballCenterY: Float): Boolean {
        val over = DismissZone.isOver(ballCenterX, ballCenterY, centerX, centerY, hitRadiusPx)
        if (over != highlighted) {
            highlighted = over
            view?.apply {
                (background as? GradientDrawable)?.setColor(if (over) 0xEEE53935.toInt() else 0xCC333333.toInt())
                scaleX = if (over) 1.25f else 1f
                scaleY = scaleX
            }
        }
        return over
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        highlighted = false
    }
}
