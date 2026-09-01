package com.niki914.zafiro.chat.agentic.shell

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator.canRequestUserConfirmation
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator.pendingConfirmation
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator.respond
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

/** 确认请求终态：允许 / 用户拒绝 / 无确认渠道（宿主路径）拒绝。 */
enum class ToolPermissionResponse { ALLOWED, DENIED_BY_USER, DENIED_UNAVAILABLE }

/**
 * CONFIRM 型执行规则的用户确认协调器。
 * - LLMController.stream 按来源设置 [canRequestUserConfirmation]：
 *   UI 直连 = true；宿主 Binder = false（默认拒绝，Agent 收到英文错误）。
 * - UI collect [pendingConfirmation] 渲染对话框，[respond] 解除挂起；
 *   永不超时（用户明确决策，PRD §3）。
 * - LLMController 单活跃回合，confirm/respond 无并发竞争。
 */
object ToolPermissionCoordinator {
    private const val LOG_TAG = "niki914_nexus_ToolPermission"

    @Volatile
    var canRequestUserConfirmation: Boolean = false

    private val pendingFlow = MutableStateFlow<ToolPermissionRequest?>(null)

    /** 当前待确认请求；null = 无挂起确认。 */
    val pendingConfirmation: StateFlow<ToolPermissionRequest?> = pendingFlow.asStateFlow()

    private var deferred: CompletableDeferred<ToolPermissionResponse>? = null

    suspend fun confirm(request: ToolPermissionRequest): ToolPermissionResponse {
        if (!canRequestUserConfirmation) {
            Logger.i(LOG_TAG, "confirm denied source=host id=${request.id}")
            return ToolPermissionResponse.DENIED_UNAVAILABLE
        }
        Logger.i(LOG_TAG, "confirm requested id=${request.id} tool=${request.toolName}")
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
