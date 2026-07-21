package com.niki914.nexus.agentic.app.ui.nexus.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.niki914.nexus.agentic.app.conversation.ConversationFormatter
import com.niki914.nexus.agentic.app.conversation.ConversationRecord
import com.niki914.nexus.agentic.app.conversation.ConversationRepo
import com.niki914.nexus.agentic.app.conversation.ConversationSummary
import com.niki914.nexus.agentic.chat.LlmStreamEvent
import com.niki914.nexus.agentic.chat.ToolCallStatus
import com.niki914.nexus.agentic.repo.AppStateSettings
import com.niki914.nexus.agentic.repo.AppStateSettingsCodec
import com.niki914.nexus.agentic.repo.DomainSettingsStore
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.store.StoreDescriptorRegistry
import com.niki914.s3ss10n.ChatTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.RunWith
import org.junit.runner.Description
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HomeChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    private lateinit var context: Context
    private lateinit var store: FakeDomainSettingsStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        store = FakeDomainSettingsStore()
        XRepo.installStoreForTest(store)
        XRepo.init(context)
        ConversationRepo.init(context)
    }

    @After
    fun tearDown() = runTest {
        ConversationRepo.closeForTest()
        XRepo.resetForTest()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun send_collectsTextAndToolCallsInStreamOrder() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(stream = { query ->
                assertEquals("hello", query)
                flowOf(
                    LlmStreamEvent.RoundStarted,
                    LlmStreamEvent.TextDelta(delta = "he", fullText = "he"),
                    LlmStreamEvent.ToolRunning(ToolCallStatus(name = "search", label = "Search")),
                    LlmStreamEvent.ToolSucceeded(ToolCallStatus(name = "search", label = "Search")),
                    LlmStreamEvent.TextDelta(delta = "llo", fullText = "hello"),
                    LlmStreamEvent.ToolRunning(
                        ToolCallStatus(
                            callId = "calc-1",
                            name = "calc",
                            label = "Calc"
                        )
                    ),
                    LlmStreamEvent.ToolSucceeded(
                        ToolCallStatus(
                            callId = "calc-1",
                            name = "calc",
                            label = "Calc"
                        )
                    ),
                    LlmStreamEvent.Completed(fullText = "hello back"),
                )
            }),
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("  hello  "))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals("", state.input)
        assertFalse(state.isGenerating)
        assertEquals(1, state.turns.size)
        val summary = conversations.listConversations().single()
        assertEquals(summary.id, state.currentConversationId)
        assertEquals("hello", state.currentConversationTitle)
        val turn = state.turns.single()
        assertEquals("hello", turn.userText)
        assertEquals(
            listOf(
                HomeChatBlock.Text("he"),
                HomeChatBlock.Tool(
                    HomeToolStatus(
                        name = "Search",
                        state = HomeToolState.Succeeded
                    )
                ),
                HomeChatBlock.Text("llo"),
                HomeChatBlock.Tool(
                    HomeToolStatus(
                        callId = "calc-1",
                        name = "Calc",
                        state = HomeToolState.Succeeded
                    )
                ),
                HomeChatBlock.Text(" back"),
            ),
            turn.blocks,
        )
    }

    @Test
    fun newConversation_clearsUiStateAndResetsRuntime() = runTest {
        var resetCalled = false
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = { flowOf(LlmStreamEvent.Completed("done")) },
                resetConversation = { resetCalled = true },
            ),
        )
        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        viewModel.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()

        assertTrue(resetCalled)
        val state = viewModel.uiStateFlow.value
        assertEquals("", state.input)
        assertFalse(state.isGenerating)
        assertTrue(state.turns.isEmpty())
        assertEquals(null, state.currentConversationId)
        assertEquals(null, state.currentConversationTitle)
    }

    @Test
    fun send_doesNotCreateVisibleFallbackWhenUnexpectedErrorHasNoMessage() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(stream = {
                flow {
                    throw RuntimeException()
                }
            }),
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(1, state.turns.size)
        assertFalse(state.turns.single().blocks.any { it is HomeChatBlock.Error })
        assertFalse(state.isGenerating)
    }

    @Test
    fun send_appendsErrorBlockWhenStreamReportsError() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(stream = {
                flowOf(LlmStreamEvent.Error("network failed"))
            }),
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(1, state.turns.size)
        assertEquals(
            listOf(HomeChatBlock.Error("network failed")),
            state.turns.single().blocks,
        )
        assertFalse(state.isGenerating)
    }

    @Test
    fun send_ignoresSecondSendWhileGenerating() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(stream = {
                flow {
                    emit(LlmStreamEvent.RoundStarted)
                    awaitCancellation()
                }
            }),
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("first"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.InputChanged("second"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertTrue(state.isGenerating)
        assertEquals("second", state.input)
        assertEquals(1, state.turns.size)
        assertEquals("first", state.turns.single().userText)

        viewModel.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()
    }

    @Test
    fun stopGenerating_keepsPartialAssistantMessageAndAllowsNextSend() = runTest {
        var sentQueries = emptyList<String>()
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(stream = { query ->
                sentQueries = sentQueries + query
                flow {
                    emit(LlmStreamEvent.RoundStarted)
                    emit(LlmStreamEvent.TextDelta(delta = "partial", fullText = "partial"))
                    awaitCancellation()
                }
            }),
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("first"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.StopGenerating)
        runCurrent()

        val stoppedState = viewModel.uiStateFlow.value
        assertFalse(stoppedState.isGenerating)
        assertEquals(
            listOf(HomeChatBlock.Text("partial")),
            stoppedState.turns.single().blocks,
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("second"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()

        val nextState = viewModel.uiStateFlow.value
        assertTrue(nextState.isGenerating)
        assertEquals(listOf("first", "second"), sentQueries)
        assertEquals(2, nextState.turns.size)

        viewModel.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()
    }

    @Test
    fun completed_persistsRuntimeHistoryAndLastOpenedConversationId() = runTest {
        val history = listOf(
            ChatTurn.User("hello"),
            ChatTurn.Assistant("hello back"),
        )
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = { flowOf(LlmStreamEvent.Completed(fullText = "hello back")) },
                getHistory = { history },
            ),
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        val summary = conversations.listConversations().single()
        val record = conversations.getConversation(summary.id)!!
        assertEquals(summary.id, conversations.lastOpenedConversationId())
        val state = viewModel.uiStateFlow.value
        assertEquals(summary.id, state.currentConversationId)
        assertEquals("hello", state.currentConversationTitle)
        assertEquals(history, record.history)
        assertEquals("", record.draftText)
        assertEquals("hello back", record.summary.lastMessagePreview)
    }

    @Test
    fun newConversation_keepsPersistedConversationButClearsCurrentPointer() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = { flowOf(LlmStreamEvent.Completed(fullText = "answer")) },
                getHistory = { listOf(ChatTurn.User("hello"), ChatTurn.Assistant("answer")) },
            ),
        )
        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()
        val conversationId = conversations.listConversations().single().id

        viewModel.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()

        assertEquals("", conversations.lastOpenedConversationId())
        assertEquals(conversationId, conversations.getConversation(conversationId)?.summary?.id)
        val state = viewModel.uiStateFlow.value
        assertTrue(state.turns.isEmpty())
        assertEquals(null, state.currentConversationId)
        assertEquals(null, state.currentConversationTitle)
    }

    @Test
    fun startupRestore_restoresHistoryTurnsAndDraft() = runTest {
        val history = listOf(ChatTurn.User("hello"), ChatTurn.Assistant("answer"))
        val conversations = FakeHomeConversationStore()
        val conversationId = conversations.createConversation("hello")
        conversations.saveHistory(conversationId, history)
        conversations.updateDraft(conversationId, "draft")
        conversations.setLastOpenedConversationId(conversationId)
        var replacedHistory: List<ChatTurn>? = null

        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = { flowOf() },
                replaceHistory = { replacedHistory = it },
            ),
        )
        advanceUntilIdle()

        assertEquals(history, replacedHistory)
        val state = viewModel.uiStateFlow.value
        assertEquals("draft", state.input)
        assertEquals(conversationId, state.currentConversationId)
        assertEquals("hello", state.currentConversationTitle)
        assertEquals(1, state.turns.size)
        assertEquals("hello", state.turns.single().userText)
        assertEquals(listOf(HomeChatBlock.Text("answer")), state.turns.single().blocks)
    }

    @Test
    fun loadConversation_restoresHistoryButDoesNotRestoreDraft() = runTest {
        val conversations = FakeHomeConversationStore()
        val firstId = conversations.createConversation("first")
        conversations.saveHistory(firstId, listOf(ChatTurn.User("first"), ChatTurn.Assistant("one")))
        conversations.setLastOpenedConversationId(firstId)
        val secondHistory = listOf(ChatTurn.User("second"), ChatTurn.Assistant("two"))
        val secondId = conversations.createConversation("second")
        conversations.saveHistory(secondId, secondHistory)
        conversations.updateDraft(secondId, "must not restore")
        var replacedHistory = emptyList<ChatTurn>()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = { flowOf() },
                replaceHistory = { replacedHistory = it },
            ),
        )
        advanceUntilIdle()

        viewModel.sendIntent(HomeChatIntent.LoadConversation(secondId))
        advanceUntilIdle()

        assertEquals(secondHistory, replacedHistory)
        assertEquals(secondId, conversations.lastOpenedConversationId())
        val state = viewModel.uiStateFlow.value
        assertEquals("", state.input)
        assertEquals(secondId, state.currentConversationId)
        assertEquals("second", state.currentConversationTitle)
        assertEquals("second", state.turns.single().userText)
        assertEquals(listOf(HomeChatBlock.Text("two")), state.turns.single().blocks)
    }

    @Test
    fun deleteCurrentConversation_deletesRecordAndClearsCurrentState() = runTest {
        var resetCalled = false
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = { flowOf(LlmStreamEvent.Completed(fullText = "answer")) },
                getHistory = { listOf(ChatTurn.User("hello"), ChatTurn.Assistant("answer")) },
                resetConversation = { resetCalled = true },
            ),
        )
        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()
        val conversationId = conversations.listConversations().single().id

        viewModel.sendIntent(HomeChatIntent.DeleteConversation(conversationId))
        advanceUntilIdle()

        assertTrue(resetCalled)
        assertEquals(null, conversations.getConversation(conversationId))
        assertEquals("", conversations.lastOpenedConversationId())
        val state = viewModel.uiStateFlow.value
        assertEquals("", state.input)
        assertFalse(state.isGenerating)
        assertTrue(state.turns.isEmpty())
        assertEquals(null, state.currentConversationId)
        assertEquals(null, state.currentConversationTitle)
    }
}

