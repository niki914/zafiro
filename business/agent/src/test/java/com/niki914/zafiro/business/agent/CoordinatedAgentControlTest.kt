package com.niki914.zafiro.business.agent

// 保护：先到先得仲裁状态机（主对话运行中防抢占、空闲时抢占、结束后归还）

import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.Draft
import com.niki914.logging.Backend
import com.niki914.logging.Level
import com.niki914.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        override val draft: StateFlow<Draft> = MutableStateFlow(Draft())
        override fun updateDraft(transform: (Draft) -> Draft) {}
        override fun clearDraft() {}
        override fun stream(): TurnStart = TurnStart.Started
        override suspend fun load(id: ConversationId) {}
        override fun discard() {}
        override fun stop() { stopCallCount++ }
        override fun addApprover(approver: Approver) {}
        override fun removeApprover(approver: Approver) {}
        override suspend fun decideApproval(request: ApprovalRequest): ApprovalDecision =
            ApprovalDecision.Deny
    }

    @Test
    fun taskAgent_doesNotStealFocus_whenMainAgentIsAlreadyRunning() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val mainAgent = FakeAgent(initialState = AgentState.Generating("running query"))
        val coordinator = CoordinatedAgentControl(mainAgent, scope)

        val taskAgent = FakeAgent(initialState = AgentState.Idle())
        coordinator.trackTaskAgent(taskAgent)

        // Task Agent 进入 running
        taskAgent.stateFlow.value = AgentState.Generating("task query")

        // 焦点仍在 mainAgent，stop() 调用的应是 mainAgent
        coordinator.stop()
        assertEquals(1, mainAgent.stopCallCount)
        assertEquals(0, taskAgent.stopCallCount)
    }

    @Test
    fun taskAgent_acquiresFocus_whenMainAgentIsIdle() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val mainAgent = FakeAgent(initialState = AgentState.Idle())
        val coordinator = CoordinatedAgentControl(mainAgent, scope)

        val taskAgent = FakeAgent(initialState = AgentState.Idle())
        coordinator.trackTaskAgent(taskAgent)

        // Task Agent 进入 running
        taskAgent.stateFlow.value = AgentState.Generating("task query")

        // 抢占成功：stop() 路由至 taskAgent
        coordinator.stop()
        assertEquals(0, mainAgent.stopCallCount)
        assertEquals(1, taskAgent.stopCallCount)
    }

    @Test
    fun focus_returnsToMainAgent_whenTaskAgentCompletes() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val mainAgent = FakeAgent(initialState = AgentState.Idle())
        val coordinator = CoordinatedAgentControl(mainAgent, scope)

        val taskAgent = FakeAgent(initialState = AgentState.Idle())
        coordinator.trackTaskAgent(taskAgent)

        // 抢占焦点
        taskAgent.stateFlow.value = AgentState.Generating("task query")

        // 任务完成恢复 Idle
        taskAgent.stateFlow.value = AgentState.Idle()

        // 焦点归还给 mainAgent
        coordinator.stop()
        assertEquals(1, mainAgent.stopCallCount)
        assertEquals(0, taskAgent.stopCallCount)
    }
}
