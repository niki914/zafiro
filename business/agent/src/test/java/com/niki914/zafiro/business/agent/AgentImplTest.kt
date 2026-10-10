package com.niki914.zafiro.business.agent

import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.Draft
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.chat.LlmStreamEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只测不触碰遗留引擎的部分：草稿镜像与 `stream()` 的同步拒绝。
 * 门禁的 `Started` 分支会真的走 `ensureConversation`（要 store 与引擎），留给层 2。
 */
import com.niki914.logging.Backend
import com.niki914.logging.Level
import com.niki914.logging.Logger
import org.junit.Before

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