private class FakeHomeChatRuntime(
    private val stream: (String) -> Flow<LlmStreamEvent>,
    private val resetConversation: suspend () -> Unit = {},
    private val stopCurrentRound: suspend (Boolean) -> Unit = {},
    private val getHistory: suspend () -> List<ChatTurn> = { emptyList() },
    private val replaceHistory: suspend (List<ChatTurn>) -> Unit = {},
) : HomeChatRuntime {
    override fun stream(query: String): Flow<LlmStreamEvent> = stream.invoke(query)
    override suspend fun resetConversation() = resetConversation.invoke()
    override suspend fun stopCurrentRound(keepCurrentTurn: Boolean) =
        stopCurrentRound.invoke(keepCurrentTurn)

    override suspend fun getHistory(): List<ChatTurn> = getHistory.invoke()
    override suspend fun replaceHistory(history: List<ChatTurn>) = replaceHistory.invoke(history)
}

private class FakeHomeConversationStore : HomeConversationStore {
    private val records = linkedMapOf<String, ConversationRecord>()
    private var nextId = 0
    private var lastOpenedId = ""

    override suspend fun lastOpenedConversationId(): String = lastOpenedId

    override suspend fun setLastOpenedConversationId(value: String) {
        lastOpenedId = value.trim()
    }

