package com.niki914.zafiro.app.overlay

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
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
import com.niki914.zafiro.remoteview.floatingball.FloatingBallDetailMorphCard
import com.niki914.zafiro.remoteview.floatingball.FloatingBallEffect
import com.niki914.zafiro.remoteview.floatingball.FloatingBallGeometry
import com.niki914.zafiro.remoteview.floatingball.FloatingBallIntent
import com.niki914.zafiro.remoteview.floatingball.FloatingBallMorphCard
import com.niki914.zafiro.remoteview.floatingball.FloatingBallState
import com.niki914.zafiro.remoteview.floatingball.FloatingBallTokens
import com.niki914.zafiro.remoteview.floatingball.FloatingBallUiState
import com.niki914.zafiro.remoteview.floatingball.FloatingBallViewModel
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 悬浮球 WindowManager 承载管理器（三窗口分离与 MVI 解耦架构）。
 *
 * 核心设计：
 * - **三窗口分离（Three-Window Architecture）**：
 *   - [Ball Window]：专职小球常态、手势拖动与贴边淹没。终生尺寸固定 (50x50dp)，永远不做 Window Resize；
 *   - [Card Window]：专职卡片展开与收缩动效。终生尺寸固定 (182x118dp)，永远不做 Window Resize；
 *   - [Detail Window]：专职授权详情展示与点击空白外部关闭。全屏透明无黑底蒙层，基于卡片物理坐标向屏幕中心平滑扩张/收回。
 * - **零系统级 Resize 缺陷**：
 *   彻底绕过 Android 底层 SurfaceFlinger 从大到小裁切与移动时产生的左上角撕裂和位移补间；
 * - **MVI 状态机驱动（FloatingBallViewModel）**：
 *   小球、卡片、授权详情三窗口的业务交互逻辑统一收拢于 ViewModel，Overlay 管理器只专职负责 WindowManager 宿主。
 */
object FloatingBallOverlayManager {

    private const val TAG = "FloatingBallOverlay"
    private const val INITIAL_Y_RATIO = 0.68f

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var ballRootView: FloatingBallTouchLayout? = null
    private var cardRootView: FloatingCardTouchLayout? = null
    private var detailRootView: View? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var viewModel: FloatingBallViewModel? = null

    // 悬浮球状态代理（读 MVI UiState）
    val ballState: FloatingBallState
        get() = viewModel?.uiStateFlow?.value?.ballState ?: FloatingBallState.Collapsed
    val dockSide: DockSide
        get() = viewModel?.uiStateFlow?.value?.dockSide ?: DockSide.Right
    val isSubmerged: Boolean
        get() = viewModel?.uiStateFlow?.value?.isSubmerged ?: true
    val yRatio: Float
        get() = viewModel?.uiStateFlow?.value?.yRatio ?: INITIAL_Y_RATIO

    // 授权状态
    val activeApprovalRequest: ApprovalRequest?
        get() = viewModel?.uiStateFlow?.value?.approvalRequest
    private var activeApprovalCont: CancellableContinuation<ApprovalDecision>? = null
    private var floatingBallApprover: FloatingBallApprover? = null

    val isShowing: Boolean
        get() = ballRootView != null

    private fun resolveApproval(decision: ApprovalDecision) {
        val cont = activeApprovalCont
        activeApprovalCont = null
        cont?.resume(decision)
    }

