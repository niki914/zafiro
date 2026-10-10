package com.niki914.zafiro.business.agent

import com.niki914.logging.Logger
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.isRunning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局聚合 AgentControl 协调器。
 *
 * 遵循「先到先得」策略仲裁前台通知栏与悬浮窗所有权：
 * 1. 默认焦点归属于主前台 [mainAgent]；
 * 2. 主前台处于 [AgentState.isRunning] 时，后来的 Task Agent 不得抢占焦点；
 * 3. 主前台空闲时，率先运行的 Task Agent 赢得焦点；
 * 4. 赢得焦点的 Agent 结束（恢复 Idle）后，焦点自动交还主前台。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CoordinatedAgentControl(
    private val mainAgent: Agent,
    private val scope: CoroutineScope,
) : AgentControl {

    private companion object {
        const val LOG_TAG = "niki914_zafiro_CoordinatedAgentControl"
    }

    private val activeFocus = MutableStateFlow<AgentControl>(mainAgent)
    private val registeredApprovers = CopyOnWriteArrayList<Approver>()

    override val status: StateFlow<AgentState> = activeFocus
        .flatMapLatest { it.status }
        .stateIn(scope, SharingStarted.Eagerly, mainAgent.status.value)

    init {
        // 监控 mainAgent 的状态变化
        scope.launch {
            mainAgent.status.collect { state ->
                val current = activeFocus.value
                if (current !== mainAgent && !current.status.value.isRunning && state.isRunning) {
                    Logger.i(LOG_TAG, "mainAgent reclaimed focus (running)")
                    switchFocus(mainAgent)
                }
            }
        }
    }

    /** 注册并追踪一个新创建的 Task Agent。 */
    fun trackTaskAgent(taskAgent: Agent): Job {
        registeredApprovers.forEach { taskAgent.addApprover(it) }
        val agentRef = WeakReference(taskAgent)
        val statusFlow = taskAgent.status
        return scope.launch {
            statusFlow.collect { state ->
                val agent = agentRef.get() ?: run {
                    switchFocus(mainAgent)
                    cancel()
                    return@collect
                }
                val current = activeFocus.value
                if (state.isRunning) {
                    // 先到先得：只有当前焦点未在 Running 时，新 Running 的任务才能抢占
                    if (!current.status.value.isRunning) {
                        Logger.i(LOG_TAG, "taskAgent acquired focus (first-come first-served)")
                        switchFocus(agent)
                    }
                } else if (current === agent) {
                    // 当前持焦点的任务结束，焦点归还给 mainAgent
                    Logger.i(LOG_TAG, "taskAgent completed, focus returned to mainAgent")
                    switchFocus(mainAgent)
                }
            }
        }
    }

    private fun switchFocus(newFocus: AgentControl) {
        activeFocus.value = newFocus
    }

    override fun stop() {
        Logger.i(LOG_TAG, "stop routed to current focused agent")
        activeFocus.value.stop()
    }

    override fun addApprover(approver: Approver) {
        if (!registeredApprovers.contains(approver)) {
            registeredApprovers.add(approver)
        }
        mainAgent.addApprover(approver)
        activeFocus.value.addApprover(approver)
    }

    override fun removeApprover(approver: Approver) {
        registeredApprovers.remove(approver)
        mainAgent.removeApprover(approver)
        activeFocus.value.removeApprover(approver)
    }

    override suspend fun decideApproval(request: ApprovalRequest): ApprovalDecision {
        return activeFocus.value.decideApproval(request)
    }
}
