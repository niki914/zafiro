package com.niki914.zafiro.chat.agentic.shell

import com.niki914.logging.Logger
import com.niki914.permission.PermissionObservation
import com.niki914.zafiro.chat.runtime.NoOp
import com.niki914.zafiro.chat.runtime.PermissionKind
import com.niki914.zafiro.chat.runtime.PermissionPhase
import com.niki914.zafiro.chat.runtime.PermissionRequestObservation
import com.niki914.zafiro.chat.runtime.Reason
import com.niki914.zafiro.chat.runtime.RuntimeFact
import com.niki914.zafiro.chat.runtime.RuntimeFactSink
import com.niki914.zafiro.chat.runtime.TurnKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

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
 * 单次执行的授权观察上下文（T-10 适配接缝，Group5 消费）。
 *
 * 由 executor 在创建执行时捕获身份并随协程上下文显式下发；各授权点只读
 * 当前协程携带的这一份，永不读取可变全局“当前回合/当前工具”推断归属。
 * - [turn]：本次执行的回合身份（executor 分配，非 Hook 侧 turnId）。
 * - [toolCallId]：发起确认的工具调用 ID；上游无证据时为 null（保持 unknown，不猜测）。
 * - [sink]：授权事实汇点；Group5 的 ConversationPermissionObserver 转发给 reducer。
 *   无上下文的调用（旧路径/单测）不发布事实，原行为不变。
 * - [permissionObservation]：系统权限链观察口径（Group2），由 AccessibilityController
 *   随权限请求透传；Group5 按执行创建并绑定回合后装入。
 *
 * Group5 用法：每次 submit 创建 `AuthorizationContext(turn, toolCallId, sink, obs)`，
 * 用 `withContext(auth) { llmController.stream(...) }` 包住执行冷流；事实按 requestId
 * 归属回合，响应经 `respond(requestId, allowed)` 登记到实际等待器。
 */
data class AuthorizationContext(
    val turn: TurnKey,
    val toolCallId: String? = null,
    val sink: RuntimeFactSink = NoOp,
    val permissionObservation: PermissionObservation? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AuthorizationContext>
}

/** 工具确认展示渠道（事实 channel 口径）：前台对话框 / 后台悬浮窗。 */
const val TOOL_CONFIRM_CHANNEL_FOREGROUND = "foreground"
const val TOOL_CONFIRM_CHANNEL_BACKGROUND = "background"

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
        // 路由前先发布请求事实，身份来自当前协程的 AuthorizationContext（创建时捕获）。
        val auth = coroutineContext[AuthorizationContext]
        auth?.emitSafely(request.toObservation(auth, PermissionPhase.Requested, channel = null, reason = null))
        // 原路由判定：一次读值决定渠道（前台对话框 → 后台 handler → 无渠道拒绝），
        // 挂起后不再重读前台状态；等待期间先发布带渠道的 Processing，终态沿用同一渠道。
        val foreground = isUiResumed
        val handler = backgroundConfirmationHandler
        val channel = when {
            foreground -> TOOL_CONFIRM_CHANNEL_FOREGROUND
            handler != null -> TOOL_CONFIRM_CHANNEL_BACKGROUND
            else -> null
        }
        if (channel != null) {
            auth?.emitSafely(request.toObservation(auth, PermissionPhase.Processing, channel, reason = null))
        }
        try {
            val response = when {
                foreground -> showInAppDialog(request)
                handler != null -> handler(request)
                else -> {
                    Logger.i(LOG_TAG, "confirm denied unavailable id=${request.id}")
                    ToolPermissionResponse.DENIED_UNAVAILABLE
                }
            }
            auth?.emitSafely(request.toObservation(auth, response.toPhase(), channel, response.toReason()))
            return response
        } catch (e: CancellationException) {
            // owner 真取消沿原路径传播，仅补 Cancelled 事实。
            auth?.emitSafely(
                request.toObservation(
                    auth, PermissionPhase.Cancelled, channel,
                    Reason("CANCELLED", "ToolPermissionCoordinator", null),
                ),
            )
            throw e
        } catch (e: Throwable) {
            auth?.emitSafely(
                request.toObservation(
                    auth, PermissionPhase.Failed, channel,
                    Reason("FAILED", "ToolPermissionCoordinator", e.message),
                ),
            )
            throw e
        }
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

    /**
     * UI 决策入口；requestId 与当前挂起请求不一致时忽略（旧回调不清除新绑定）。
     * @return true 仅当实际完成了一个等待器；Group5/Group8 凭此确认响应已登记。
     */
    fun respond(requestId: String, allowed: Boolean): Boolean {
        if (pendingFlow.value?.id != requestId) return false
        return deferred?.complete(
            if (allowed) ToolPermissionResponse.ALLOWED else ToolPermissionResponse.DENIED_BY_USER
        ) == true
    }
}

private fun ToolPermissionRequest.toObservation(
    auth: AuthorizationContext,
    phase: PermissionPhase,
    channel: String?,
    reason: Reason?,
): RuntimeFact = RuntimeFact.PermissionReported(
    PermissionRequestObservation(
        requestId = id,
        turn = auth.turn,
        toolCallId = auth.toolCallId,
        kind = PermissionKind.ToolConfirmation,
        target = toolName,
        channel = channel,
        phase = phase,
        reason = reason,
    ),
)

private fun ToolPermissionResponse.toPhase(): PermissionPhase = when (this) {
    ToolPermissionResponse.ALLOWED -> PermissionPhase.Allowed
    ToolPermissionResponse.DENIED_BY_USER -> PermissionPhase.Denied
    ToolPermissionResponse.DENIED_UNAVAILABLE -> PermissionPhase.Unavailable
}

private fun ToolPermissionResponse.toReason(): Reason? = when (this) {
    ToolPermissionResponse.ALLOWED -> null
    ToolPermissionResponse.DENIED_BY_USER ->
        Reason("DENIED_BY_USER", "ToolPermissionCoordinator", null)
    ToolPermissionResponse.DENIED_UNAVAILABLE ->
        Reason("UNAVAILABLE", "ToolPermissionCoordinator", null)
}

/**
 * 汇点隔离：观察者抛任何异常（含观察者来源 CancellationException）都不改变业务行为；
 * 真正的 owner 取消沿原路径（waiter.await 抛出）继续传播，不在此处吞掉。
 */
internal fun AuthorizationContext.emitSafely(fact: RuntimeFact) {
    try {
        sink.accept(fact)
    } catch (_: CancellationException) {
    } catch (_: Throwable) {
    }
}