    private class FloatingBallApprover : Approver {
        override suspend fun decide(request: ApprovalRequest): ApprovalDecision {
            return suspendCancellableCoroutine { cont ->
                mainHandler.post {
                    activeApprovalCont = cont
                    viewModel?.sendIntent(FloatingBallIntent.UpdateApprovalRequest(request))
                }
                cont.invokeOnCancellation {
                    mainHandler.post {
                        if (viewModel?.uiStateFlow?.value?.approvalRequest == request) {
                            viewModel?.sendIntent(FloatingBallIntent.UpdateApprovalRequest(null))
                        }
                        if (activeApprovalCont === cont) {
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

            val vmInstance = FloatingBallViewModel()
            viewModel = vmInstance

            val approver = FloatingBallApprover()
            floatingBallApprover = approver
            val agentControl = runCatching { requireService<AgentControl>() }.getOrNull()
            agentControl?.addApprover(approver)

            lateinit var ballLayout: FloatingBallTouchLayout
            lateinit var cardLayout: FloatingCardTouchLayout

            owner.lifecycleScope.launch {
                vmInstance.uiEffect.collect { effect ->
                    when (effect) {
                        FloatingBallEffect.ExpandCard -> {
                            ballLayout.requestExpand()
                        }
                        is FloatingBallEffect.CollapseCard -> {
                            cardLayout.requestCollapse(effect.snapDock)
                        }
                        FloatingBallEffect.ShowDetail -> {
                            showDetailWindow(appContext, wm, owner)
                        }
                        FloatingBallEffect.DismissDetail -> {
                            removeDetailWindow()
                        }
                        is FloatingBallEffect.SettleApproval -> {
                            resolveApproval(effect.decision)
                        }
                        FloatingBallEffect.RequestStopAgent -> {
                            runCatching { requireService<AgentControl>() }.getOrNull()?.stop()
                        }
                        FloatingBallEffect.LaunchApp -> {
                            val intent = Intent(appContext, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            appContext.startActivity(intent)
                        }
                    }
                }
            }

            owner.lifecycleScope.launch {
                var lastOutcome: TurnOutcome? = null
                var lastPhase = AgentPhase.Idle
                agentControl?.status?.collect { status ->
                    val newOutcome = status.outcome
                    val outcomeArrived = newOutcome != null && newOutcome != lastOutcome && lastPhase != AgentPhase.Idle
                    lastOutcome = newOutcome
                    lastPhase = status.phase
                    vmInstance.sendIntent(
                        FloatingBallIntent.UpdateAgentStatus(
                            preview = status.preview,
                            isRunning = status.phase != AgentPhase.Idle,
                        )
                    )
                    if (outcomeArrived) {
                        vmInstance.sendIntent(FloatingBallIntent.RequestExpand)
                    }
                }
            }

            // --- A. 小球窗口挂载 ---
            ballLayout = FloatingBallTouchLayout(
                context = appContext,
                wm = wm,
                lp = ballLp,
                initialBallX = initialX,
                initialBallY = initialY,
                dockSideProvider = { dockSide },
                isSubmergedProvider = { isSubmerged },
                onRequestExpand = {
                    val currentDock = vmInstance.uiStateFlow.value.dockSide
                    cardLayout.openAt(ballLayout.ballX, ballLayout.ballY, currentDock)
                },
                onDockSideChanged = { newDock ->
                    vmInstance.sendIntent(FloatingBallIntent.UpdateDockSide(newDock))
                },
                onSnapFinished = { newDock, submerged ->
                    vmInstance.sendIntent(FloatingBallIntent.UpdateDockSide(newDock))
                    vmInstance.sendIntent(FloatingBallIntent.UpdateSubmerged(submerged))
                },
                onPositionUpdated = { newYRatio ->
                    vmInstance.sendIntent(FloatingBallIntent.UpdatePosition(newYRatio))
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
                                vmInstance.sendIntent(FloatingBallIntent.RequestExpand)
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
                    vmInstance.sendIntent(FloatingBallIntent.UpdateDockSide(newDock))
                },
                onCardCollapseStarting = { anchorX, anchorY ->
                    ballLayout.syncPosition(anchorX, anchorY)
                    ballLayout.alpha = 1f
                    ballLayout.visibility = View.VISIBLE
                },
                onCardCollapseCompleted = { finalBallX, finalBallY, snapDock ->
                    ballLayout.syncPosition(finalBallX, finalBallY)
                    ballLayout.visibility = View.VISIBLE
                    ballLayout.alpha = 1f
                    cardLayout.visibility = View.GONE
                    if (snapDock != null) {
                        vmInstance.sendIntent(FloatingBallIntent.UpdateDockSide(snapDock))
                        vmInstance.sendIntent(FloatingBallIntent.UpdateSubmerged(true))
                        ballLayout.snapToEdge(snapDock)
                    } else {
                        vmInstance.sendIntent(FloatingBallIntent.UpdateSubmerged(false))
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

                    val uiState by vmInstance.uiStateFlow.collectAsState()

                    BaseTheme(
                        darkTheme = isDark,
                        dynamicColor = themePrefs.seedColor == null,
                        seedColor = seed,
                    ) {
                        FloatingBallMorphCard(
                            state = uiState.ballState,
                            dockSide = uiState.dockSide,
                            preview = uiState.preview,
                            approvalRequest = uiState.approvalRequest,
                            isApprovalPending = uiState.isApprovalPending,
                            isStopEnabled = uiState.isStopEnabled,
                            onBallClick = {},
                            onMinimize = {
                                vmInstance.sendIntent(FloatingBallIntent.RequestCollapse())
                            },
                            onJumpToApp = {
                                vmInstance.sendIntent(FloatingBallIntent.JumpToApp)
                            },
                            onStop = {
                                vmInstance.sendIntent(FloatingBallIntent.StopAgent)
                            },
                            onAllow = {
                                vmInstance.sendIntent(FloatingBallIntent.AllowApproval)
                            },
                            onDeny = {
                                vmInstance.sendIntent(FloatingBallIntent.DenyApproval)
                            },
                            onOpenDetail = {
                                vmInstance.sendIntent(FloatingBallIntent.OpenDetail)
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
                // ballLayout 在下，cardLayout 在上
                wm.addView(ballLayout, ballLp)
                wm.addView(cardLayout, cardLp)
                Logger.i(TAG, "FloatingBall 2 windows added to WindowManager (ball, card)")
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

            removeDetailWindow()

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
            detailRootView = null
            viewModel = null
            windowManager = null
            lifecycleOwner = null
            Logger.i(TAG, "FloatingBall 3 windows dismissed")
        }
    }


    private fun showDetailWindow(appContext: Context, wm: WindowManager, owner: OverlayLifecycleOwner) {
        if (detailRootView != null) return
        val request = viewModel?.uiStateFlow?.value?.approvalRequest ?: return
        val cardLayout = cardRootView ?: return

        val density = appContext.resources.displayMetrics.density
        val cardXDp = (cardLayout.cardX / density).dp
        val cardYDp = (cardLayout.cardY / density).dp

        // 注意：Window 2（cardLayout）先保持 VISIBLE 垫底，绝不提前隐藏！
        // 等待 Window 3 首帧真实绘制完毕（onFirstFrameReady）后再交接隐藏，杜绝开窗闪烁

        val detailLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
        }

        val composeView = ComposeView(appContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
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
                    FloatingBallDetailMorphCard(
                        startCardX = cardXDp,
                        startCardY = cardYDp,
                        request = request,
                        onAllow = {
                            cardLayout.makeVisibleAndNotifyDrawn { removeDetailWindow() }
                            viewModel?.sendIntent(FloatingBallIntent.AllowApproval)
                        },
                        onDeny = {
                            cardLayout.makeVisibleAndNotifyDrawn { removeDetailWindow() }
                            viewModel?.sendIntent(FloatingBallIntent.DenyApproval)
                        },
                        onFirstFrameReady = {
                            // Window 3 首帧真实绘制完毕（且与卡片完全重叠），此时让底层卡片隐藏
                            cardRootView?.visibility = View.INVISIBLE
                        },
                        onCollapseFinished = {
                            cardLayout.makeVisibleAndNotifyDrawn { removeDetailWindow() }
                            viewModel?.sendIntent(FloatingBallIntent.CloseDetail)
                        },
                    )
                }
            }
        }

        detailRootView = composeView
        try {
            wm.addView(composeView, detailLp)
            Logger.i(TAG, "FloatingBall detail window added on-demand")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to add detail window", e)
            removeDetailWindow()
        }
    }

    private fun removeDetailWindow() {
        val view = detailRootView ?: return
        detailRootView = null
        try {
            windowManager?.removeViewImmediate(view)
            Logger.i(TAG, "FloatingBall detail window removed")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to remove detail window", e)
        }
    }
}
