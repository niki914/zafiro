package com.niki914.zafiro.app.ui.model.home

import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentStatus
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.ConversationTurn
import com.niki914.zafiro.api.model.Draft
import com.niki914.zafiro.api.model.DraftImage
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.business.agent.turnIdAt
import com.niki914.zafiro.app.conversation.ConversationFormatter
import com.niki914.zafiro.app.conversation.ConversationRecord
import com.niki914.zafiro.app.conversation.ConversationSummary
import com.niki914.zafiro.app.conversation.ForkKind
import com.niki914.zafiro.app.ui.model.TextPacer
import com.niki914.zafiro.app.ui.model.MainDispatcherRule
import com.niki914.zafiro.chat.LlmErrorCode
import com.niki914.zafiro.service.ServiceRegistry
import com.niki914.zafiro.service.installService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HomeChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        ServiceRegistry.clearForTest()
    }

    @Test
    fun sendMirrorsTextToAgentAndRendersItsConversation() = runTest {
        val fixture = fixture()
        fixture.vm.sendIntent(HomeChatIntent.InputChanged("  hello  "))
        runCurrent()
        fixture.vm.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        assertEquals("hello", fixture.agent.sentDrafts.single().text)
        assertEquals("", fixture.vm.uiStateFlow.value.input)
        assertEquals("hello", fixture.vm.uiStateFlow.value.turns.single().userText)
        assertEquals(listOf(HomeChatBlock.Text("answer")), fixture.vm.uiStateFlow.value.turns.single().blocks)
        assertFalse(fixture.vm.uiStateFlow.value.isGenerating)
    }

    @Test
    fun imageDraftComesFromAgentAndImageOnlySendKeepsAttachment() = runTest {
        val fixture = fixture()
        fixture.vm.sendIntent(HomeChatIntent.ImageAttached("content://image/1"))
        runCurrent()
        val image = fixture.vm.uiStateFlow.value.pendingImages.single()
        assertTrue(image.path.endsWith("image-1.jpg"))
        fixture.vm.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        assertEquals("", fixture.agent.sentDrafts.single().text)
        assertEquals(listOf(image.path), fixture.agent.sentImages.single().map { it.path })
        assertEquals(listOf(image.path), fixture.vm.uiStateFlow.value.turns.single().images.map { it.path })
    }

    @Test
    fun stopKeepsPartialConversationAndCallsAgentStop() = runTest {
        val fixture = fixture()
        fixture.agent.streamResult = TurnStart.Started
        fixture.vm.sendIntent(HomeChatIntent.InputChanged("question"))
        runCurrent()
        fixture.vm.sendIntent(HomeChatIntent.Send)
        runCurrent()
        fixture.agent.publishConversation(
            Conversation(
                id = ConversationId("session-1"),
                turns = listOf(ConversationTurn(id = turnIdAt(0), userText = "question", blocks = listOf(TurnBlock.Text("t0:0", "partial")))),
            ),
        )
        fixture.agent.publishStatus(AgentStatus(phase = com.niki914.zafiro.api.model.AgentPhase.Generating))
        runCurrent()
        assertTrue(fixture.vm.uiStateFlow.value.isGenerating)
        fixture.vm.sendIntent(HomeChatIntent.StopGenerating)
        advanceUntilIdle()

        assertEquals(1, fixture.agent.stopCount)
        assertEquals(listOf(HomeChatBlock.Text("partial")), fixture.vm.uiStateFlow.value.turns.single().blocks)
        assertFalse(fixture.vm.uiStateFlow.value.isGenerating)
    }

    @Test
    fun startupRestoreUsesAgentStateAndRestoresUiDraft() = runTest {
        val store = FakeHomeConversationStore(restoreOnStartup = true)
        store.createRecord("saved", "hello")
        store.setSnapshot("saved", snapshotOf(
            Message.User(listOf(ContentBlock.Text("hello"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("answer")))),
        ))
        store.updateDraft("saved", "unfinished")
        store.setLastOpenedConversationId("saved")
        val fixture = fixture(store = store)
        advanceUntilIdle()

        val state = fixture.vm.uiStateFlow.value
        assertEquals("saved", fixture.agent.lastLoadedId?.value)
        assertEquals("saved", state.currentConversationId)
        assertEquals("unfinished", state.input)
        assertEquals("hello", state.turns.single().userText)
        assertEquals(listOf(HomeChatBlock.Text("answer")), state.turns.single().blocks)
    }

    @Test
    fun switchingConversationLoadsAgentAndClearsUnrestoredDraft() = runTest {
        val store = FakeHomeConversationStore()
        store.createRecord("first", "one")
        store.setSnapshot("first", snapshotOf(Message.User(listOf(ContentBlock.Text("one")))))
        store.createRecord("second", "two")
        store.setSnapshot("second", snapshotOf(
            Message.User(listOf(ContentBlock.Text("two"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("reply")))),
        ))
        store.updateDraft("second", "should not restore")
        val fixture = fixture(store = store)

        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("second"))
        advanceUntilIdle()

        val state = fixture.vm.uiStateFlow.value
        assertEquals("second", fixture.agent.lastLoadedId?.value)
        assertEquals("second", state.currentConversationId)
        assertEquals("", state.input)
        assertEquals("two", state.turns.single().userText)
        assertEquals("second", store.lastOpenedConversationId())
    }

    @Test
    fun newConversationDiscardsAgentButLeavesSavedHistory() = runTest {
        val fixture = fixture()
        fixture.store.createRecord("saved", "old")
        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("saved"))
        advanceUntilIdle()
        fixture.vm.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()

        assertEquals(1, fixture.agent.discardCount)
        assertEquals(null, fixture.vm.uiStateFlow.value.currentConversationId)
        assertEquals("", fixture.store.lastOpenedConversationId())
        assertTrue(fixture.store.getConversation("saved") != null)
    }

    @Test
    fun deleteActiveConversationDeletesHistoryAndDiscardsAgent() = runTest {
        val fixture = fixture()
        fixture.store.createRecord("saved", "old")
        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("saved"))
        advanceUntilIdle()
        fixture.vm.sendIntent(HomeChatIntent.DeleteConversation("saved"))
        advanceUntilIdle()

        assertEquals(1, fixture.agent.discardCount)
        assertNull(fixture.store.getConversation("saved"))
        assertEquals(null, fixture.vm.uiStateFlow.value.currentConversationId)
    }

    @Test
    fun forkRetainsExistingAppOwnedHistoryPath() = runTest {
        val fixture = fixture(history = listOf(
            Message.User(listOf(ContentBlock.Text("first"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("answer")))),
            Message.User(listOf(ContentBlock.Text("second"))),
        ))
        fixture.store.createRecord("source", "first")
        fixture.store.setSnapshot("source", snapshotOf(
            Message.User(listOf(ContentBlock.Text("first"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("answer")))),
            Message.User(listOf(ContentBlock.Text("second"))),
        ))
        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("source"))
        advanceUntilIdle()
        fixture.vm.sendIntent(HomeChatIntent.ForkAt(0))
        advanceUntilIdle()

        val fork = fixture.store.getConversation(fixture.store.lastOpenedConversationId())!!
        assertTrue(fork.summary.title.startsWith("Fork ·"))
        assertEquals(2, fork.snapshot.entries.size)
        assertEquals(fork.summary.id, fixture.agent.lastLoadedId?.value)
    }

    @Test
    fun regenerateAndRewindKeepSelectedQueryAndImages() = runTest {
        val history = listOf(
            Message.User(listOf(ContentBlock.Text("first"), ContentBlock.Image("/image.jpg", "image/jpeg"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("answer")))),
            Message.User(listOf(ContentBlock.Text("second"))),
        )
        val fixture = fixture(history = history)
        fixture.store.createRecord("source", "first")
        fixture.store.setSnapshot("source", snapshotOf(*history.toTypedArray()))
        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("source"))
        advanceUntilIdle()

        fixture.vm.sendIntent(HomeChatIntent.ReGenerateAt(0))
        advanceUntilIdle()
        assertEquals("first", fixture.agent.sentDrafts.last().text)
        assertEquals(listOf("/image.jpg"), fixture.agent.sentImages.last().map { it.path })

        fixture.vm.sendIntent(HomeChatIntent.RewindAt(0))
        advanceUntilIdle()
        assertEquals("first", fixture.vm.uiStateFlow.value.input)
        assertEquals("/image.jpg", fixture.vm.uiStateFlow.value.pendingImages.single().path)
    }

}
