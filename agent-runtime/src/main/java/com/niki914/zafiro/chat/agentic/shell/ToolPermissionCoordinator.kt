package com.niki914.zafiro.chat.agentic.shell

import com.niki914.logging.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一次工具执行确认请求（UI 展示字段）。 */
data class ToolPermissionRequest(
    val id: String,
    val toolName: String,
    val command: String,
    val matchedRuleName: String,
)

/** 确认请求终态：允许 / 用户拒绝 / 无确认渠道拒绝。 */
enum class ToolPermissionResponse { ALLOWED, DENIED_BY_USER, DENIED_UNAVAILABLE }

/**
 * CONFIRM 型执行规则的用户确认协调器。
 *
 * 路由策略（按优先级）：
 * - 应用前台 → Compose 对话框（[pendingConfirmation] StateFlow → HomePageContent 渲染）
 * - 应用后台 + [backgroundConfirmationHandler] 已设置 → 后台弹窗（overlay）
 * - 否则 → DENIED_UNAVAILABLE
 *
 * 永不超时（用户明确决策，PRD §3）。
 */
object ToolPermissionCoordinator {
    private const val LOG_TAG = "niki914_nexus_ToolPermission"

    /** 应用 UI 是否可见（MainActivity 前台）。由 App 端 onResume/onPause 写入。 */
    @Volatile
    var isUiResumed: Boolean = false

    /** 后台确认处理器（overlay 弹窗）。null = 无后台确认能力，静默拒绝。 */
    var backgroundConfirmationHandler: (suspend (ToolPermissionRequest) -> ToolPermissionResponse)? = null

    private val pendingFlow = MutableStateFlow<ToolPermissionRequest?>(null)

    /** 当前待确认请求；null = 无挂起确认。 */
    val pendingConfirmation: StateFlow<ToolPermissionRequest?> = pendingFlow.asStateFlow()

    private var deferred: CompletableDeferred<ToolPermissionResponse>? = null

    suspend fun confirm(request: ToolPermissionRequest): ToolPermissionResponse {
        Logger.i(
            LOG_TAG,
            "confirm id=${request.id} tool=${request.toolName} uiResumed=$isUiResumed handler=${backgroundConfirmationHandler != null}",
        )
        if (isUiResumed) {
            return showInAppDialog(request)
        }
        val handler = backgroundConfirmationHandler
        if (handler != null) {
            return handler(request)
        }
        Logger.i(LOG_TAG, "confirm denied unavailable id=${request.id}")
        return ToolPermissionResponse.DENIED_UNAVAILABLE
    }

    private suspend fun showInAppDialog(request: ToolPermissionRequest): ToolPermissionResponse {
        pendingFlow.value = request
        val waiter = CompletableDeferred<ToolPermissionResponse>()
        deferred = waiter
        try {
            return waiter.await()
        } finally {
            if (deferred === waiter) {
                deferred = null
                pendingFlow.value = null
            }
        }
    }

    /** UI 决策入口；requestId 与当前挂起请求不一致时忽略。 */
    fun respond(requestId: String, allowed: Boolean) {
        if (pendingFlow.value?.id != requestId) return
        deferred?.complete(
            if (allowed) ToolPermissionResponse.ALLOWED else ToolPermissionResponse.DENIED_BY_USER
        )
    }
}
