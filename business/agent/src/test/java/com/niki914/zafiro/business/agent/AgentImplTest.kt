// 保护：Agent 状态机重置、草稿镜像、权限裁决与会话绑定/加载生命周期契约
package com.niki914.zafiro.business.agent

import com.niki914.logging.Backend
import com.niki914.logging.Level
import com.niki914.logging.Logger
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.Draft
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.service.installService
import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.RuntimeBridge
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.RuntimeHostGateway
import com.niki914.zafiro.settings.RuntimeSettingsGateway
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import com.niki914.zafiro.settings.model.RuntimeToolValidation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentImplTest {

    private val agent = AgentImpl()

    @Before
    fun silenceLogger() {
        Logger.install(object : Backend {
            override fun emit(level: Level, tag: String, msg: String, throwable: Throwable?) = Unit
        })
    }

    @After
    fun tearDown() {
        agent.clearForTest()
    }

    @Test
    fun stream_rejectsEmptyDraft() {
        assertEquals(TurnStart.DraftEmpty, agent.stream())
    }

    @Test
    fun updateDraft_isPureTransformOnCurrentDraft() {
        agent.updateDraft { it.copy(text = "你") }
        agent.updateDraft { it.copy(text = it.text + "好") }
        assertEquals("你好", agent.draft.value.text)
    }

    @Test
    fun clearDraft_resetsTextAndImages() {
        agent.updateDraft {
            Draft(
                text = "带图",
                images = listOf(DraftImage.Ready(Attachment("/tmp/a.jpg"))),
            )
        }
        agent.clearDraft()
        assertEquals(Draft(), agent.draft.value)
        assertTrue(agent.draft.value.images.isEmpty())
    }

    @Test
    fun stop_whenIdle_isNoOp() {
        agent.stop()
        assertEquals(AgentState.Idle(), agent.status.value)
    }

    @Test
    fun decideApproval_whenNoApprovers_returnsDeny() = runBlocking {
        val decision = agent.decideApproval(toolRequest("rm -rf /"))
        assertEquals(ApprovalDecision.Deny, decision)
    }

    @Test
    fun decideApproval_whenAllApproversAbstain_returnsDeny() = runBlocking {
        agent.addApprover(approverOf(ApprovalDecision.Abstain))
        agent.addApprover(approverOf(ApprovalDecision.Abstain))

        val decision = agent.decideApproval(toolRequest("rm -rf /data"))

        assertEquals(ApprovalDecision.Deny, decision)
    }

    @Test
    fun decideApproval_firstNonAbstainWins() = runBlocking {
        agent.addApprover(approverOf(ApprovalDecision.Allow, delayMs = 50))
        agent.addApprover(approverOf(ApprovalDecision.Deny, delayMs = 10))

        val decision = agent.decideApproval(toolRequest("ls"))

        assertEquals(ApprovalDecision.Deny, decision)
    }

    @Test
    fun decideApproval_abstainDoesNotBlockLaterDecision() = runBlocking {
        agent.addApprover(approverOf(ApprovalDecision.Abstain))
        agent.addApprover(approverOf(ApprovalDecision.Allow, delayMs = 10))

        val decision = agent.decideApproval(toolRequest("ls"))

        assertEquals(ApprovalDecision.Allow, decision)
    }

    @Test
    fun removeApprover_removesRegisteredApprover() = runBlocking {
        val approver = approverOf(ApprovalDecision.Allow)
        agent.addApprover(approver)
        agent.removeApprover(approver)

        val decision = agent.decideApproval(toolRequest("pwd"))

        assertEquals(ApprovalDecision.Deny, decision)
    }

    @Test
    fun fold_afterApplySessionId_preservesSessionIdAcrossEvents() {
        val sessionId = ConversationId("new-conv-uuid")
        agent.applySessionId(sessionId)
        assertEquals(sessionId, agent.conversation.value.id)

        agent.foldForTest(LlmStreamEvent.RoundStarted)
        assertEquals(sessionId, agent.conversation.value.id)

        agent.foldForTest(LlmStreamEvent.TextDelta(delta = "hello", fullText = "hello"))
        assertEquals(sessionId, agent.conversation.value.id)
    }

    @Test
    fun discard_clearsEngineBoundConfig() {
        agent.engine.bindConfig("cfg-custom")
        agent.discard()
        assertEquals("", agent.engine.boundConfigId)
    }

    @Test
    fun load_bindsStoredConfigIdToEngine() = runBlocking {
        val fakeStore = object : ConversationStore_Tmp {
            var stored: StoredConversation_Tmp? = null
            override suspend fun load(id: ConversationId) = stored
            override suspend fun exists(id: ConversationId) = stored != null
            override suspend fun create(id: ConversationId, firstUserInput: String) {}
            override suspend fun setLastOpened(id: ConversationId?) {}
        }
        val fakeGateway = StubRuntimeSettingsGateway()
        val fakeHost = object : RuntimeHostGateway {
            override suspend fun postNotification(title: String, content: String, uri: String?): Boolean = true
        }
        installService<ConversationStore_Tmp>(fakeStore)
        RuntimeEnvironment.install(RuntimeBridge(settings = fakeGateway, host = fakeHost))
        try {
            fakeStore.stored = StoredConversation_Tmp(
                snapshot = SessionSnapshot(id = "conv-1", leafId = null, version = 1, entries = emptyList()),
                conversation = Conversation(),
                configId = "cfg-persisted",
            )
            agent.load(ConversationId("conv-1"))
            assertEquals("cfg-persisted", agent.engine.boundConfigId)
        } finally {
            RuntimeEnvironment.clearForTest()
        }
    }

    private fun toolRequest(command: String) = ApprovalRequest.ToolExecution(
        toolName = "terminal",
        command = command,
        ruleName = "RULE",
    )

    private fun approverOf(
        decision: ApprovalDecision,
        delayMs: Long = 0,
    ) = object : Approver {
        override suspend fun decide(request: ApprovalRequest): ApprovalDecision {
            if (delayMs > 0) delay(delayMs)
            return decision
        }
    }
}

private class StubRuntimeSettingsGateway : RuntimeSettingsGateway {
    override suspend fun readLlmConfig(agentId: String, configId: String?): RuntimeLlmConfig =
        RuntimeLlmConfig(endpoint = "https://api.openai.com", model = "gpt-4o")
    override suspend fun listMcpServers(): List<RuntimeMcpServer> = emptyList()
    override suspend fun addMemory(value: String) = Unit
    override suspend fun removeMemory(oldText: String): MemoryMutationResult = MemoryMutationResult.Ok
    override suspend fun replaceMemory(oldText: String, content: String): MemoryMutationResult = MemoryMutationResult.Ok
    override suspend fun listCustomPyTools(): List<RuntimeCustomPyTool> = emptyList()
    override suspend fun saveCustomPyTool(tool: RuntimeCustomPyTool, overwrite: Boolean): RuntimeToolValidation? = null
    override suspend fun deleteCustomPyTool(name: String) = Unit
    override suspend fun setCustomPyToolEnabled(name: String, enabled: Boolean) = Unit
    override suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> = emptyList()
    override suspend fun setBuiltinToolEnabled(name: String, enabled: Boolean): RuntimeToolValidation? = null
    override suspend fun setBuiltinToolGroupEnabled(groupId: String, enabled: Boolean): RuntimeToolValidation? = null
    override suspend fun listExecutionRules(): List<RuntimeExecutionRule> = emptyList()
}
