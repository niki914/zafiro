package com.niki914.zafiro.business.agent

import com.niki914.logging.Logger
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局 AgentControl 协调器。
 *
 * 核心原则：
 * 悬浮球与常驻通知等系统级控制入口严格绑定主对话 [mainFlow]。
 * 后台 Task Agent 独立静默运行，绝不抢占全局焦点；
 * 当主会话发生切换时，状态与注册的 [Approver] 自动无缝迁移至新主会话。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CoordinatedAgentControl(
    private val mainFlow: StateFlow<Agent>,
    private val scope: CoroutineScope,
) : AgentControl {

    constructor(mainAgent: Agent, scope: CoroutineScope) : this(
        mainFlow = MutableStateFlow(mainAgent),
        scope = scope,
    )

    private companion object {
        const val LOG_TAG = "niki914_zafiro_CoordinatedAgentControl"
    }

    private val registeredApprovers = CopyOnWriteArrayList<Approver>()

    override val status: StateFlow<AgentState> = mainFlow
        .flatMapLatest { it.status }
        .stateIn(scope, SharingStarted.Eagerly, mainFlow.value.status.value)

    init {
        var currentMain = mainFlow.value
        scope.launch {
            mainFlow.collect { newMain ->
                val prev = currentMain
                if (prev !== newMain) {
                    Logger.i(LOG_TAG, "mainAgent switched, migrating approvers")
                    registeredApprovers.forEach { prev.removeApprover(it) }
                    registeredApprovers.forEach { newMain.addApprover(it) }
                    currentMain = newMain
                }
            }
        }
    }

    override fun stop() {
        Logger.i(LOG_TAG, "stop routed to main agent")
        mainFlow.value.stop()
    }

    override fun addApprover(approver: Approver) {
        if (!registeredApprovers.contains(approver)) {
            registeredApprovers.add(approver)
        }
        mainFlow.value.addApprover(approver)
    }

    override fun removeApprover(approver: Approver) {
        registeredApprovers.remove(approver)
        mainFlow.value.removeApprover(approver)
    }

    override suspend fun decideApproval(request: ApprovalRequest): ApprovalDecision {
        return mainFlow.value.decideApproval(request)
    }
}
