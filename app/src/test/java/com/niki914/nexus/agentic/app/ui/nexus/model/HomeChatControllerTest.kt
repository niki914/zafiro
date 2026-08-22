package com.niki914.nexus.agentic.app.ui.nexus.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.niki914.nexus.agentic.app.conversation.ConversationFormatter
import com.niki914.nexus.agentic.app.conversation.ConversationRecord
import com.niki914.nexus.agentic.app.conversation.ConversationRepo
import com.niki914.nexus.agentic.app.conversation.ConversationSummary
import com.niki914.nexus.agentic.app.conversation.ForkKind
import com.niki914.nexus.agentic.chat.LlmStreamEvent
import com.niki914.nexus.agentic.chat.ToolCallStatus
import com.niki914.nexus.agentic.repo.AppStateSettings
import com.niki914.nexus.agentic.repo.AppStateSettingsCodec
import com.niki914.nexus.agentic.repo.DomainSettingsStore
import com.niki914.nexus.agentic.repo.XRepo
import com.niki914.nexus.store.StoreDescriptorRegistry
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
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
import org.junit.runner.Description
import org.junit.runner.RunWith
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
    fun send_clearsPreviousErrorBlocksWhenStartingNewTurn() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(stream = { query ->
                if (query == "hello") {
                    flowOf(LlmStreamEvent.Error("network failed"))
                } else {
                    flowOf(
                        LlmStreamEvent.RoundStarted,
                        LlmStreamEvent.TextDelta(delta = "ok", fullText = "ok"),
                        LlmStreamEvent.Completed("ok"),
                    )
                }
            }),
        )

        // 第一轮：出错，错误卡片出现
        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()
        assertEquals(
            listOf(HomeChatBlock.Error("network failed")),
            viewModel.uiStateFlow.value.turns.single().blocks,
        )

        // 第二轮：发新消息，旧错误卡片消失，新 turn 正常流式
        viewModel.sendIntent(HomeChatIntent.InputChanged("again"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(2, state.turns.size)
        assertEquals(
            "旧 turn 的错误卡片应在新一轮发起时清除",
            emptyList<HomeChatBlock>(),
            state.turns[0].blocks,
        )
        assertEquals(
            listOf(HomeChatBlock.Text("ok")),
            state.turns[1].blocks,
        )
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
    fun stopGenerating_finalizesRunningToolsToFailedWithInterruptedReason() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(stream = {
                flow {
                    emit(LlmStreamEvent.RoundStarted)
                    emit(LlmStreamEvent.ToolRunning(ToolCallStatus(name = "search", label = "Search")))
                    emit(LlmStreamEvent.ToolRunning(ToolCallStatus(callId = "c1", name = "calc", label = "Calc")))
                    awaitCancellation()
                }
            }),
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.StopGenerating)
        runCurrent()

        val state = viewModel.uiStateFlow.value
        assertFalse(state.isGenerating)
        val toolBlocks = state.turns.single().blocks.filterIsInstance<HomeChatBlock.Tool>()
        assertEquals(2, toolBlocks.size)
        toolBlocks.forEach { tool ->
            assertEquals(HomeToolState.Failed, tool.status.state)
            assertEquals(
                HomeChatViewModel.FAILED_REASON_INTERRUPTED,
                tool.status.failedReason,
            )
        }

        viewModel.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()
    }

    @Test
    fun stopGenerating_stopsTurnAndClearsGenerating() = runTest {
        val conversations = FakeHomeConversationStore()
        var stopCalled = false
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = {
                    flow {
                        emit(LlmStreamEvent.RoundStarted)
                        emit(LlmStreamEvent.TextDelta(delta = "partial", fullText = "partial"))
                        awaitCancellation()
                    }
                },
                stopCurrentRound = { stopCalled = true },
            ),
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.StopGenerating)
        advanceUntilIdle()

        assertTrue(stopCalled)
        assertFalse(viewModel.uiStateFlow.value.isGenerating)
        assertTrue(conversations.lastOpenedConversationId().isNotBlank())

        viewModel.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()
    }

    @Test
    fun updateTool_matchesCorrectBlockWithMixedCallIds() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(stream = {
                flowOf(
                    LlmStreamEvent.RoundStarted,
                    LlmStreamEvent.ToolRunning(ToolCallStatus(name = "search", label = "Search")),
                    LlmStreamEvent.ToolRunning(ToolCallStatus(callId = "c1", name = "search", label = "Search")),
                    LlmStreamEvent.ToolSucceeded(ToolCallStatus(callId = "c1", name = "search", label = "Search")),
                    LlmStreamEvent.Completed(fullText = ""),
                )
            }),
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        val toolBlocks =
            viewModel.uiStateFlow.value.turns.single().blocks.filterIsInstance<HomeChatBlock.Tool>()
        assertEquals(2, toolBlocks.size)
        val nullCallId = toolBlocks.first { it.status.callId == null }
        val hasCallId = toolBlocks.first { it.status.callId == "c1" }
        assertEquals(HomeToolState.Running, nullCallId.status.state)
        assertEquals(HomeToolState.Succeeded, hasCallId.status.state)

        viewModel.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()
    }

    @Test
    fun completed_keepsConversationAndLastOpenedId() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = { flowOf(LlmStreamEvent.Completed(fullText = "hello back")) },
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
        assertEquals("", record.draftText)
    }

    @Test
    fun newConversation_keepsPersistedConversationButClearsCurrentPointer() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = { flowOf(LlmStreamEvent.Completed(fullText = "answer")) },
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
    fun startupRestore_opensSnapshotAndRestoresTurnsAndDraft() = runTest {
        val conversations = FakeHomeConversationStore()
        val conversationId = "session-restore"
        conversations.createConversation(conversationId, "hello")
        conversations.setSnapshot(
            conversationId,
            snapshotOf(
                Message.User(listOf(ContentBlock.Text("hello"))),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("answer")))),
            ),
        )
        conversations.updateDraft(conversationId, "draft")
        conversations.setLastOpenedConversationId(conversationId)
        var openedSnapshot: SessionSnapshot? = null

        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = { flowOf() },
                openSession = { openedSnapshot = it },
            ),
        )
        advanceUntilIdle()

        assertEquals(conversationId, openedSnapshot?.id)
        val state = viewModel.uiStateFlow.value
        assertEquals("draft", state.input)
        assertEquals(conversationId, state.currentConversationId)
        assertEquals("hello", state.currentConversationTitle)
        assertEquals(1, state.turns.size)
        assertEquals("hello", state.turns.single().userText)
        assertEquals(listOf(HomeChatBlock.Text("answer")), state.turns.single().blocks)
    }

    @Test
    fun loadConversation_stopsThenOpensSnapshot() = runTest {
        val conversations = FakeHomeConversationStore()
        val firstId = "session-first"
        conversations.createConversation(firstId, "first")
        conversations.setSnapshot(
            firstId,
            snapshotOf(
                Message.User(listOf(ContentBlock.Text("first"))),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("one")))),
            ),
        )
        conversations.setLastOpenedConversationId(firstId)
        val secondId = "session-second"
        conversations.createConversation(secondId, "second")
        conversations.setSnapshot(
            secondId,
            snapshotOf(
                Message.User(listOf(ContentBlock.Text("second"))),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("two")))),
            ),
        )
        conversations.updateDraft(secondId, "must not restore")
        var stopCount = 0
        var openedSnapshot: SessionSnapshot? = null
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = { flowOf() },
                stopCurrentRound = { stopCount++ },
                openSession = { openedSnapshot = it },
            ),
        )
        advanceUntilIdle()

        viewModel.sendIntent(HomeChatIntent.LoadConversation(secondId))
        advanceUntilIdle()

        assertTrue(stopCount >= 1)
        assertEquals(secondId, openedSnapshot?.id)
        assertEquals(secondId, conversations.lastOpenedConversationId())
        val state = viewModel.uiStateFlow.value
        assertEquals("", state.input)
        assertEquals(secondId, state.currentConversationId)
        assertEquals("second", state.currentConversationTitle)
        assertEquals("second", state.turns.single().userText)
        assertEquals(listOf(HomeChatBlock.Text("two")), state.turns.single().blocks)
    }

    @Test
    fun forkAt_createsForkSessionWithTruncatedSubtree() = runTest {
        val conversations = FakeHomeConversationStore()
        val sourceId = "session-fork-src"
        conversations.createConversation(sourceId, "first")
        conversations.setSnapshot(
            sourceId,
            snapshotOf(
                Message.User(listOf(ContentBlock.Text("first"))),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("one")))),
                Message.User(listOf(ContentBlock.Text("second"))),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("two")))),
            ),
        )
        conversations.setLastOpenedConversationId(sourceId)
        val opened = mutableListOf<SessionSnapshot>()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = { flowOf() },
                historySnapshot = {
                    listOf(
                        Message.User(listOf(ContentBlock.Text("first"))),
                        Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("one")))),
                        Message.User(listOf(ContentBlock.Text("second"))),
                        Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("two")))),
                    )
                },
                openSession = { opened.add(it) },
            ),
        )
        advanceUntilIdle()

        viewModel.sendIntent(HomeChatIntent.ForkAt(0))
        advanceUntilIdle()

        val newSnapshot = opened.last()
        // fork 在第一个 User 后分支：保留第一条 User + 其回答（2 条），标题加 Fork 前缀
        assertEquals(2, newSnapshot.entries.size)
        assertEquals("first", (newSnapshot.entries.first().message as Message.User)
            .content.filterIsInstance<ContentBlock.Text>().map { it.text }.joinToString("\n"))
        val newRecord = conversations.getConversation(newSnapshot.id)!!
        assertTrue(newRecord.summary.title.startsWith("Fork ·"))
        assertEquals(newSnapshot.id, conversations.lastOpenedConversationId())
    }

    @Test
    fun reGenerateAt_forksAndResendsTheSameQuery() = runTest {
        val conversations = FakeHomeConversationStore()
        val sourceId = "session-regen-src"
        conversations.createConversation(sourceId, "first")
        conversations.setSnapshot(
            sourceId,
            snapshotOf(
                Message.User(listOf(ContentBlock.Text("first"))),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("one")))),
                Message.User(listOf(ContentBlock.Text("second"))),
            ),
        )
        conversations.setLastOpenedConversationId(sourceId)
        var lastQuery: String? = null
        val opened = mutableListOf<SessionSnapshot>()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = { query ->
                    lastQuery = query
                    flowOf(LlmStreamEvent.Completed(fullText = "regenerated"))
                },
                historySnapshot = {
                    listOf(
                        Message.User(listOf(ContentBlock.Text("first"))),
                        Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("one")))),
                        Message.User(listOf(ContentBlock.Text("second"))),
                    )
                },
                openSession = { opened.add(it) },
            ),
        )
        advanceUntilIdle()

        viewModel.sendIntent(HomeChatIntent.ReGenerateAt(0))
        advanceUntilIdle()

        val newSnapshot = opened.last()
        assertEquals("first", lastQuery)
        // regen 截断到第一条 User 之前（丢弃该轮及后续，重新生成）：保留 0 条
        assertEquals(0, newSnapshot.entries.size)
        val newRecord = conversations.getConversation(newSnapshot.id)!!
        assertTrue(newRecord.summary.title.startsWith("Regenerate ·"))
    }

    @Test
    fun deleteCurrentConversation_deletesRecordAndClearsCurrentState() = runTest {
        var resetCalled = false
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(
                stream = { flowOf(LlmStreamEvent.Completed(fullText = "answer")) },
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
    private val stopCurrentRound: suspend () -> Unit = {},
    private val ensureSession: suspend () -> String = { "fake-session-1" },
    private val openSession: suspend (SessionSnapshot) -> Unit = {},
    private val historySnapshot: suspend () -> List<Message> = { emptyList() },
) : HomeChatRuntime {
    override fun stream(query: String): Flow<LlmStreamEvent> = stream.invoke(query)
    override suspend fun resetConversation() = resetConversation.invoke()
    override suspend fun stopCurrentRound() = stopCurrentRound.invoke()

    override suspend fun ensureSession(): String = ensureSession.invoke()
    override suspend fun openSession(restore: SessionSnapshot) = openSession.invoke(restore)
    override suspend fun historySnapshot(): List<Message> = historySnapshot.invoke()
}

private open class FakeHomeConversationStore : HomeConversationStore {
    private val records = linkedMapOf<String, ConversationRecord>()
    private var lastOpenedId = ""

    override suspend fun lastOpenedConversationId(): String = lastOpenedId

    override suspend fun setLastOpenedConversationId(value: String) {
        lastOpenedId = value.trim()
    }

    override suspend fun createConversation(id: String, firstUserInput: String) {
        val now = records.size.toLong() + 1
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
            snapshot = SessionSnapshot(id = id, leafId = null, version = 1, entries = emptyList()),
        )
    }

    override suspend fun getConversation(id: String): ConversationRecord? = records[id]

    override suspend fun updateDraft(conversationId: String, draftText: String) {
        val record = records[conversationId] ?: return
        records[conversationId] = record.copy(draftText = draftText)
    }

    override suspend fun deleteConversation(id: String) {
        records.remove(id)
    }

    override suspend fun forkConversation(
        sourceId: String,
        keepEntryCount: Int,
        kind: ForkKind,
    ): String {
        val source = records[sourceId]
            ?: throw IllegalStateException("Source conversation not found: $sourceId")
        val newId = "fork-${records.size + 1}"
        val projected = ConversationFormatter.projectLeaf(
            source.snapshot.entries,
            source.snapshot.leafId,
        )
        val truncated = projected.take(keepEntryCount)
        val prefix = when (kind) {
            ForkKind.Fork -> "Fork · "
            ForkKind.Regenerate -> "Regenerate · "
        }
        records[newId] = ConversationRecord(
            summary = source.summary.copy(
                id = newId,
                title = prefix + source.summary.title,
                titleEdited = true,
                lastMessagePreview = ConversationFormatter.previewFromEntries(truncated),
                turnCount = truncated.size,
            ),
            draftText = "",
            snapshot = SessionSnapshot(
                id = newId,
                leafId = truncated.lastOrNull()?.id,
                version = 1,
                entries = truncated,
            ),
        )
        return newId
    }

    fun setSnapshot(id: String, snapshot: SessionSnapshot) {
        val record = records[id] ?: return
        // 真实 Repo.getConversation 组装 snapshot.id = conversationId，Fake 对齐
        records[id] = record.copy(snapshot = snapshot.copy(id = id))
    }

    fun listConversations(): List<ConversationSummary> {
        return records.values.map { it.summary }.sortedByDescending { it.updatedAt }
    }
}

private fun snapshotOf(vararg messages: Message): SessionSnapshot {
    var parent: String? = null
    val entries = messages.mapIndexed { index, message ->
        val entry = ConversationEntry(
            id = "entry-$index",
            parentId = parent,
            timestamp = 1000L + index,
            message = message,
        )
        parent = entry.id
        entry
    }
    return SessionSnapshot(
        id = "session-snapshot",
        leafId = entries.lastOrNull()?.id,
        version = 1,
        entries = entries,
    )
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
        return true
    }
}

private const val DB_NAME = "test-conversation.db"
