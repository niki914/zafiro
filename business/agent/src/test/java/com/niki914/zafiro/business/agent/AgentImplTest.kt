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
class AgentImplTest {

    @After
    fun tearDown() {
        AgentImpl.clearForTest()
    }

    @Test
    fun stream_rejectsEmptyDraft() {
        assertEquals(TurnStart.DraftEmpty, AgentImpl.stream())
    }

    @Test
    fun updateDraft_isPureTransformOnCurrentDraft() {
        AgentImpl.updateDraft { it.copy(text = "你") }
        AgentImpl.updateDraft { it.copy(text = it.text + "好") }
        assertEquals("你好", AgentImpl.draft.value.text)
    }

    @Test
    fun clearDraft_resetsTextAndImages() {
        AgentImpl.updateDraft {
            Draft(
                text = "带图",
                images = listOf(DraftImage.Ready(Attachment("/tmp/a.jpg"))),
            )
        }
        AgentImpl.clearDraft()
        assertEquals(Draft(), AgentImpl.draft.value)
        assertTrue(AgentImpl.draft.value.images.isEmpty())
    }

    @Test
    fun stop_whenIdle_isNoOp() {
        AgentImpl.stop()
        assertEquals(AgentState.Idle(), AgentImpl.status.value)
    }

    @Test
    fun decideApproval_whenNoApprovers_returnsDeny() = runBlocking {
        val decision = AgentImpl.decideApproval(toolRequest("rm -rf /"))
        assertEquals(ApprovalDecision.Deny, decision)
    }

    @Test
    fun decideApproval_whenAllApproversAbstain_returnsDeny() = runBlocking {
        AgentImpl.addApprover(approverOf(ApprovalDecision.Abstain))
        AgentImpl.addApprover(approverOf(ApprovalDecision.Abstain))

        val decision = AgentImpl.decideApproval(toolRequest("rm -rf /data"))

        assertEquals(ApprovalDecision.Deny, decision)
    }

    @Test
    fun decideApproval_firstNonAbstainWins() = runBlocking {
        AgentImpl.addApprover(approverOf(ApprovalDecision.Allow, delayMs = 50))
        AgentImpl.addApprover(approverOf(ApprovalDecision.Deny, delayMs = 10))

        val decision = AgentImpl.decideApproval(toolRequest("ls"))

        assertEquals(ApprovalDecision.Deny, decision)
    }

    @Test
    fun decideApproval_abstainDoesNotBlockLaterDecision() = runBlocking {
        AgentImpl.addApprover(approverOf(ApprovalDecision.Abstain))
        AgentImpl.addApprover(approverOf(ApprovalDecision.Allow, delayMs = 10))

        val decision = AgentImpl.decideApproval(toolRequest("ls"))

        assertEquals(ApprovalDecision.Allow, decision)
    }

    @Test
    fun removeApprover_removesRegisteredApprover() = runBlocking {
        val approver = approverOf(ApprovalDecision.Allow)
        AgentImpl.addApprover(approver)
        AgentImpl.removeApprover(approver)

        val decision = AgentImpl.decideApproval(toolRequest("pwd"))

        assertEquals(ApprovalDecision.Deny, decision)
    }

    @Test
    fun fold_afterApplySessionId_preservesSessionIdAcrossEvents() {
        val sessionId = ConversationId("new-conv-uuid")
        AgentImpl.applySessionId(sessionId)
        assertEquals(sessionId, AgentImpl.conversation.value.id)

        AgentImpl.foldForTest(LlmStreamEvent.RoundStarted)
        assertEquals(sessionId, AgentImpl.conversation.value.id)

        AgentImpl.foldForTest(LlmStreamEvent.TextDelta(delta = "hello", fullText = "hello"))
        assertEquals(sessionId, AgentImpl.conversation.value.id)
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
