// 保护：宿主在生成中发新消息时的抢占重试契约——必须 stop 并等到回合真正落地（Idle）
// 之后才重新 stream；stop 落地窗口内 stream 仍回 Busy 时必须重试，不得直接放弃。
package com.niki914.zafiro.runtime.service

import android.app.Application
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PreemptPreviousTurnTest {

    @Test
    fun restartsAfterOldTurnActuallySettles() = runTest {
        val agent = FakeAgent(this, busyStreamsBeforeStart = 1)

        val started = preemptPreviousTurn(agent)

        assertTrue(started)
        assertEquals(2, agent.stopCount)
        assertEquals(2, agent.streamCount)
    }

    @Test
    fun retriesWhileStopHasNotSettledYet() = runTest {
        // 旧回合在 stop 之后连回两次 Busy，只有第三次才真正可发起
        val agent = FakeAgent(this, busyStreamsBeforeStart = 2)

        val started = preemptPreviousTurn(agent)

        assertTrue(started)
        assertEquals(3, agent.stopCount)
        assertEquals(3, agent.streamCount)
    }

    @Test
    fun givesUpAfterBoundedAttempts() = runTest {
        val agent = FakeAgent(this, busyStreamsBeforeStart = Int.MAX_VALUE)

        val started = preemptPreviousTurn(agent, maxAttempts = 3)

        assertFalse(started)
        assertEquals(3, agent.streamCount)
    }

    @Test
    fun stopsWhenDraftIsEmpty() = runTest {
        val agent = FakeAgent(this, busyStreamsBeforeStart = 1, draftEmptyOnRestart = true)

        val started = preemptPreviousTurn(agent)

        assertFalse(started)
    }

    /**
     * 模拟 Agent 的 stop 落地窗口：stop() 之后状态不立即变 Idle，而是异步晚一拍；
     * 前 [busyStreamsBeforeStart] 次 stream() 返回 Busy，之后返回 Started。
     */
    private class FakeAgent(
        private val scope: CoroutineScope,
        private val busyStreamsBeforeStart: Int,
        private val draftEmptyOnRestart: Boolean = false,
    ) : Agent {
        private val mutableStatus = MutableStateFlow<AgentState>(AgentState.Generating(null))
        override val status: StateFlow<AgentState> = mutableStatus.asStateFlow()

        var stopCount = 0
            private set
        var streamCount = 0
            private set

        override fun stop() {
            stopCount++
            // 异步落 Idle：模拟 stop 与 Idle 之间的窗口，逼重试逻辑不能只测一次
            scope.launch { mutableStatus.value = AgentState.Idle() }
        }

        override fun stream(): TurnStart {
            streamCount++
            if (draftEmptyOnRestart && streamCount > 1) return TurnStart.DraftEmpty
            return if (streamCount <= busyStreamsBeforeStart) TurnStart.Busy else TurnStart.Started
        }

        override val conversation: StateFlow<Conversation> = MutableStateFlow(Conversation())
        override val contextUsage: StateFlow<ContextUsage?> = MutableStateFlow(null)
        override val draft: StateFlow<Draft> = MutableStateFlow(Draft())
        override fun updateDraft(transform: (Draft) -> Draft) = Unit
        override fun clearDraft() = Unit
        override suspend fun load(id: ConversationId) = Unit
        override fun discard() = Unit
        override fun release() = Unit
        override fun bindConfig(configId: String) = Unit
        override fun addApprover(approver: Approver) = Unit
        override fun removeApprover(approver: Approver) = Unit
        override suspend fun decideApproval(request: ApprovalRequest): ApprovalDecision =
            ApprovalDecision.Abstain
    }
}
