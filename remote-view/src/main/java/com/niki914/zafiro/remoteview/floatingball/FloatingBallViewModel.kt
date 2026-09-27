package com.niki914.zafiro.remoteview.floatingball

import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.xsettings.XSettings
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.service.requireService

/**
 * 悬浮球统一 MVI UI 状态。
 */
data class FloatingBallUiState(
    val ballState: FloatingBallState = FloatingBallState.Collapsed,
    val isDetailOpen: Boolean = false,
    val dockSide: DockSide = DockSide.Right,
    val isSubmerged: Boolean = true,
    val preview: String? = null,
    val approvalRequest: ApprovalRequest? = null,
    val isStopEnabled: Boolean = false,
    val yRatio: Float = 0.68f,
    /** 末轮结局；null = 尚无结局。「结局到达」据此判断（自动展开的触发条件之一）。 */
    val lastOutcome: TurnOutcome? = null,
) {
    val isApprovalPending: Boolean
        get() = approvalRequest != null
}

/**
 * 悬浮球统一用户与系统意图。
 */
sealed interface FloatingBallIntent {
    data object RequestExpand : FloatingBallIntent
    data object CommitExpand : FloatingBallIntent
    data class RequestCollapse(val snapDock: DockSide? = null) : FloatingBallIntent
    data object OpenDetail : FloatingBallIntent
    data object CloseDetail : FloatingBallIntent
    data object AllowApproval : FloatingBallIntent
    data object DenyApproval : FloatingBallIntent
    data object StopAgent : FloatingBallIntent
    data object JumpToApp : FloatingBallIntent
    data class UpdateDockSide(val dockSide: DockSide) : FloatingBallIntent
    data class UpdateSubmerged(val submerged: Boolean) : FloatingBallIntent
    data class UpdatePosition(val yRatio: Float) : FloatingBallIntent
    data class UpdateAgentStatus(
        val preview: String?,
        val isRunning: Boolean,
        val lastOutcome: TurnOutcome? = null,
    ) : FloatingBallIntent
    data class UpdateApprovalRequest(val request: ApprovalRequest?) : FloatingBallIntent
}

/**
 * 悬浮球单次副作用（驱动窗口动画、系统导航与审批结算）。
 */
sealed interface FloatingBallEffect {
    data object LaunchApp : FloatingBallEffect
    data object RequestStopAgent : FloatingBallEffect
    data class SettleApproval(val decision: ApprovalDecision) : FloatingBallEffect
    data object ExpandCard : FloatingBallEffect
    data class CollapseCard(val snapDock: DockSide?) : FloatingBallEffect
    data object ShowDetail : FloatingBallEffect
    data object DismissDetail : FloatingBallEffect
}

/**
 * 悬浮球及多窗口统一状态机 ViewModel（基于 [ComposeMVIViewModel]）。
 *
 * 核心设计：
 * - 统一管理小球（Ball）、卡片（Card）、授权详情（Detail）3 个 Window 的逻辑状态；
 * - 集中处理自动展开、审批决策结算、双向收缩等转场约束；
 * - 消除宿主 OverlayManager 对状态与业务逻辑的强耦合。
 */
class FloatingBallViewModel :
    ComposeMVIViewModel<FloatingBallIntent, FloatingBallUiState, FloatingBallEffect>() {

    private val settings: XSettings get() = requireService()

    override fun initUiState(): FloatingBallUiState = FloatingBallUiState()

    /** 展开请求的唯一出口（用户点击 / 自动展开共用），已展开时幂等。 */
    private fun requestExpand() {
        if (currentState.ballState.isExpanded) return
        sendEffect(FloatingBallEffect.ExpandCard)
    }

    override suspend fun handleIntent(intent: FloatingBallIntent) {
        when (intent) {
            FloatingBallIntent.RequestExpand -> requestExpand()

            FloatingBallIntent.CommitExpand -> {
                updateState { copy(ballState = FloatingBallState.Expanded, isSubmerged = false) }
            }

            is FloatingBallIntent.RequestCollapse -> {
                updateState { copy(ballState = FloatingBallState.Collapsed, isDetailOpen = false) }
                sendEffect(FloatingBallEffect.CollapseCard(intent.snapDock))
            }

            FloatingBallIntent.OpenDetail -> {
                if (currentState.approvalRequest != null) {
                    updateState { copy(isDetailOpen = true) }
                    sendEffect(FloatingBallEffect.ShowDetail)
                }
            }

            FloatingBallIntent.CloseDetail -> {
                updateState { copy(isDetailOpen = false) }
                sendEffect(FloatingBallEffect.DismissDetail)
            }

            FloatingBallIntent.AllowApproval -> {
                val wasDetailOpen = currentState.isDetailOpen
                updateState { copy(isDetailOpen = false, approvalRequest = null) }
                sendEffect(FloatingBallEffect.SettleApproval(ApprovalDecision.Allow))
                if (wasDetailOpen) {
                    sendEffect(FloatingBallEffect.DismissDetail)
                }
            }

            FloatingBallIntent.DenyApproval -> {
                val wasDetailOpen = currentState.isDetailOpen
                updateState { copy(isDetailOpen = false, approvalRequest = null) }
                sendEffect(FloatingBallEffect.SettleApproval(ApprovalDecision.Deny))
                if (wasDetailOpen) {
                    sendEffect(FloatingBallEffect.DismissDetail)
                }
            }

            FloatingBallIntent.StopAgent -> {
                sendEffect(FloatingBallEffect.RequestStopAgent)
            }

            FloatingBallIntent.JumpToApp -> {
                sendEffect(FloatingBallEffect.LaunchApp)
            }

            is FloatingBallIntent.UpdateDockSide -> {
                updateState { copy(dockSide = intent.dockSide) }
            }

            is FloatingBallIntent.UpdateSubmerged -> {
                updateState { copy(isSubmerged = intent.submerged) }
            }

            is FloatingBallIntent.UpdatePosition -> {
                updateState { copy(yRatio = intent.yRatio) }
            }

            is FloatingBallIntent.UpdateAgentStatus -> {
                val wasRunning = currentState.isStopEnabled
                val wasCollapsed = currentState.ballState.isCollapsed
                val outcomeArrived = intent.lastOutcome != null &&
                        intent.lastOutcome != currentState.lastOutcome &&
                        wasRunning
                updateState {
                    copy(
                        preview = intent.preview,
                        isStopEnabled = intent.isRunning,
                        lastOutcome = intent.lastOutcome,
                    )
                }
                // 末轮结局到达：自动展开由设置控制（否则小球只用颜色提示）
                if (outcomeArrived && wasCollapsed && settings.floatingBallAutoExpand.value) {
                    requestExpand()
                }
            }

            is FloatingBallIntent.UpdateApprovalRequest -> {
                val newRequest = intent.request
                val wasDetailOpen = currentState.isDetailOpen
                updateState {
                    copy(
                        approvalRequest = newRequest,
                        isDetailOpen = if (newRequest == null) false else isDetailOpen,
                    )
                }
                if (newRequest == null && wasDetailOpen) {
                    sendEffect(FloatingBallEffect.DismissDetail)
                }
                if (newRequest != null && settings.floatingBallAutoExpand.value) {
                    requestExpand()
                }
            }
        }
    }
}
