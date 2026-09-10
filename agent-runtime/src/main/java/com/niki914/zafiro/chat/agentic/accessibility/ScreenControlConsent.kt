package com.niki914.zafiro.chat.agentic.accessibility

import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /**
     * 并发请求串行化：第二个请求等第一个出结果后再弹，不覆盖 waiter。
     * 串行等待时 UI 前台状态可能变化，每轮重新检查 isUiResumed。
     */
    suspend fun request(): Boolean = mutex.withLock {
        if (!ToolPermissionCoordinator.isUiResumed) return false
        pendingFlow.value = true
        val waiter = CompletableDeferred<Boolean>()
        deferred = waiter
        try {
            return waiter.await()
        } finally {
            if (deferred === waiter) {
                deferred = null
                pendingFlow.value = false
            }
        }
    }

    /** UI 决策入口（对话框按钮）。 */
    fun respond(allowed: Boolean) {
        deferred?.complete(allowed)
    }
}
