package com.niki914.zafiro.app.ui.model.home

import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.ConversationTurn
import com.niki914.zafiro.api.model.ToolInvocation
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.app.ui.model.MainDispatcherRule
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
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

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
        fixture.agent.publishConversation(
            Conversation(
                id = ConversationId("session-1"),
                turns = listOf(
                    ConversationTurn(
                        id = turnIdAt(0),
                        userText = "question",
                        blocks = listOf(
                            TurnBlock.Text("t0:0", "before"),
                            TurnBlock.Tool("t0:1", ToolInvocation("tool", "search", "Search")),
                            TurnBlock.Text("t0:2", tail),
                        ),
                    ),
                ),
            )
        )
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
    fun thinkingActiveKeyFollowsIncompleteThinkingBlockAndClearsOnComplete() = runTest {
        val fixture = sendOneTurn()

        fixture.agent.publishThinking(text = "reasoning", isComplete = false)
        runCurrent()

        var state = fixture.vm.uiStateFlow.value
        assertEquals("0_0", state.activeThinkingKey)

        fixture.agent.updateThinking(
            blockIndex = 0,
            text = "reasoning continues",
            isComplete = false
        )
        runCurrent()
        state = fixture.vm.uiStateFlow.value
        assertEquals("0_0", state.activeThinkingKey)

        fixture.agent.updateThinking(
            blockIndex = 0,
            text = "reasoning continues",
            isComplete = true
        )
        runCurrent()
        state = fixture.vm.uiStateFlow.value
        assertNull(state.activeThinkingKey)
    }

    @Test
    fun loadingConversationDoesNotSetActiveThinkingKey() = runTest {
        val fixture = fixture()
        fixture.store.createRecord("saved", "question")
        fixture.store.setSnapshot(
            "saved", snapshotOf(
                Message.User(listOf(ContentBlock.Text("question"))),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Thinking("historical reasoning")))),
            )
        )

        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("saved"))
        advanceUntilIdle()

        val state = fixture.vm.uiStateFlow.value
        assertTrue(state.turns.single().blocks.single() is HomeChatBlock.Thinking)
        assertNull(state.activeThinkingKey)
    }

    @Test
    fun toggleActionKeyUpdatesUserToggledKeys() = runTest {
        val fixture = fixture()
        fixture.vm.sendIntent(HomeChatIntent.ToggleActionKey("10_thinking_0", isExpanded = false))
        runCurrent()

        assertEquals(false, fixture.vm.uiStateFlow.value.userToggledKeys["10_thinking_0"])

        fixture.vm.sendIntent(HomeChatIntent.ToggleActionKey("10_thinking_0", isExpanded = true))
        runCurrent()

        assertEquals(true, fixture.vm.uiStateFlow.value.userToggledKeys["10_thinking_0"])
    }

    private suspend fun TestScope.sendOneTurn(): Fixture {
        val fixture = fixture()
        fixture.agent.streamText = null
        fixture.vm.sendIntent(HomeChatIntent.InputChanged("question"))
        runCurrent()
        fixture.vm.sendIntent(HomeChatIntent.Send)
        runCurrent()
        fixture.agent.publishStatus(AgentState.Generating(text = null))
        runCurrent()
        return fixture
    }
}
