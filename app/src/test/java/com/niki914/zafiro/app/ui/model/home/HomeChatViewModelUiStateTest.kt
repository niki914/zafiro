package com.niki914.zafiro.app.ui.model.home

import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.api.model.AgentStatus
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.ConversationTurn
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.business.agent.turnIdAt
import com.niki914.zafiro.service.ServiceRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeChatViewModelUiStateTest {
    @get:Rule
    val mainDispatcherRule = com.niki914.zafiro.app.ui.model.MainDispatcherRule(UnconfinedTestDispatcher())

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @After
    fun tearDown() {
        ServiceRegistry.clearForTest()
    }

    @Test
    fun conversationSnapshotPreservesTextAfterToolBlock() = runTest {
        val fixture = fixture()
        val tail = "answer after tool"
        fixture.agent.publishConversation(Conversation(
            id = ConversationId("session-1"),
            turns = listOf(
                ConversationTurn(
                    id = turnIdAt(0),
                    userText = "question",
                    blocks = listOf(
                        TurnBlock.Text("t0:0", "before"),
                        TurnBlock.Tool("t0:1", com.niki914.zafiro.api.model.ToolInvocation("tool", "search", "Search")),
                        TurnBlock.Text("t0:2", tail),
                    ),
                ),
            ),
        ))
        advanceUntilIdle()

        assertEquals(
            listOf("before", "Search", tail),
            fixture.vm.uiStateFlow.value.turns.single().blocks.map { block ->
                when (block) {
                    is HomeChatBlock.Text -> block.text
                    is HomeChatBlock.Tool -> block.status.name
                    else -> error("Unexpected block: $block")
                }
            },
        )
    }

    @Test
    fun thinkingAutoExpandsWhileActiveAndManualCollapseSurvivesEcho() = runTest {
        val fixture = sendOneTurn()

        fixture.agent.publishThinking(text = "reasoning", isComplete = false)
        runCurrent()

        // 首发：自动展开 + 成为滚动跟随对象
        var state = fixture.vm.uiStateFlow.value
        assertEquals("0_0", state.activeThinkingKey)
        assertTrue("0_0" in state.expandedThinking)

        // 思考中手动收起：允许
        fixture.vm.sendIntent(HomeChatIntent.ToggleThinking(0, 0))
        runCurrent()
        assertFalse("0_0" in fixture.vm.uiStateFlow.value.expandedThinking)

        // 同一块的续接：只更新文本，不重新撑开
        fixture.agent.updateThinking(blockIndex = 0, text = "reasoning continues", isComplete = false)
        runCurrent()
        state = fixture.vm.uiStateFlow.value
        assertFalse("0_0" in state.expandedThinking)
        assertEquals("0_0", state.activeThinkingKey)

        // 块完成：摘 active（停滚动跟随），保持用户给的收起态
        fixture.agent.updateThinking(blockIndex = 0, text = "reasoning continues", isComplete = true)
        runCurrent()
        state = fixture.vm.uiStateFlow.value
        assertNull(state.activeThinkingKey)
        assertFalse("0_0" in state.expandedThinking)
    }

    @Test
    fun loadingConversationDoesNotAutoExpandHistoricalThinking() = runTest {
        val fixture = fixture()
        fixture.store.createRecord("saved", "question")
        fixture.store.setSnapshot("saved", snapshotOf(
            Message.User(listOf(ContentBlock.Text("question"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Thinking("historical reasoning")))),
        ))

        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("saved"))
        advanceUntilIdle()

        // 恢复出来的思考块已结束，既不自动展开也不成为滚动跟随对象
        val state = fixture.vm.uiStateFlow.value
        assertTrue(state.turns.single().blocks.single() is HomeChatBlock.Thinking)
        assertFalse("0_0" in state.expandedThinking)
        assertNull(state.activeThinkingKey)
    }

    @Test
    fun thinkingNewBlockCollapsesPreviousAutoExpanded() = runTest {
        val fixture = sendOneTurn()

        fixture.agent.publishThinking(text = "first", isComplete = false)
        runCurrent()
        assertTrue("0_0" in fixture.vm.uiStateFlow.value.expandedThinking)
        assertFalse("0_1" in fixture.vm.uiStateFlow.value.expandedThinking)

        // 新块到来：收起前面自动展开的块，展开新块
        fixture.agent.publishThinking(text = "second", isComplete = false)
        runCurrent()
        val state = fixture.vm.uiStateFlow.value
        assertTrue("0_1" in state.expandedThinking)
        assertFalse("0_0" in state.expandedThinking)
    }

    @Test
    fun thinkingUserExpandedBlockSurvivesNewBlock() = runTest {
        val fixture = sendOneTurn()

        fixture.agent.publishThinking(text = "first", isComplete = false)
        runCurrent()
        fixture.agent.publishThinking(text = "second", isComplete = false)
        runCurrent()
        // 块 1 自动展开，块 0 已被自动收起
        var state = fixture.vm.uiStateFlow.value
        assertTrue("0_1" in state.expandedThinking)
        assertFalse("0_0" in state.expandedThinking)

        // 用户手动展开块 0（接管）
        fixture.vm.sendIntent(HomeChatIntent.ToggleThinking(0, 0))
        runCurrent()
        state = fixture.vm.uiStateFlow.value
        assertTrue("0_0" in state.expandedThinking)
        assertTrue("0_1" in state.expandedThinking)

        // 块 2 到来：只收仍自动展开的块 1，用户展开的块 0 保留
        fixture.agent.publishThinking(text = "third", isComplete = false)
        runCurrent()
        state = fixture.vm.uiStateFlow.value
        assertTrue("0_2" in state.expandedThinking)
        assertTrue("0_0" in state.expandedThinking)
        assertFalse("0_1" in state.expandedThinking)
    }

    /**
     * 走一次 Send 建立本轮回合。
     *
     * 回合不写正文块：思考块从下标 0 起，展开态的 key 与断言同口径。
     */
    @Test
    fun completedThinkingBlockDoesNotAutoExpandWhenFirstObserved() = runTest {
        val fixture = sendOneTurn()
        fixture.agent.publishThinking(text = "completed reasoning", isComplete = true)
        runCurrent()

        val state = fixture.vm.uiStateFlow.value
        assertFalse("0_0" in state.expandedThinking)
        assertNull(state.activeThinkingKey)
    }

    private suspend fun TestScope.sendOneTurn(): Fixture {
        val fixture = fixture()
        fixture.agent.streamText = null
        fixture.vm.sendIntent(HomeChatIntent.InputChanged("question"))
        runCurrent()
        fixture.vm.sendIntent(HomeChatIntent.Send)
        runCurrent()
        fixture.agent.publishStatus(AgentStatus(phase = com.niki914.zafiro.api.model.AgentPhase.Generating))
        runCurrent()
        return fixture
    }
}
