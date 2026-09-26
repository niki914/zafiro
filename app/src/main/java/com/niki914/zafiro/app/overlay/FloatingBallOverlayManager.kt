package com.niki914.zafiro.app.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.animation.doOnEnd
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.niki914.logging.Logger
import com.niki914.uikit.base.BaseTheme
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.model.AgentPhase
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.app.MainActivity
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.remoteview.floatingball.DockSide
import com.niki914.zafiro.remoteview.floatingball.FloatingBallCollapsedBall
import com.niki914.zafiro.remoteview.floatingball.FloatingBallGeometry
import com.niki914.zafiro.remoteview.floatingball.FloatingBallMorphCard
import com.niki914.zafiro.remoteview.floatingball.FloatingBallState
import com.niki914.zafiro.remoteview.floatingball.FloatingBallTokens
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.hypot

/**
 * 悬浮球 WindowManager 承载管理器（双窗口分离架构）。
 *
 * 核心设计：
 * - **双窗口分离（Two-Window Architecture）**：
 *   - [Ball Window]：专职小球常态、手势拖动与贴边淹没。终生尺寸固定，永远不做 Window Resize；
 *   - [Card Window]：专职卡片展开与收缩动效。终生尺寸固定，永远不做 Window Resize；
 * - **零系统级 Resize 缺陷**：
 *   彻底绕过 Android 底层 SurfaceFlinger 从大到小裁切与移动时产生的左上角撕裂和位移补间；
 * - **物理像素严格重合接力**：
 *   展开前和收缩后，在动画交界的那一帧，两窗口在 (ballX, ballY) 处 100% 严丝合缝重合接力，视觉上完全无缝。
 *
 *   TODO: 重构。目前来看，将来至少出现三个 Window。所以肯定是需要解耦的。然后目前这个选择框其实是 compose，所以可以用上 MVI 架构，而不是全部都聚集在这个 overlay manager 里面。
 *   TODO：approver review 以后改成翻页动画并且提供进一步展开的 UI，可以参考已经删除的 Tool permission overlay
 */
object FloatingBallOverlayManager {

    private const val TAG = "FloatingBallOverlay"
    private const val INITIAL_Y_RATIO = 0.68f

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var ballRootView: FloatingBallTouchLayout? = null
    private var cardRootView: FloatingCardTouchLayout? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null

    // 悬浮球状态机
    var ballState by mutableStateOf(FloatingBallState.Collapsed)
        private set
    var dockSide by mutableStateOf(DockSide.Right)
        private set
    var isSubmerged by mutableStateOf(true)
        private set
    var yRatio by mutableFloatStateOf(INITIAL_Y_RATIO)
        private set

    // 授权状态
    var activeApprovalRequest by mutableStateOf<ApprovalRequest?>(null)
        private set
    private var activeApprovalCont: CancellableContinuation<ApprovalDecision>? = null
    private var floatingBallApprover: FloatingBallApprover? = null

    val isShowing: Boolean
        get() = ballRootView != null

    private fun autoExpandIfCollapsed() {
        mainHandler.post {
            if (ballState.isCollapsed) {
                ballRootView?.requestExpand()
            }
        }
    }

    private fun resolveApproval(decision: ApprovalDecision) {
        val cont = activeApprovalCont
        activeApprovalCont = null
        activeApprovalRequest = null
        cont?.resume(decision)
    }

