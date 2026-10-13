package com.niki914.zafiro.business.agent

// 保护：先到先得仲裁状态机（主对话运行中防抢占、空闲时抢占、结束后归还）

import com.niki914.logging.Backend
import com.niki914.logging.Level
import com.niki914.logging.Logger
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.ContextUsage
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.Draft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CoordinatedAgentControlTest {

    @Before
    fun silenceLogger() {
        Logger.install(object : Backend {
            override fun emit(level: Level, tag: String, msg: String, throwable: Throwable?) = Unit
        })
    }

    private class FakeAgent(
        initialState: AgentState = AgentState.Idle(),
    ) : Agent {
        val stateFlow = MutableStateFlow(initialState)
        var stopCallCount = 0

        override val status: StateFlow<AgentState> = stateFlow.asStateFlow()
        override val conversation: StateFlow<Conversation> = MutableStateFlow(Conversation())
        override val contextUsage: StateFlow<ContextUsage?> = MutableStateFlow(null)
        override val draft: StateFlow<Draft> = MutableStateFlow(Draft())
        override fun updateDraft(transform: (Draft) -> Draft) {}
        override fun clearDraft() {}
        override fun stream(): TurnStart = TurnStart.Started
        override suspend fun load(id: ConversationId) {}
        override fun discard() {}
        override fun release() {}
        override fun bindConfig(configId: String) {}
        override fun stop() {
            stopCallCount++
        }

        val registeredApprovers = mutableListOf<Approver>()
        override fun addApprover(approver: Approver) {
            registeredApprovers.add(approver)
        }

        override fun removeApprover(approver: Approver) {
            registeredApprovers.remove(approver)
        }

        override suspend fun decideApproval(request: ApprovalRequest): ApprovalDecision =
            ApprovalDecision.Deny
    }

    @Test
    fun mainSwitch_updatesStatusAndApproversSeamlessly() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val agent1 = FakeAgent(initialState = AgentState.Idle())
        val agent2 = FakeAgent(initialState = AgentState.WaitingApproval(toolRequest("test")))
        val mainFlow = MutableStateFlow<Agent>(agent1)
        val coordinator = CoordinatedAgentControl(mainFlow, scope)

        val approver = object : Approver {
            override suspend fun decide(request: ApprovalRequest): ApprovalDecision =
                ApprovalDecision.Allow
        }
        coordinator.addApprover(approver)
        assertEquals(listOf(approver), agent1.registeredApprovers)

        // 切换到 agent2：approver 自动移交给 agent2，status 立即跟随 agent2
        mainFlow.value = agent2
        assertEquals(emptyList<Approver>(), agent1.registeredApprovers)
        assertEquals(listOf(approver), agent2.registeredApprovers)
        assertTrue(coordinator.status.value is AgentState.WaitingApproval)
    }

    private fun toolRequest(cmd: String): ApprovalRequest =
        ApprovalRequest.ToolExecution(
            toolName = "bash",
            command = cmd,
            ruleName = "confirm",
            matchedPattern = cmd,
        )
}