    override suspend fun createConversation(firstUserInput: String): String {
        val id = "conversation-${nextId++}"
        val now = nextId.toLong()
        records[id] = ConversationRecord(
            summary = ConversationSummary(
                id = id,
                title = ConversationFormatter.titleFromFirstInput(firstUserInput),
                titleEdited = false,
                createdAt = now,
                updatedAt = now,
                lastMessagePreview = ConversationFormatter.previewFromText(firstUserInput),
                turnCount = 0,
            ),
            draftText = "",
            history = emptyList(),
        )
        return id
    }

    override suspend fun getConversation(id: String): ConversationRecord? = records[id]

    override suspend fun saveHistory(conversationId: String, history: List<ChatTurn>) {
        val record = records[conversationId] ?: return
        records[conversationId] = record.copy(
            summary = record.summary.copy(
                updatedAt = record.summary.updatedAt + 1,
                lastMessagePreview = ConversationFormatter.previewFromHistory(history),
                turnCount = history.count { it !is ChatTurn.System },
            ),
            history = history.filterNot { it is ChatTurn.System },
        )
    }

    override suspend fun updateDraft(conversationId: String, draftText: String) {
        val record = records[conversationId] ?: return
        records[conversationId] = record.copy(draftText = draftText)
    }

    override suspend fun deleteConversation(id: String) {
        records.remove(id)
    }

    override suspend fun forkConversation(sourceId: String, keepTurnCount: Int): String {
        val source = records[sourceId] ?: throw IllegalStateException("Source conversation not found: $sourceId")
        val newId = "conversation-${nextId++}"
        val truncated = source.history.take(keepTurnCount)
        records[newId] = ConversationRecord(
            summary = source.summary.copy(
                id = newId,
                titleEdited = true,
                lastMessagePreview = ConversationFormatter.previewFromHistory(truncated),
                turnCount = truncated.size,
            ),
            draftText = "",
            history = truncated,
        )
        return newId
    }

    fun listConversations(): List<ConversationSummary> {
        return records.values.map { it.summary }.sortedByDescending { it.updatedAt }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private class FakeDomainSettingsStore : DomainSettingsStore {
    private val values = mutableMapOf(
        StoreDescriptorRegistry.APP_STATE_ID to AppStateSettingsCodec.encode(AppStateSettings()),
    )

    override suspend fun readJson(context: Context, storeId: String): String {
        return values[storeId]
            ?: StoreDescriptorRegistry.resolveDynamic(storeId)?.defaultJson
            ?: "{}"
    }

    override suspend fun writeJsonFromOwner(context: Context, storeId: String, json: String): Boolean {
        values[storeId] = json
        return true
    }

    override suspend fun mutateJson(context: Context, storeId: String, path: String, value: Any?): Boolean {
        return false
    }
}

private const val DB_NAME = "conversation_history.db"
