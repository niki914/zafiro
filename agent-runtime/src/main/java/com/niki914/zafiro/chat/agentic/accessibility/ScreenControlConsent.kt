package com.niki914.zafiro.chat.agentic.accessibility

import com.niki914.zafiro.chat.agentic.shell.AuthorizationContext
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator
import com.niki914.zafiro.chat.agentic.shell.emitSafely
import com.niki914.zafiro.chat.runtime.PermissionKind
import com.niki914.zafiro.chat.runtime.PermissionPhase
import com.niki914.zafiro.chat.runtime.PermissionRequestObservation
import com.niki914.zafiro.chat.runtime.Reason
import com.niki914.zafiro.chat.runtime.RuntimeFact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * 屏幕控制知情同意（无障碍 + 悬浮窗，缺一不可）。
 * 前台 → Compose 对话框（HomePageContent 渲染 [pending]）；后台 → 直接拒绝（弹不了窗）。
 * 无状态不记忆：拒绝后下次申请会再弹（调用方重调即重试，与 PRD 重试哲学一致）。
 * 在 PermissionManager 链路之外：同意后引擎照常尽力尝试，拒绝则整体不进引擎。
 */
object ScreenControlConsent {
    private val pendingFlow = MutableStateFlow(false)

    /** 当前是否有挂起的知情请求；UI collect 后渲染对话框。 */
    val pending: StateFlow<Boolean> = pendingFlow.asStateFlow()

    private val mutex = Mutex()
    private var deferred: CompletableDeferred<Boolean>? = null
    private var currentRequestId: String? = null

    /** 屏控知情渠道（事实 channel 口径）：仅前台对话框；后台直接拒绝，无等待渠道。 */
    const val CHANNEL_FOREGROUND = "foreground"

    /**
     * 并发请求串行化：第二个请求等第一个出结果后再弹，不覆盖 waiter。
     * 串行等待时 UI 前台状态可能变化，每轮重新检查 isUiResumed。
     *
     * T-11：内部补 requestId（每次调用分配），路由前发布 Requested 事实；身份来自
     * 当前协程的 AuthorizationContext（无上下文时不发布事实，直接走原逻辑）。
     * Boolean 返回签名保留，现有弹窗（HomePageContent [pending]）行为不变。
     */
    suspend fun request(): Boolean = mutex.withLock {
        val auth = coroutineContext[AuthorizationContext]
        val requestId = UUID.randomUUID().toString()
        fun report(phase: PermissionPhase, channel: String?, reason: Reason?) {
            val sinkAuth = auth ?: return
            sinkAuth.emitSafely(
                RuntimeFact.PermissionReported(
                    PermissionRequestObservation(
                        requestId = requestId,
                        turn = sinkAuth.turn,
                        toolCallId = sinkAuth.toolCallId,
                        kind = PermissionKind.ScreenConsent,
                        target = null,
                        channel = channel,
                        phase = phase,
                        reason = reason,
                    ),
                ),
            )
        }
        // 后台仍直接拒绝（弹不了窗），拒绝前先让请求可见。
        report(PermissionPhase.Requested, channel = null, reason = null)
        if (!ToolPermissionCoordinator.isUiResumed) {
            report(
                PermissionPhase.Denied, channel = null,
                Reason("BACKGROUND_REFUSED", "ScreenControlConsent", null),
            )
            return false
        }
        // 等待期间暴露前台渠道；挂起后不再重读前台状态。
        report(PermissionPhase.Processing, CHANNEL_FOREGROUND, reason = null)
        pendingFlow.value = true
        val waiter = CompletableDeferred<Boolean>()
        deferred = waiter
        currentRequestId = requestId
        try {
            val allowed = waiter.await()
            report(
                if (allowed) PermissionPhase.Allowed else PermissionPhase.Denied,
                CHANNEL_FOREGROUND,
                if (allowed) null else Reason("DENIED_BY_USER", "ScreenControlConsent", null),
            )
            return allowed
        } catch (e: CancellationException) {
            // owner 真取消沿原路径传播，仅补 Cancelled 事实。
            report(
                PermissionPhase.Cancelled, CHANNEL_FOREGROUND,
                Reason("CANCELLED", "ScreenControlConsent", null),
            )
            throw e
        } catch (e: Throwable) {
            report(
                PermissionPhase.Failed, CHANNEL_FOREGROUND,
                Reason("FAILED", "ScreenControlConsent", e.message),
            )
            throw e
        } finally {
            if (deferred === waiter) {
                deferred = null
                currentRequestId = null
                pendingFlow.value = false
            }
        }
    }

    /**
     * 带请求身份的响应接缝（Group5/Group8 接线用）。
     * requestId 与当前挂起请求不一致时返回 false，不触碰现有绑定；
     * 一致时完成同一个原等待器并沿原 finally 清理，返回是否实际完成。
     */
    fun respond(requestId: String, allowed: Boolean): Boolean {
        if (currentRequestId != requestId) return false
        return deferred?.complete(allowed) == true
    }

    /** UI 决策入口（对话框按钮）；原语义保留，仍完成当前 waiter。 */
    fun respond(allowed: Boolean) {
        deferred?.complete(allowed)
    }
}
