package com.niki914.zafiro.app.notification

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.niki914.logging.Logger
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.runtime.service.AgentRuntimeService
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 常驻通知生命周期与授权决议管理器。
 *
 * 核心设计：
 * - 对应悬浮球 [FloatingBallOverlayManager]，作为常驻通知状态载体的控制中枢；
 * - 实现 [Approver] 并向 [AgentControl] 注册，与悬浮球、Compose 授权对话框并发协作；
 * - 驱动 [AgentRuntimeService] 的前台服务启停与实时状态/操作同步。
 */
object ResidentNotificationManager {

    private const val TAG = "niki914_zafiro_ResidentNotificationManager"

    private var activeApprovalCont: CancellableContinuation<ApprovalDecision>? = null
    var activeApprovalRequest: ApprovalRequest? = null
        private set

    private var approverRegistered = false

    internal val residentApprover = object : Approver {
        override suspend fun decide(request: ApprovalRequest): ApprovalDecision {
            return suspendCancellableCoroutine { cont ->
                activeApprovalCont = cont
                activeApprovalRequest = request
                AgentRuntimeService.notifyUpdate()
                cont.invokeOnCancellation {
                    if (activeApprovalCont === cont) {
                        activeApprovalCont = null
                    }
                    if (activeApprovalRequest == request) {
                        activeApprovalRequest = null
                    }
                    AgentRuntimeService.notifyUpdate()
                }
            }
        }
    }

    fun resolveApproval(decision: ApprovalDecision) {
        val cont = activeApprovalCont
        activeApprovalCont = null
        activeApprovalRequest = null
        cont?.resume(decision)
        AgentRuntimeService.notifyUpdate()
    }

    fun start(context: Context) {
        Logger.i(TAG, "Starting resident notification")
        if (!approverRegistered) {
            val agentControl = runCatching { requireService<AgentControl>() }.getOrNull()
            agentControl?.addApprover(residentApprover)
            approverRegistered = true
        }

        val intent = Intent(context, AgentRuntimeService::class.java).apply {
            action = AgentRuntimeService.ACTION_START_RESIDENT
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to startForegroundService for AgentRuntimeService", e)
        }
    }

    fun stop(context: Context) {
        Logger.i(TAG, "Stopping resident notification")
        if (approverRegistered) {
            val agentControl = runCatching { requireService<AgentControl>() }.getOrNull()
            agentControl?.removeApprover(residentApprover)
            approverRegistered = false
        }
        val cont = activeApprovalCont
        activeApprovalCont = null
        activeApprovalRequest = null
        cont?.cancel()

        val intent = Intent(context, AgentRuntimeService::class.java).apply {
            action = AgentRuntimeService.ACTION_STOP_RESIDENT
        }
        try {
            context.startService(intent)
        } catch (e: Throwable) {
            Logger.e(TAG, "Failed to send stop action to AgentRuntimeService", e)
        }
    }

    internal fun resetForTest() {
        val agentControl = runCatching { requireService<AgentControl>() }.getOrNull()
        if (approverRegistered) {
            agentControl?.removeApprover(residentApprover)
            approverRegistered = false
        }
        activeApprovalCont?.cancel()
        activeApprovalCont = null
        activeApprovalRequest = null
    }
}
