package com.niki914.zafiro.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import com.niki914.zafiro.remoteview.floatingball.DockSide
import com.niki914.zafiro.remoteview.floatingball.FloatingBallGeometry
import com.niki914.zafiro.remoteview.floatingball.FloatingBallTokens
import kotlin.math.hypot

/**
 * 卡片窗口触摸承载（专职展开展示、拖动、收缩交接与首帧绘制监听）。
 */
@SuppressLint("ViewConstructor")
internal class FloatingCardTouchLayout(
    context: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    initialBallX: Int,
    initialBallY: Int,
    private val onCardDragged: (Int, Int, DockSide) -> Unit,
    private val onCardCollapseStarting: (Int, Int) -> Unit,
    private val onCardCollapseCompleted: (Int, Int, DockSide?) -> Unit,
) : FrameLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var isDragging = false

    private val density: Float get() = context.resources.displayMetrics.density
    private val ballWidthPx: Int get() = (FloatingBallTokens.buttonDiameter * density).toInt()
    private val cardWidthPx: Int get() = (FloatingBallTokens.expandedWidth * density).toInt()
    private val cardHeightPx: Int get() = (FloatingBallTokens.expandedHeight * density).toInt()
    private val rightAnchorXPx: Int get() = (FloatingBallTokens.rightAnchorX * density).toInt()
    private val leftAnchorXPx: Int get() = (FloatingBallTokens.leftAnchorX * density).toInt()
    private val anchorYPx: Int get() = (FloatingBallTokens.anchorY * density).toInt()
    private val snapThresholdPx: Float get() = FloatingBallTokens.snapThreshold * density
    private val submergedPx: Int get() = (FloatingBallTokens.submergedOffset * density).toInt()

    private val minCardY: Int get() = (32 * density).toInt()
    private val maxCardY: Int get() = (context.resources.displayMetrics.heightPixels - (48 + FloatingBallTokens.expandedHeight) * density).toInt()

    val cardX: Int get() = lp.x
    val cardY: Int get() = lp.y

    private var currentAnchorBallX = initialBallX
    private var currentAnchorBallY = initialBallY
    private var pendingSnapDock: DockSide? = null

    fun openAt(ballX: Int, ballY: Int, dock: DockSide) {
        currentAnchorBallX = ballX
        currentAnchorBallY = ballY

        val anchorXPx = if (dock.isRight) rightAnchorXPx else leftAnchorXPx
        lp.x = ballX - anchorXPx
        lp.y = ballY - anchorYPx

        if (isAttachedToWindow) {
            wm.updateViewLayout(this, lp)
        }
        alpha = 1f
        visibility = View.VISIBLE
    }

    /**
     * 将卡片恢复可见，并挂载绘制监听器，直到本窗口真正完成一帧绘制后才触发 [onDrawn]，
     * 确保上层覆盖窗口在此之前继续遮盖，杜绝交接期间 1 帧空白造成的闪烁。
     */
    fun makeVisibleAndNotifyDrawn(onDrawn: () -> Unit) {
        alpha = 1f
        visibility = View.VISIBLE
        invalidate()
        var handled = false
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (!handled) {
                    handled = true
                    post {
                        if (viewTreeObserver.isAlive) {
                            runCatching { viewTreeObserver.removeOnDrawListener(this) }
                        }
                        onDrawn()
                    }
                }
            }
        }
        viewTreeObserver.addOnDrawListener(listener)
        // 50ms 超时兜底，防止极端情况下未触发 draw 导致调用方挂起
        postDelayed({
            if (!handled) {
                handled = true
                if (viewTreeObserver.isAlive) {
                    runCatching { viewTreeObserver.removeOnDrawListener(listener) }
                }
                onDrawn()
            }
        }, 50)
    }

    fun requestCollapse(snapDock: DockSide? = null) {
        pendingSnapDock = snapDock
        onCardCollapseStarting(currentAnchorBallX, currentAnchorBallY)
    }

    fun notifyCollapseFinished() {
        val snapDock = pendingSnapDock
        pendingSnapDock = null
        onCardCollapseCompleted(currentAnchorBallX, currentAnchorBallY, snapDock)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                lastRawX = ev.rawX
                lastRawY = ev.rawY
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dist = hypot((ev.rawX - downRawX).toDouble(), (ev.rawY - downRawY).toDouble()).toFloat()
                if (dist > touchSlop) {
                    isDragging = true
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - lastRawX
                val dy = ev.rawY - lastRawY
                lastRawX = ev.rawX
                lastRawY = ev.rawY

                val screenWidth = context.resources.displayMetrics.widthPixels

                lp.x += dx.toInt()
                lp.y = (lp.y + dy.toInt()).coerceIn(minCardY, maxCardY)

                val newDock = FloatingBallGeometry.resolveDockSide(lp.x.toFloat(), cardWidthPx, screenWidth)

                val anchorXPx = if (newDock.isRight) rightAnchorXPx else leftAnchorXPx
                currentAnchorBallX = lp.x + anchorXPx
                currentAnchorBallY = lp.y + anchorYPx

                onCardDragged(currentAnchorBallX, currentAnchorBallY, newDock)

                if (isAttachedToWindow) {
                    wm.updateViewLayout(this, lp)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    handleSnap()
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun handleSnap() {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val distanceToLeft = lp.x.toFloat()
        val distanceToRight = (screenWidth - (lp.x + cardWidthPx)).toFloat()

        when {
            distanceToLeft < snapThresholdPx -> {
                // 拉到左边缘收起：先在原地收缩为小球，完成后平滑吸附到左侧并淹没
                onCardDragged(currentAnchorBallX, currentAnchorBallY, DockSide.Left)
                requestCollapse(snapDock = DockSide.Left)
            }
            distanceToRight < snapThresholdPx -> {
                // 拉到右边缘收起：先在原地收缩为小球，完成后平滑吸附到右侧并淹没
                onCardDragged(currentAnchorBallX, currentAnchorBallY, DockSide.Right)
                requestCollapse(snapDock = DockSide.Right)
            }
            else -> {
                // 自由悬停：保持展开
                val finalDock = FloatingBallGeometry.resolveDockSide(lp.x.toFloat(), cardWidthPx, screenWidth)
                onCardDragged(currentAnchorBallX, currentAnchorBallY, finalDock)
            }
        }
    }
}