    private class FloatingBallApprover : Approver {
        override suspend fun decide(request: ApprovalRequest): ApprovalDecision {
            return suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    activeApprovalRequest = request
                    activeApprovalCont = cont
                    autoExpandIfCollapsed()
                }
                cont.invokeOnCancellation {
                    mainHandler.post {
                        if (activeApprovalRequest == request) {
                            activeApprovalRequest = null
                            activeApprovalCont = null
                        }
                    }
                }
            }
        }
    }

    fun show(context: Context) {
        mainHandler.post {
            if (ballRootView != null) return@post

            val appContext = context.applicationContext
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            val density = appContext.resources.displayMetrics.density
            val screenWidth = appContext.resources.displayMetrics.widthPixels
            val screenHeight = appContext.resources.displayMetrics.heightPixels

            val ballWidthPx = (FloatingBallTokens.buttonDiameter * density).toInt()
            val ballHeightPx = (FloatingBallTokens.buttonDiameter * density).toInt()
            val cardWidthPx = (FloatingBallTokens.expandedWidth * density).toInt()
            val cardHeightPx = (FloatingBallTokens.expandedHeight * density).toInt()

            val submergedPx = (FloatingBallTokens.submergedOffset * density).toInt()
            val initialX = if (dockSide.isLeft) {
                -submergedPx
            } else {
                screenWidth - (ballWidthPx - submergedPx)
            }
            val initialY = (screenHeight * INITIAL_Y_RATIO).toInt()
            val initialAnchorXPx = if (dockSide.isRight) {
                (FloatingBallTokens.rightAnchorX * density).toInt()
            } else {
                (FloatingBallTokens.leftAnchorX * density).toInt()
            }

            // 1. 小球窗口 LayoutParams (尺寸由规范锁定)
            val ballLp = WindowManager.LayoutParams(
                ballWidthPx,
                ballHeightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX
                y = initialY
                windowAnimations = 0
            }

            // 2. 卡片窗口 LayoutParams (尺寸由规范锁定)
            val cardLp = WindowManager.LayoutParams(
                cardWidthPx,
                cardHeightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = initialX - initialAnchorXPx
                y = initialY - ((FloatingBallTokens.anchorY) * density).toInt()
                windowAnimations = 0
            }

            val owner = OverlayLifecycleOwner().apply {
                onCreate()
                onStart()
                onResume()
            }
            lifecycleOwner = owner

            val approver = FloatingBallApprover()
            floatingBallApprover = approver
            val agentControl = runCatching { requireService<AgentControl>() }.getOrNull()
            agentControl?.addApprover(approver)

            owner.lifecycleScope.launch {
                var lastOutcome: TurnOutcome? = null
                var lastPhase = AgentPhase.Idle
                agentControl?.status?.collect { status ->
                    val newOutcome = status.outcome
                    val outcomeArrived = newOutcome != null && newOutcome != lastOutcome && lastPhase != AgentPhase.Idle
                    lastOutcome = newOutcome
                    lastPhase = status.phase
                    if (outcomeArrived) {
                        autoExpandIfCollapsed()
                    }
                }
            }

            lateinit var ballLayout: FloatingBallTouchLayout
            lateinit var cardLayout: FloatingCardTouchLayout

            // --- A. 小球窗口挂载 ---
            ballLayout = FloatingBallTouchLayout(
                context = appContext,
                wm = wm,
                lp = ballLp,
                initialBallX = initialX,
                initialBallY = initialY,
                onRequestExpand = {
                    cardLayout.openAt(ballLayout.ballX, ballLayout.ballY, dockSide)
                },
                onDockSideChanged = { newDock ->
                    dockSide = newDock
                },
                onSnapFinished = { newDock, submerged ->
                    dockSide = newDock
                    isSubmerged = submerged
                },
                onPositionUpdated = { newYRatio ->
                    yRatio = newYRatio
                },
            ).apply {
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
            }

            val ballComposeView = ComposeView(appContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val themePrefs = ThemeController.prefs
                    val isSystemDark = isSystemInDarkTheme()
                    val isDark = themePrefs.resolveDarkTheme(isSystemDark)
                    val seed = themePrefs.seedColor?.let { Color(it) }

                    BaseTheme(
                        darkTheme = isDark,
                        dynamicColor = themePrefs.seedColor == null,
                        seedColor = seed,
                    ) {
                        FloatingBallCollapsedBall(
                            onClick = {
                                ballLayout.requestExpand()
                            },
                        )
                    }
                }
            }
            ballLayout.addView(ballComposeView)
            ballRootView = ballLayout

            // --- B. 卡片窗口挂载 ---
            cardLayout = FloatingCardTouchLayout(
                context = appContext,
                wm = wm,
                lp = cardLp,
                initialBallX = initialX,
                initialBallY = initialY,
                onCardDragged = { newBallX, newBallY, newDock ->
                    ballLayout.syncPosition(newBallX, newBallY)
                    dockSide = newDock
                },
                onCardCollapseStarting = { anchorX, anchorY ->
                    ballLayout.syncPosition(anchorX, anchorY)
                    ballLayout.alpha = 1f
                    ballLayout.visibility = View.VISIBLE
                },
                onCardCollapseCompleted = { finalBallX, finalBallY, snapDock ->
                    ballState = FloatingBallState.Collapsed
                    ballLayout.syncPosition(finalBallX, finalBallY)
                    ballLayout.visibility = View.VISIBLE
                    ballLayout.alpha = 1f
                    cardLayout.visibility = View.GONE
                    if (snapDock != null) {
                        dockSide = snapDock
                        isSubmerged = true
                        ballLayout.snapToEdge(snapDock)
                    } else {
                        isSubmerged = false
                    }
                },
            ).apply {
                visibility = View.GONE // 初始隐藏
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)
            }

            val cardComposeView = ComposeView(appContext).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    val themePrefs = ThemeController.prefs
                    val isSystemDark = isSystemInDarkTheme()
                    val isDark = themePrefs.resolveDarkTheme(isSystemDark)
                    val seed = themePrefs.seedColor?.let { Color(it) }

                    val currentAgentControl = requireService<AgentControl>()
                    val agentStatus by currentAgentControl.status.collectAsState()
                    val currentApproval = activeApprovalRequest

                    val previewText = if (currentApproval != null) {
                        "⚠️ 待授权 · ${currentApproval.toolName}: ${currentApproval.command}"
                    } else {
                        agentStatus.preview
                    }

                    BaseTheme(
                        darkTheme = isDark,
                        dynamicColor = themePrefs.seedColor == null,
                        seedColor = seed,
                    ) {
                        FloatingBallMorphCard(
                            state = ballState,
                            dockSide = dockSide,
                            preview = previewText,
                            isApprovalPending = currentApproval != null,
                            isStopEnabled = agentStatus.phase != AgentPhase.Idle,
                            onBallClick = {},
                            onMinimize = {
                                cardLayout.requestCollapse()
                            },
                            onJumpToApp = {
                                val intent = Intent(appContext, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                }
                                appContext.startActivity(intent)
                            },
                            onStop = {
                                Logger.i(TAG, "FloatingBall: Stop clicked")
                                currentAgentControl.stop()
                            },
                            onAllow = {
                                Logger.i(TAG, "FloatingBall: Allow clicked")
                                resolveApproval(ApprovalDecision.Allow)
                            },
                            onDeny = {
                                Logger.i(TAG, "FloatingBall: Deny clicked")
                                resolveApproval(ApprovalDecision.Deny)
                            },
                            onCollapseFinished = {
                                cardLayout.notifyCollapseFinished()
                            },
                            onBallAlphaChanged = { alpha ->
                                ballLayout.alpha = alpha
                                if (alpha <= 0f) {
                                    ballLayout.visibility = View.INVISIBLE
                                    ballLayout.alpha = 1f
                                } else {
                                    ballLayout.visibility = View.VISIBLE
                                }
                            },
                        )
                    }
                }
            }
            cardLayout.addView(cardComposeView)
            cardRootView = cardLayout

            try {
                // ballLayout 在下，cardLayout 在上（展开时覆于小球之上）
                wm.addView(ballLayout, ballLp)
                wm.addView(cardLayout, cardLp)
                Logger.i(TAG, "FloatingBall dual windows added to WindowManager (ball below, card above)")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to add FloatingBall windows", e)
                dismiss()
            }
        }
    }

    fun dismiss() {
        mainHandler.post {
            floatingBallApprover?.let { approver ->
                runCatching { requireService<AgentControl>() }.getOrNull()?.removeApprover(approver)
            }
            floatingBallApprover = null
            resolveApproval(ApprovalDecision.Abstain)

            val wm = windowManager

            ballRootView?.let {
                try {
                    wm?.removeViewImmediate(it)
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to remove ball window", e)
                }
            }

            cardRootView?.let {
                try {
                    wm?.removeViewImmediate(it)
                } catch (e: Exception) {
                    Logger.w(TAG, "Failed to remove card window", e)
                }
            }

            lifecycleOwner?.let {
                it.onPause()
                it.onStop()
                it.onDestroy()
            }

            ballRootView = null
            cardRootView = null
            windowManager = null
            lifecycleOwner = null
            Logger.i(TAG, "FloatingBall dual windows dismissed")
        }
    }

    /**
     * 小球窗口触摸承载（专职小球拖动、贴边吸附与淹没）。
     */
    @SuppressLint("ViewConstructor")
    private class FloatingBallTouchLayout(
        context: Context,
        private val wm: WindowManager,
        private val lp: WindowManager.LayoutParams,
        initialBallX: Int,
        initialBallY: Int,
        private val onRequestExpand: () -> Unit,
        private val onDockSideChanged: (DockSide) -> Unit,
        private val onSnapFinished: (DockSide, Boolean) -> Unit,
        private val onPositionUpdated: (Float) -> Unit,
    ) : FrameLayout(context) {

        var ballX: Int = initialBallX
            private set
        var ballY: Int = initialBallY
            private set

        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var isDragging = false
        private var positionAnimator: ValueAnimator? = null

        private val density: Float get() = context.resources.displayMetrics.density
        private val ballWidthPx: Int get() = (FloatingBallTokens.buttonDiameter * density).toInt()
        private val ballHeightPx: Int get() = (FloatingBallTokens.buttonDiameter * density).toInt()
        private val submergedPx: Int get() = (FloatingBallTokens.submergedOffset * density).toInt()
        private val snapThresholdPx: Float get() = FloatingBallTokens.snapThreshold * density
        private val escapeDistancePx: Int get() = (FloatingBallTokens.escapeSnapDistance * density).toInt()

        private val minBallY: Int get() = ((32 + FloatingBallTokens.anchorY) * density).toInt()
        private val maxBallY: Int get() = (context.resources.displayMetrics.heightPixels - (48 + FloatingBallTokens.expandedHeight - FloatingBallTokens.anchorY) * density).toInt()

        fun syncPosition(newX: Int, newY: Int) {
            ballX = newX
            ballY = newY.coerceIn(minBallY, maxBallY)
            lp.x = ballX
            lp.y = ballY
            val screenWidth = context.resources.displayMetrics.widthPixels
            val newDock = FloatingBallGeometry.resolveDockSide(ballX.toFloat(), ballWidthPx, screenWidth)
            if (newDock != dockSide) {
                onDockSideChanged(newDock)
            }
            if (isAttachedToWindow) {
                wm.updateViewLayout(this, lp)
            }
        }

        fun snapToEdge(dock: DockSide) {
            val screenWidth = context.resources.displayMetrics.widthPixels
            val targetX = if (dock.isLeft) -submergedPx else screenWidth - (ballWidthPx - submergedPx)
            onDockSideChanged(dock)
            animateBallTo(targetX, ballY) {
                onSnapFinished(dock, true)
            }
        }

        fun applySubmerged(dock: DockSide) {
            val screenWidth = context.resources.displayMetrics.widthPixels
            ballX = if (dock.isLeft) -submergedPx else screenWidth - (ballWidthPx - submergedPx)
            lp.x = ballX
            if (isAttachedToWindow) {
                wm.updateViewLayout(this, lp)
            }
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    positionAnimator?.cancel()
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

                    ballX += dx.toInt()
                    ballY = (ballY + dy.toInt()).coerceIn(minBallY, maxBallY)
                    lp.x = ballX
                    lp.y = ballY

                    val newDock = FloatingBallGeometry.resolveDockSide(ballX.toFloat(), ballWidthPx, screenWidth)
                    if (newDock != dockSide) {
                        onDockSideChanged(newDock)
                    }

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
            val screenHeight = context.resources.displayMetrics.heightPixels

            val currentYRatio = ballY.toFloat() / screenHeight.coerceAtLeast(1)
            onPositionUpdated(currentYRatio)

            val distanceToLeft = ballX.toFloat()
            val distanceToRight = (screenWidth - (ballX + ballWidthPx)).toFloat()

            when {
                distanceToLeft < snapThresholdPx -> {
                    val targetX = -submergedPx
                    onDockSideChanged(DockSide.Left)
                    animateBallTo(targetX, ballY) {
                        onSnapFinished(DockSide.Left, true)
                    }
                }
                distanceToRight < snapThresholdPx -> {
                    val targetX = screenWidth - (ballWidthPx - submergedPx)
                    onDockSideChanged(DockSide.Right)
                    animateBallTo(targetX, ballY) {
                        onSnapFinished(DockSide.Right, true)
                    }
                }
                else -> {
                    val finalDock = if (distanceToLeft < distanceToRight) DockSide.Left else DockSide.Right
                    onDockSideChanged(finalDock)
                    onSnapFinished(finalDock, false)
                }
            }
        }

        fun requestExpand() {
            val screenWidth = context.resources.displayMetrics.widthPixels
            val distanceToLeft = ballX
            val distanceToRight = screenWidth - (ballX + ballWidthPx)

            if (isSubmerged || distanceToLeft < escapeDistancePx || distanceToRight < escapeDistancePx) {
                val escapeX = FloatingBallGeometry.calculateStableDockX(
                    dockSide = dockSide,
                    screenWidthPx = screenWidth,
                    ballWidthPx = ballWidthPx,
                    escapeDistancePx = escapeDistancePx,
                )

                animateBallTo(escapeX, ballY) {
                    isSubmerged = false
                    onRequestExpand()
                }
            } else {
                onRequestExpand()
            }
        }

        private fun animateBallTo(targetX: Int, targetY: Int, onEnd: () -> Unit = {}) {
            positionAnimator?.cancel()
            val startX = ballX
            val startY = ballY

            if (startX == targetX && startY == targetY) {
                onEnd()
                return
            }

            positionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val frac = it.animatedValue as Float
                    ballX = (startX + (targetX - startX) * frac).toInt()
                    ballY = (startY + (targetY - startY) * frac).toInt()
                    lp.x = ballX
                    lp.y = ballY
                    if (isAttachedToWindow) {
                        wm.updateViewLayout(this@FloatingBallTouchLayout, lp)
                    }
                }
                doOnEnd {
                    onEnd()
                }
                start()
            }
        }
    }

    /**
     * 卡片窗口触摸承载（专职展开展示、拖动与收缩交接）。
     */
    @SuppressLint("ViewConstructor")
    private class FloatingCardTouchLayout(
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
            ballState = FloatingBallState.Expanded
        }

        fun requestCollapse(snapDock: DockSide? = null) {
            pendingSnapDock = snapDock
            onCardCollapseStarting(currentAnchorBallX, currentAnchorBallY)
            ballState = FloatingBallState.Collapsed
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
                    dockSide = DockSide.Left
                    requestCollapse(snapDock = DockSide.Left)
                }
                distanceToRight < snapThresholdPx -> {
                    // 拉到右边缘收起：先在原地收缩为小球，完成后平滑吸附到右侧并淹没
                    dockSide = DockSide.Right
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

    private class OverlayLifecycleOwner :
        LifecycleOwner,
        SavedStateRegistryOwner,
        ViewModelStoreOwner {

        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store

        fun onCreate() {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun onStart() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        fun onResume() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onPause() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }

        fun onStop() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }
}
