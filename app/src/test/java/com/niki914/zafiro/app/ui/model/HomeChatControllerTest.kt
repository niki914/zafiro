package com.niki914.zafiro.app.ui.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.app.conversation.ConversationFormatter
import com.niki914.zafiro.app.conversation.ConversationRecord
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.conversation.ConversationSummary
import com.niki914.zafiro.app.conversation.ForkKind
import com.niki914.zafiro.chat.LlmStreamEvent
import com.niki914.zafiro.chat.ToolCallStatus
import com.niki914.zafiro.chat.runtime.CommandCaller
import com.niki914.zafiro.chat.runtime.CommandSurface
import com.niki914.zafiro.chat.runtime.ConversationEngine
import com.niki914.zafiro.chat.runtime.ConversationOperation
import com.niki914.zafiro.chat.runtime.ConversationOperationBackend
import com.niki914.zafiro.chat.runtime.ConversationRuntime
import com.niki914.zafiro.chat.runtime.ExecutionOwner
import com.niki914.zafiro.chat.runtime.OperationOutcome
import com.niki914.zafiro.chat.runtime.OperationReceipt
import com.niki914.zafiro.chat.runtime.RuntimeFactSink
import com.niki914.zafiro.chat.runtime.TurnInput
import com.niki914.zafiro.chat.runtime.TurnKey
import com.niki914.zafiro.repo.AppStateSettings
import com.niki914.zafiro.repo.AppStateSettingsCodec
import com.niki914.zafiro.repo.DomainSettingsStore
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HomeChatViewModelTest {
    @get:Rule
    val mainDispatcherRule =
        MainDispatcherRule(
            UnconfinedTestDispatcher()
        )

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
        val conversations =
            FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, 
                stream = { query, _ ->
                    assertEquals("hello", query)
                    flowOf(
                        LlmStreamEvent.RoundStarted,
                        LlmStreamEvent.TextDelta(delta = "he", fullText = "he"),
                        LlmStreamEvent.ToolRunning(
                            ToolCallStatus(
                                name = "search",
                                label = "Search"
                            )
                        ),
                        LlmStreamEvent.ToolSucceeded(
                            ToolCallStatus(
                                name = "search",
                                label = "Search"
                            )
                        ),
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
                        LlmStreamEvent.Completed,
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
            ),
            turn.blocks,
        )
    }

    @Test
    fun newConversation_clearsUiStateAndResetsRuntime() = runTest {
        val conversations =
            FakeHomeConversationStore()
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ -> flowOf(LlmStreamEvent.Completed) },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )
        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        viewModel.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()

        assertEquals(1, runtime.resetCount)
        val state = viewModel.uiStateFlow.value
        assertEquals("", state.input)
        assertFalse(state.isGenerating)
        assertTrue(state.turns.isEmpty())
        assertEquals(null, state.currentConversationId)
        assertEquals(null, state.currentConversationTitle)
    }

    @Test
    fun imageAttached_ingestsAndAddsToPendingImages() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, stream = { _, _ -> flowOf(LlmStreamEvent.Completed) }),
        )

        viewModel.sendIntent(HomeChatIntent.ImageAttached("content://media/1"))
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertEquals(1, state.pendingImages.size)
        assertEquals("/tmp/content://media/1.jpg", state.pendingImages.single().path)
    }

    @Test
    fun imageRemoved_removesOnlyTargetImage() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, stream = { _, _ -> flowOf(LlmStreamEvent.Completed) }),
        )

        viewModel.sendIntent(HomeChatIntent.ImageAttached("content://media/1"))
        viewModel.sendIntent(HomeChatIntent.ImageAttached("content://media/2"))
        advanceUntilIdle()
        val idToRemove = viewModel.uiStateFlow.value.pendingImages.first().id

        viewModel.sendIntent(HomeChatIntent.ImageRemoved(idToRemove))
        runCurrent()

        val remaining = viewModel.uiStateFlow.value.pendingImages
        assertEquals(1, remaining.size)
        assertEquals("/tmp/content://media/2.jpg", remaining.single().path)
    }

    @Test
    fun send_movesPendingImagesIntoTurnAndClearsPending() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, stream = { _, _ -> flowOf(LlmStreamEvent.Completed) }),
        )

        viewModel.sendIntent(HomeChatIntent.ImageAttached("content://media/1"))
        viewModel.sendIntent(HomeChatIntent.ImageAttached("content://media/2"))
        advanceUntilIdle()
        val attached = viewModel.uiStateFlow.value.pendingImages

        viewModel.sendIntent(HomeChatIntent.InputChanged("look"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertTrue(state.pendingImages.isEmpty())
        assertEquals(1, state.turns.size)
        assertEquals(attached, state.turns.single().images)
        assertEquals("look", state.turns.single().userText)
    }

    @Test
    fun send_withImagesOnly_streamsAndRecordsTurn() = runTest {
        var streamed = false
        var streamedImages: List<ContentBlock.Image> = emptyList()
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, stream = { _, images ->
                streamed = true
                streamedImages = images
                flowOf(LlmStreamEvent.Completed)
            }),
        )

        viewModel.sendIntent(HomeChatIntent.ImageAttached("content://media/1"))
        advanceUntilIdle()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        // 纯图片发送照常发流，且图片块真到达 stream（Okia.send 空文本 + 图片块）
        assertFalse(viewModel.uiStateFlow.value.isGenerating)
        assertEquals(1, viewModel.uiStateFlow.value.turns.size)
        assertEquals(1, viewModel.uiStateFlow.value.turns.single().images.size)
        assertTrue(streamed)
        assertEquals(1, streamedImages.size)
    }

    @Test
    fun imageAttached_ingestFailure_isSilentlyDropped() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, 
                stream = { _, _ -> flowOf(LlmStreamEvent.Completed) },
                ingestImage = { null },
            ),
        )

        viewModel.sendIntent(HomeChatIntent.ImageAttached("content://media/broken"))
        advanceUntilIdle()

        assertTrue(viewModel.uiStateFlow.value.pendingImages.isEmpty())
    }

    @Test
    fun newConversation_clearsPendingImages() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, stream = { _, _ -> flowOf(LlmStreamEvent.Completed) }),
        )

        viewModel.sendIntent(HomeChatIntent.ImageAttached("content://media/1"))
        advanceUntilIdle()
        viewModel.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()

        assertTrue(viewModel.uiStateFlow.value.pendingImages.isEmpty())
    }

    @Test
    fun send_doesNotCreateVisibleFallbackWhenUnexpectedErrorHasNoMessage() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, stream = { _, _ ->
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
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, stream = { _, _ ->
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
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, stream = { query, _ ->
                if (query == "hello") {
                    flowOf(LlmStreamEvent.Error("network failed"))
                } else {
                    flowOf(
                        LlmStreamEvent.RoundStarted,
                        LlmStreamEvent.TextDelta(delta = "ok", fullText = "ok"),
                        LlmStreamEvent.Completed,
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
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, stream = { _, _ ->
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
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, stream = { query, _ ->
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
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, stream = { _, _ ->
                flow {
                    emit(LlmStreamEvent.RoundStarted)
                    emit(
                        LlmStreamEvent.ToolRunning(
                            ToolCallStatus(
                                name = "search",
                                label = "Search"
                            )
                        )
                    )
                    emit(
                        LlmStreamEvent.ToolRunning(
                            ToolCallStatus(
                                callId = "c1",
                                name = "calc",
                                label = "Calc"
                            )
                        )
                    )
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
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ ->
                flow {
                    emit(LlmStreamEvent.RoundStarted)
                    emit(LlmStreamEvent.TextDelta(delta = "partial", fullText = "partial"))
                    awaitCancellation()
                }
            },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.StopGenerating)
        advanceUntilIdle()

        // 停止按钮：引擎先停（scoped stopTurn），返回后本地包装取消；非全局 stop
        assertEquals(listOf(runtime.lastSubmittedKey), runtime.engine.stoppedTurns)
        // engine.stopTurn 调用时本地 wrapper 尚未 cancel → false
        assertEquals(listOf(false), runtime.engine.stoppedTurnCancelled)
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
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, stream = { _, _ ->
                flowOf(
                    LlmStreamEvent.RoundStarted,
                    LlmStreamEvent.ToolRunning(ToolCallStatus(name = "search", label = "Search")),
                    LlmStreamEvent.ToolRunning(
                        ToolCallStatus(
                            callId = "c1",
                            name = "search",
                            label = "Search"
                        )
                    ),
                    LlmStreamEvent.ToolSucceeded(
                        ToolCallStatus(
                            callId = "c1",
                            name = "search",
                            label = "Search"
                        )
                    ),
                    LlmStreamEvent.Completed,
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
    fun toolPending_insertsPlaceholderAndToolRunningUpdatesInPlace() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, stream = { _, _ ->
                flowOf(
                    LlmStreamEvent.RoundStarted,
                    LlmStreamEvent.ToolPending(
                        ToolCallStatus(callId = "c1", name = "terminal", label = "terminal")
                    ),
                    LlmStreamEvent.ToolRunning(
                        ToolCallStatus(
                            callId = "c1",
                            name = "terminal",
                            label = "terminal",
                            argumentsJson = """{"command":"ls"}""",
                        )
                    ),
                    LlmStreamEvent.Completed,
                )
            }),
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        // 占位行被 ToolRunning 原地更新，不产生重复块
        val toolBlocks =
            viewModel.uiStateFlow.value.turns.single().blocks.filterIsInstance<HomeChatBlock.Tool>()
        assertEquals(1, toolBlocks.size)
        assertEquals("c1", toolBlocks.single().status.callId)
        assertEquals(HomeToolState.Running, toolBlocks.single().status.state)
        // inputText 经 ToolPresentation.inputOf 提取：terminal 取 command 字段
        assertEquals("ls", toolBlocks.single().status.inputText)

        viewModel.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()
    }

    @Test
    fun completed_keepsConversationAndLastOpenedId() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, 
                stream = { _, _ -> flowOf(LlmStreamEvent.Completed) },
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
    fun send_clearsExpandedUserActionRowOfPreviousTurn() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, 
                // 第一轮失败：最后一条 turn 是裸 user message（无 AI 回应），
                // 此时复制按钮因 isLastTurn 放开；第二轮发起后必须收起
                stream = { _, _ ->
                    flowOf(
                        LlmStreamEvent.RoundStarted,
                        LlmStreamEvent.Error(message = "boom", code = null),
                    )
                },
            ),
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("q1"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        viewModel.sendIntent(HomeChatIntent.ToggleActionRow(0, ActionSource.User))
        runCurrent()
        assertEquals(0L, viewModel.uiStateFlow.value.expandedActionTurnId)

        viewModel.sendIntent(HomeChatIntent.InputChanged("q2"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertNull(state.expandedActionTurnId)
        assertNull(state.expandedActionSource)
        // 旧回合的错误卡随新回合消失，仅剩两条 user turn
        assertEquals(2, state.turns.size)
        assertTrue(state.turns[0].blocks.isEmpty())
    }

    @Test
    fun reGenerateAt_clearsErrorBlocksOfOldTurns() = runTest {
        val conversations = FakeHomeConversationStore()
        val sourceId = "session-regen-clear"
        conversations.createConversation(sourceId, "first")
        conversations.setSnapshot(
            sourceId,
            snapshotOf(
                Message.User(listOf(ContentBlock.Text("first"))),
            ),
        )
        conversations.setLastOpenedConversationId(sourceId)
        var queryCount = 0
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, 
                stream = { _, _ ->
                    queryCount++
                    if (queryCount == 1) {
                        flowOf(LlmStreamEvent.Error(message = "boom", code = null))
                    } else {
                        flowOf(LlmStreamEvent.Completed)
                    }
                },
                historySnapshot = {
                    listOf(
                        Message.User(listOf(ContentBlock.Text("first"))),
                    )
                },
            ),
        )
        advanceUntilIdle()

        // 第一轮：直接对历史 turn regen，得到一条带 Error 的新 turn
        viewModel.sendIntent(HomeChatIntent.ReGenerateAt(0))
        advanceUntilIdle()
        val failed = viewModel.uiStateFlow.value.turns.last()
        assertTrue(failed.blocks.filterIsInstance<HomeChatBlock.Error>().isNotEmpty())

        // 第二轮 regen：旧 turn 的 Error 块随新回合发起消失
        viewModel.sendIntent(HomeChatIntent.ReGenerateAt(0))
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertTrue(state.turns.dropLast(1).all { turn ->
            turn.blocks.filterIsInstance<HomeChatBlock.Error>().isEmpty()
        })
        assertNull(state.expandedActionTurnId)
    }

    @Test
    fun newConversation_keepsPersistedConversationButClearsCurrentPointer() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, 
                stream = { _, _ -> flowOf(LlmStreamEvent.Completed) },
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

        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ -> flowOf() },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )
        advanceUntilIdle()

        assertEquals(conversationId, runtime.openedSnapshots.single().id)
        val state = viewModel.uiStateFlow.value
        assertEquals("draft", state.input)
        assertEquals(conversationId, state.currentConversationId)
        assertEquals("hello", state.currentConversationTitle)
        assertEquals(1, state.turns.size)
        assertEquals("hello", state.turns.single().userText)
        assertEquals(listOf(HomeChatBlock.Text("answer")), state.turns.single().blocks)
    }

    @Test
    fun loadConversation_withActiveTurn_stopsThenOpensCapturedSnapshot() = runTest {
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
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ ->
                flow {
                    emit(LlmStreamEvent.RoundStarted)
                    awaitCancellation()
                }
            },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )
        advanceUntilIdle()

        // 起一个悬挂回合，使 currentExecutionKey 非空
        viewModel.sendIntent(HomeChatIntent.InputChanged("q"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()
        val activeKey = runtime.lastSubmittedKey
        assertTrue(activeKey != null)

        viewModel.sendIntent(HomeChatIntent.LoadConversation(secondId))
        advanceUntilIdle()

        // 局部 cancel → scoped engine stop（捕获的旧身份）→ Switch（已读出的 snapshot）
        assertEquals(listOf(activeKey), runtime.engine.stoppedTurns)
        // load 先 local cancel 再 scoped engine stop → engine.stopTurn 时 wrapper 已取消
        assertEquals(listOf(true), runtime.engine.stoppedTurnCancelled)
        assertTrue(
            runtime.commandLog.indexOf("stopTurn") < runtime.commandLog.indexOf("switch")
        )
        assertEquals(secondId, runtime.openedSnapshots.last().id)
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
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ -> flowOf() },
            historySnapshot = {
                listOf(
                    Message.User(listOf(ContentBlock.Text("first"))),
                    Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("one")))),
                    Message.User(listOf(ContentBlock.Text("second"))),
                    Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("two")))),
                )
            },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )
        advanceUntilIdle()

        viewModel.sendIntent(HomeChatIntent.ForkAt(0))
        advanceUntilIdle()

        val newSnapshot = runtime.openedSnapshots.last()
        // fork 在第一个 User 后分支：保留第一条 User + 其回答（2 条），标题加 Fork 前缀
        assertEquals(2, newSnapshot.entries.size)
        assertEquals(
            "first", (newSnapshot.entries.first().message as Message.User)
            .content.filterIsInstance<ContentBlock.Text>().map { it.text }.joinToString("\n")
        )
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
        var regenImages: List<ContentBlock.Image> = emptyList()
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { query, images ->
                lastQuery = query
                regenImages = images
                flowOf(LlmStreamEvent.Completed)
            },
            historySnapshot = {
                listOf(
                    Message.User(
                        listOf(
                            ContentBlock.Text("first"),
                            ContentBlock.Image("/tmp/regen.jpg", "image/jpeg"),
                        )
                    ),
                    Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("one")))),
                    Message.User(listOf(ContentBlock.Text("second"))),
                )
            },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )
        advanceUntilIdle()

        viewModel.sendIntent(HomeChatIntent.ReGenerateAt(0))
        advanceUntilIdle()

        val newSnapshot = runtime.openedSnapshots.last()
        assertEquals("first", lastQuery)
        // regen 重发必须带原回合的图片（模型侧 + UI 侧），否则图片在重生成后丢失
        assertEquals(1, regenImages.size)
        assertEquals("/tmp/regen.jpg", regenImages.single().path)
        // regen 截断到第一条 User 之前（丢弃该轮及后续，重新生成）：保留 0 条
        assertEquals(0, newSnapshot.entries.size)
        val newRecord = conversations.getConversation(newSnapshot.id)!!
        assertTrue(newRecord.summary.title.startsWith("Regenerate ·"))
    }

    @Test
    fun deleteCurrentConversation_deletesRecordAndClearsCurrentState() = runTest {
        val conversations = FakeHomeConversationStore()
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ -> flowOf(LlmStreamEvent.Completed) },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )
        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()
        val conversationId = conversations.listConversations().single().id

        viewModel.sendIntent(HomeChatIntent.DeleteConversation(conversationId))
        advanceUntilIdle()

        assertEquals(1, runtime.resetCount)
        assertEquals(null, conversations.getConversation(conversationId))
        assertEquals("", conversations.lastOpenedConversationId())
        val state = viewModel.uiStateFlow.value
        assertEquals("", state.input)
        assertFalse(state.isGenerating)
        assertTrue(state.turns.isEmpty())
        assertEquals(null, state.currentConversationId)
        assertEquals(null, state.currentConversationTitle)
    }

    @Test
    fun send_textAfterToolBlock_isNotDroppedByPacer() = runTest {
        // 回归：工具块后新文本段 fullText 从新坐标开始，若不重置节流器，
        // 段长 ≤ 已放出字符数时整段被静默丢弃（trunk loss after tool blocks）
        val longText = "a".repeat(600)
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, 
                stream = { _, _ ->
                    flow {
                        emit(LlmStreamEvent.RoundStarted)
                        // 第一段：长文本，pacer 放出后 released 坐标远大于下一段
                        emit(
                            LlmStreamEvent.TextDelta(
                                delta = longText,
                                fullText = longText,
                                isSegmentStart = true,
                            )
                        )
                        emit(
                            LlmStreamEvent.ToolRunning(
                                ToolCallStatus(callId = "t1", name = "tool")
                            )
                        )
                        emit(
                            LlmStreamEvent.ToolSucceeded(
                                ToolCallStatus(callId = "t1", name = "tool")
                            )
                        )
                        // 工具后新文本段：短坐标（isSegmentStart = true）
                        emit(
                            LlmStreamEvent.TextDelta(
                                delta = "after tool",
                                fullText = "after tool",
                                isSegmentStart = true,
                            )
                        )
                        emit(LlmStreamEvent.Completed)
                    }
                },
            ),
        )
        viewModel.sendIntent(HomeChatIntent.InputChanged("q"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        val turn = viewModel.uiStateFlow.value.turns.single()
        val texts = turn.blocks.filterIsInstance<HomeChatBlock.Text>().map { it.text }
        assertEquals(listOf(longText, "after tool"), texts)
    }

    @Test
    fun thinking_autoExpandsWhileActive_manualCollapseSurvivesEchoEndKeepsState() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, 
                stream = { _, _ ->
                    flow {
                        emit(LlmStreamEvent.RoundStarted)
                        // 首发：块 0 开始（Mapper 只对 Started 发新块，Delta 续接重发同 id）
                        emit(LlmStreamEvent.ThinkingStarted(0, "one"))
                        delay(50)
                        // 续接回声（同 id）：手动收起后不复活
                        emit(LlmStreamEvent.ThinkingStarted(0, "one two"))
                        delay(50)
                        emit(LlmStreamEvent.ThinkingEnded(0, "one two"))
                        emit(LlmStreamEvent.Completed)
                    }
                },
            ),
        )
        viewModel.sendIntent(HomeChatIntent.InputChanged("q"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()
        runCurrent()

        // 首发：自动展开 + active 指针
        var state = viewModel.uiStateFlow.value
        assertEquals("0_0", state.activeThinkingKey)
        assertTrue("0_0" in state.expandedThinking)

        // 思考中手动收起：允许
        viewModel.sendIntent(HomeChatIntent.ToggleThinking(0, 0))
        runCurrent()
        state = viewModel.uiStateFlow.value
        assertFalse("0_0" in state.expandedThinking)

        // 续接回声到达：不重新撑开
        advanceTimeBy(50)
        runCurrent()
        state = viewModel.uiStateFlow.value
        assertFalse("0_0" in state.expandedThinking)

        // 块完成：摘 active（停滚动跟随）、块保持收起、回合结束
        advanceTimeBy(50)
        runCurrent()
        state = viewModel.uiStateFlow.value
        assertNull(state.activeThinkingKey)
        assertFalse("0_0" in state.expandedThinking)
        assertFalse(state.isGenerating)
    }

    @Test
    fun thinking_newBlockCollapsesPreviousAutoExpanded() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, 
                stream = { _, _ ->
                    flow {
                        emit(LlmStreamEvent.RoundStarted)
                        emit(LlmStreamEvent.ThinkingStarted(0, "first"))
                        delay(10)
                        emit(LlmStreamEvent.ThinkingStarted(1, "second"))
                        delay(10)
                        emit(LlmStreamEvent.Completed)
                    }
                },
            ),
        )
        viewModel.sendIntent(HomeChatIntent.InputChanged("q"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()
        runCurrent()

        // 首发：块 0 自动展开
        var state = viewModel.uiStateFlow.value
        assertTrue("0_0" in state.expandedThinking)
        assertFalse("0_1" in state.expandedThinking)

        // 新块到来：收起前面自动展开的块 0，展开块 1
        advanceTimeBy(10)
        runCurrent()
        state = viewModel.uiStateFlow.value
        assertTrue("0_1" in state.expandedThinking)
        assertFalse("0_0" in state.expandedThinking)
    }

    @Test
    fun thinking_userExpandedBlockSurvivesNewBlock() = runTest {
        val conversations = FakeHomeConversationStore()
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, 
                stream = { _, _ ->
                    flow {
                        emit(LlmStreamEvent.RoundStarted)
                        emit(LlmStreamEvent.ThinkingStarted(0, "first"))
                        delay(10)
                        emit(LlmStreamEvent.ThinkingStarted(1, "second"))
                        delay(10)
                        emit(LlmStreamEvent.ThinkingStarted(2, "third"))
                        delay(10)
                        emit(LlmStreamEvent.Completed)
                    }
                },
            ),
        )
        viewModel.sendIntent(HomeChatIntent.InputChanged("q"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()
        runCurrent()

        // 块 1 自动展开（块 0 已被自动收起）
        advanceTimeBy(10)
        runCurrent()
        var state = viewModel.uiStateFlow.value
        assertTrue("0_1" in state.expandedThinking)
        assertFalse("0_0" in state.expandedThinking)

        // 用户手动展开块 0（接管）
        viewModel.sendIntent(HomeChatIntent.ToggleThinking(0, 0))
        runCurrent()
        state = viewModel.uiStateFlow.value
        assertTrue("0_0" in state.expandedThinking)
        assertTrue("0_1" in state.expandedThinking)

        // 块 2 到来：只收仍自动展开的块 1，用户展开的块 0 保留
        advanceTimeBy(10)
        runCurrent()
        state = viewModel.uiStateFlow.value
        assertTrue("0_2" in state.expandedThinking)
        assertTrue("0_0" in state.expandedThinking)
        assertFalse("0_1" in state.expandedThinking)
    }

    @Test
    fun loadConversation_clearsTransientThinkingAndActionState() = runTest {
        val conversations = FakeHomeConversationStore()
        conversations.createConversation("session-second", "second")
        conversations.setSnapshot(
            "session-second",
            snapshotOf(
                Message.User(listOf(ContentBlock.Text("second"))),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("two")))),
            ),
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = FakeHomeChatRuntime(conversations, backgroundScope, 
                stream = { _, _ ->
                    flowOf(
                        LlmStreamEvent.RoundStarted,
                        LlmStreamEvent.ThinkingStarted(0, "one"),
                        LlmStreamEvent.ThinkingEnded(0, "one"),
                        LlmStreamEvent.Completed,
                    )
                },
            ),
        )
        viewModel.sendIntent(HomeChatIntent.InputChanged("q"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        // 当前会话：thinking 自动展开 + 操作行展开，瞬态非空
        viewModel.sendIntent(HomeChatIntent.ToggleActionRow(0, ActionSource.Agent))
        runCurrent()
        val before = viewModel.uiStateFlow.value
        assertTrue("0_0" in before.expandedThinking)
        assertTrue("0_0" in before.autoExpandedThinking)
        assertEquals(0L, before.expandedActionTurnId)

        // 切换会话：全部瞬态清理，不跨会话复用（loadConversation 曾漏清 expandedThinking）
        viewModel.sendIntent(HomeChatIntent.LoadConversation("session-second"))
        advanceUntilIdle()
        val after = viewModel.uiStateFlow.value
        assertEquals("session-second", after.currentConversationId)
        assertTrue(after.expandedThinking.isEmpty())
        assertTrue(after.autoExpandedThinking.isEmpty())
        assertTrue(after.expandedToolRuns.isEmpty())
        assertTrue(after.expandedToolResults.isEmpty())
        assertNull(after.expandedActionTurnId)
        assertNull(after.expandedActionSource)
        assertNull(after.activeThinkingKey)
    }

    // ── 迁移前后基线对照：Create 回执、身份寿命、pre-submit 取消 ───────────────

    @Test
    fun send_firstTurn_usesCreateReceiptAsPersistedId() = runTest {
        val conversations = FakeHomeConversationStore()
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ -> flowOf(LlmStreamEvent.Completed) },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        // Create 后端回执的持久化 ID 必须被 VM 原样用作 currentConversationId / lastOpened
        val persistedId = runtime.createdIds.single()
        assertEquals(persistedId, viewModel.uiStateFlow.value.currentConversationId)
        assertEquals(persistedId, conversations.lastOpenedConversationId())
        assertEquals(persistedId, conversations.listConversations().single().id)
    }

    @Test
    fun send_synchronousExecuteCompletesBeforeStreamJobAssignment_clearsExecutionKey() = runTest {
        // 内联 Main：viewModelScope.launch 返回前同步跑完协程体，确定性地让 wrapper 体
        // 先于 `streamJob = launch{}` 赋值完成（Group7 Fix-19 场景）。
        Dispatchers.setMain(InlineDispatcher())
        val conversations = FakeHomeConversationStore()
        val runtime = InlineHomeChatRuntime(conversations)
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()

        // 判别条件：同步 execute 已返回，且 wrapper 身份判定 finally 未复位 isGenerating，
        // 证明 body 在 streamJob 赋值之前完成（旧实现此处会残留 currentExecutionKey）。
        assertTrue(runtime.executeCompleted)
        assertTrue(
            "wrapper finally 未运行：body 先于 streamJob 赋值",
            viewModel.uiStateFlow.value.isGenerating,
        )

        // 新身份由 runLlmTurn 的 startedKey finally 清除，不依赖 streamJob 赋值
        viewModel.sendIntent(HomeChatIntent.NewConversation)
        runCurrent()
        assertTrue(
            "陈旧 key 不得触发 scoped engine stop",
            runtime.stoppedTurns.isEmpty(),
        )
        assertEquals(1, runtime.resetCount)
    }

    @Test
    fun stopGenerating_beforeSubmit_cancelsWrapperWithoutEngineStop() = runTest {
        val conversations = FakeHomeConversationStore()
        val gate = CompletableDeferred<Unit>()
        conversations.draftGate = gate
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ ->
                flow {
                    emit(LlmStreamEvent.RoundStarted)
                    awaitCancellation()
                }
            },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()

        // 已进入 wrapper（isGenerating），但卡在提交前的 updateDraft 挂起点：无执行身份
        assertTrue(viewModel.uiStateFlow.value.isGenerating)
        assertNull(runtime.lastSubmittedKey)

        viewModel.sendIntent(HomeChatIntent.StopGenerating)
        runCurrent()
        assertFalse(viewModel.uiStateFlow.value.isGenerating)
        // 无身份不降级为 global / engine stop
        assertTrue(runtime.engine.stoppedTurns.isEmpty())
        assertTrue(runtime.commandLog.none { it == "stop" || it == "stopTurn" })

        gate.complete(Unit)
        advanceUntilIdle()
        // 始终未进入 runLlmTurn
        assertNull(runtime.lastSubmittedKey)
        assertFalse(viewModel.uiStateFlow.value.isGenerating)
    }

    // ── 迁移前后基线对照：真实流失败、pacing、单次读库 ─────────────────────────

    @Test
    fun send_streamThrowsWithMessage_projectsErrorBlock() = runTest {
        val conversations = FakeHomeConversationStore()
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ ->
                flow {
                    emit(LlmStreamEvent.RoundStarted)
                    throw IllegalStateException("stream boom")
                }
            },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        // 真实流异常经 executor 完成回执在 await 点原样回到 VM catch
        val state = viewModel.uiStateFlow.value
        assertFalse(state.isGenerating)
        assertEquals(
            listOf(HomeChatBlock.Error("stream boom")),
            state.turns.single().blocks,
        )
    }

    @Test
    fun send_pacesIncrementalTextWithoutLoss() = runTest {
        val chunks = listOf("alpha ", "beta ", "gamma")
        var full = ""
        val conversations = FakeHomeConversationStore()
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ ->
                flow {
                    emit(LlmStreamEvent.RoundStarted)
                    chunks.forEach { chunk ->
                        full += chunk
                        emit(LlmStreamEvent.TextDelta(delta = chunk, fullText = full))
                    }
                    emit(LlmStreamEvent.Completed)
                }
            },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("hello"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        val textBlocks = viewModel.uiStateFlow.value.turns.single().blocks
            .filterIsInstance<HomeChatBlock.Text>()
        assertEquals(chunks.joinToString(""), textBlocks.single().text)
    }

    @Test
    fun loadConversation_readsStoreOnceAndPassesCapturedSnapshot() = runTest {
        val conversations = FakeHomeConversationStore()
        val conversationId = "session-single-read"
        conversations.createConversation(conversationId, "hello")
        conversations.setSnapshot(
            conversationId,
            snapshotOf(Message.User(listOf(ContentBlock.Text("hello")))),
        )
        conversations.setLastOpenedConversationId(conversationId)
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ -> flowOf() },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )
        advanceUntilIdle()

        // 冷启动恢复：Restore 复用已读 snapshot，读库一次
        assertEquals(1, conversations.getConversationCalls)
        val captured = conversations.getConversation(conversationId)!!.snapshot

        conversations.getConversationCalls = 0
        viewModel.sendIntent(HomeChatIntent.LoadConversation(conversationId))
        advanceUntilIdle()

        // Switch 后端不得二次读库；下发的是 VM 已读出的同一个 snapshot 实例
        assertEquals(1, conversations.getConversationCalls)
        assertSame(captured, runtime.openedSnapshots.last())
    }

    // ── 迁移前后基线对照：stop/new/load/delete-current 取消对象及次序 ────────

    @Test
    fun newConversation_withActiveTurn_stopsThenResets() = runTest {
        val conversations = FakeHomeConversationStore()
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ ->
                flow {
                    emit(LlmStreamEvent.RoundStarted)
                    awaitCancellation()
                }
            },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("q"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()
        val activeKey = runtime.lastSubmittedKey
        assertTrue(activeKey != null)

        viewModel.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()

        assertEquals(listOf(activeKey), runtime.engine.stoppedTurns)
        // new 先 local cancel 再 scoped engine stop → engine.stopTurn 时 wrapper 已取消
        assertEquals(listOf(true), runtime.engine.stoppedTurnCancelled)
        assertTrue(runtime.commandLog.indexOf("stopTurn") < runtime.commandLog.indexOf("reset"))
        assertEquals(1, runtime.resetCount)
        assertTrue(viewModel.uiStateFlow.value.turns.isEmpty())
        assertNull(viewModel.uiStateFlow.value.currentConversationId)
    }

    @Test
    fun deleteNonCurrentConversation_doesNotStopOrReset() = runTest {
        val conversations = FakeHomeConversationStore()
        conversations.createConversation("a", "a")
        conversations.createConversation("b", "b")
        conversations.setSnapshot("a", snapshotOf(Message.User(listOf(ContentBlock.Text("a")))))
        conversations.setLastOpenedConversationId("a")
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ ->
                flow {
                    emit(LlmStreamEvent.RoundStarted)
                    awaitCancellation()
                }
            },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )
        advanceUntilIdle()

        viewModel.sendIntent(HomeChatIntent.InputChanged("q"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()
        assertTrue(viewModel.uiStateFlow.value.isGenerating)

        viewModel.sendIntent(HomeChatIntent.DeleteConversation("b"))
        advanceUntilIdle()

        // 非当前会话删除：删记录但不触发 stop/reset，也不打断当前回合
        assertNull(conversations.getConversation("b"))
        assertEquals("a", viewModel.uiStateFlow.value.currentConversationId)
        assertTrue(viewModel.uiStateFlow.value.isGenerating)
        assertTrue(runtime.engine.stoppedTurns.isEmpty())
        assertEquals(0, runtime.resetCount)

        // 收尾：取消悬挂回合，避免测试结束时残留活跃执行
        viewModel.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()
    }

    @Test
    fun deleteCurrentConversation_withActiveTurn_stopsThenResets() = runTest {
        val conversations = FakeHomeConversationStore()
        val runtime = FakeHomeChatRuntime(
            conversations,
            backgroundScope,
            stream = { _, _ ->
                flow {
                    emit(LlmStreamEvent.RoundStarted)
                    awaitCancellation()
                }
            },
        )
        val viewModel = HomeChatViewModel(
            conversations = conversations,
            runtime = runtime,
        )

        viewModel.sendIntent(HomeChatIntent.InputChanged("q"))
        runCurrent()
        viewModel.sendIntent(HomeChatIntent.Send)
        runCurrent()
        val activeKey = runtime.lastSubmittedKey
        val conversationId = viewModel.uiStateFlow.value.currentConversationId
        assertTrue(activeKey != null)
        assertTrue(conversationId != null)
        val currentId = conversationId!!

        viewModel.sendIntent(HomeChatIntent.DeleteConversation(currentId))
        advanceUntilIdle()

        assertEquals(listOf(activeKey), runtime.engine.stoppedTurns)
        // delete-current 先 local cancel 再 scoped engine stop → engine.stopTurn 时 wrapper 已取消
        assertEquals(listOf(true), runtime.engine.stoppedTurnCancelled)
        assertTrue(runtime.commandLog.indexOf("stopTurn") < runtime.commandLog.indexOf("reset"))
        assertEquals(1, runtime.resetCount)
        assertNull(conversations.getConversation(currentId))
    }
}

private class FakeHomeChatEngine(
    private val stream: (String, List<ContentBlock.Image>) -> Flow<LlmStreamEvent>,
) : ConversationEngine {
    private val conversationState = MutableStateFlow<Conversation?>(null)

    override val conversation: StateFlow<Conversation?> get() = conversationState

    val stoppedTurns = mutableListOf<TurnKey>()

    /** engine.stopTurn 调用时该回合捕获 wrapper Job 的取消状态（stop 与本地 cancel 次序判别）。 */
    val stoppedTurnCancelled = mutableListOf<Boolean>()

    /** 由 [FakeHomeChatRuntime] 注入：TurnKey → 该回合 Submission 时的 owner.parentJob。 */
    var jobLookup: (TurnKey) -> Job? = { null }

    override fun stream(
        query: String,
        images: List<ContentBlock.Image>,
        observer: RuntimeFactSink,
    ): Flow<LlmStreamEvent> = stream.invoke(query, images)

    override suspend fun stopTurn(turn: TurnKey): Boolean {
        stoppedTurns += turn
        stoppedTurnCancelled += jobLookup(turn)?.isCancelled ?: false
        return true
    }
}

/**
 * HomeChat 运行时替身：内部构造真实 [ConversationRuntime]。
 * - 执行走真实 executor（submit + onStarted + await(handle)），真实失败/寿命交付，
 *   不伪造 TurnHandle、不反射置完成。
 * - 引擎与操作后端为可控替身；Create/Restore/Switch/Reset 直接落原 [FakeHomeConversationStore]，
 *   回执为后端实际分配/绑定的持久化 ID。
 * - 旧 `stream/resetConversation/stopCurrentRound/ensureSession/openSession` seam 已随
 *   Group7 迁移移除，这里按新 6 方法 seam 实现。
 */
private class FakeHomeChatRuntime(
    private val conversations: FakeHomeConversationStore,
    scope: CoroutineScope,
    private val stream: (String, List<ContentBlock.Image>) -> Flow<LlmStreamEvent>,
    private val historySnapshot: suspend () -> List<Message> = { emptyList() },
    private val ingestImage: suspend (String) -> HomeChatImage? = { uri ->
        HomeChatImage(id = "ingested-$uri", path = "/tmp/$uri.jpg")
    },
) : HomeChatRuntime {
    private val submittedJobs = mutableMapOf<TurnKey, Job>()

    val engine = FakeHomeChatEngine(stream).also { engine ->
        engine.jobLookup = { key -> submittedJobs[key] }
    }

    /** 命令/操作到达顺序，供 stop→switch/reset 次序断言。 */
    val commandLog = mutableListOf<String>()

    /** Create 后端实际回执的持久化 ID（VM 必须原样使用）。 */
    val createdIds = mutableListOf<String>()

    /** Restore/Switch 收到的原 [SessionSnapshot]（后端不得二次读库）。 */
    val openedSnapshots = mutableListOf<SessionSnapshot>()

    var resetCount = 0
        private set

    /** 最近一次提交的执行身份；未进入 runLlmTurn（pre-submit）时为 null。 */
    var lastSubmittedKey: TurnKey? = null
        private set

    private var sessionCounter = 0

    private val runtime = ConversationRuntime(
        scope = scope,
        engine = engine,
        operationBackend = ConversationOperationBackend { operation ->
            when (operation) {
                is ConversationOperation.Create -> {
                    val id = "fake-session-${++sessionCounter}"
                    createdIds += id
                    commandLog += "create"
                    conversations.createConversation(id, operation.firstUserInput)
                    OperationReceipt(persistedId = id)
                }

                is ConversationOperation.Restore -> {
                    operation.snapshot?.let { openedSnapshots += it }
                    commandLog += "restore"
                    OperationReceipt(persistedId = operation.persistedId)
                }

                is ConversationOperation.Switch -> {
                    operation.snapshot?.let { openedSnapshots += it }
                    commandLog += "switch"
                    OperationReceipt(persistedId = operation.persistedId)
                }

                ConversationOperation.Reset -> {
                    resetCount++
                    commandLog += "reset"
                    OperationReceipt()
                }
            }
        },
    )

    override suspend fun execute(
        input: TurnInput,
        owner: ExecutionOwner,
        onStarted: (TurnKey) -> Unit,
        output: suspend (LlmStreamEvent) -> Unit,
    ) {
        commandLog += "execute"
        val handle = runtime.submit(input = input, owner = owner, output = output)
        lastSubmittedKey = handle.key
        submittedJobs[handle.key] = owner.parentJob
        onStarted(handle.key)
        runtime.await(handle)
    }

    override suspend fun stop(turn: TurnKey) {
        commandLog += "stop"
        runtime.stop(turn, CommandCaller(CommandSurface.HomeChat))
    }

    override suspend fun stopTurn(turn: TurnKey) {
        commandLog += "stopTurn"
        runtime.stopTurn(turn)
    }

    override suspend fun operate(operation: ConversationOperation): OperationOutcome =
        runtime.operate(operation, CommandCaller(CommandSurface.HomeChat))

    override suspend fun historySnapshot(): List<Message> = historySnapshot.invoke()

    override suspend fun ingestImage(uri: String): HomeChatImage? = ingestImage.invoke(uri)
}

/**
 * 同步 [HomeChatRuntime] 替身（仅 immediate-key 用例）：execute 调 onStarted(key) 后
 * 同步返回，不发射事件、不挂起，配合 [InlineDispatcher] 让 wrapper 体在
 * `streamJob = launch{}` 赋值之前完成。
 *
 * 不构造 [ConversationRuntime]：executor 子协程调度会引入赋值次序抖动，
 * 而本用例只在 VM 自身的 seam 层验证身份清理与 streamJob 赋值无关。
 * 不伪造 TurnHandle、不反射置完成。
 */
private class InlineHomeChatRuntime(
    private val conversations: FakeHomeConversationStore,
) : HomeChatRuntime {
    val stoppedTurns = mutableListOf<TurnKey>()

    var resetCount = 0
        private set

    var lastKey: TurnKey? = null
        private set

    var executeCompleted = false
        private set

    private var counter = 0

    override suspend fun execute(
        input: TurnInput,
        owner: ExecutionOwner,
        onStarted: (TurnKey) -> Unit,
        output: suspend (LlmStreamEvent) -> Unit,
    ) {
        counter += 1
        val key = TurnKey(
            conversationId = "inline-session",
            turnId = "inline-turn-$counter",
            epoch = counter.toLong(),
        )
        lastKey = key
        onStarted(key)
        executeCompleted = true
    }

    override suspend fun stop(turn: TurnKey) {
        stoppedTurns += turn
    }

    override suspend fun stopTurn(turn: TurnKey) {
        stoppedTurns += turn
    }

    override suspend fun operate(operation: ConversationOperation): OperationOutcome =
        when (operation) {
            is ConversationOperation.Create -> {
                val id = "inline-session-$counter"
                conversations.createConversation(id, operation.firstUserInput)
                OperationOutcome.Succeeded(id)
            }

            is ConversationOperation.Restore -> OperationOutcome.Succeeded(operation.persistedId)

            is ConversationOperation.Switch -> OperationOutcome.Succeeded(operation.persistedId)

            ConversationOperation.Reset -> {
                resetCount += 1
                OperationOutcome.Succeeded()
            }
        }

    override suspend fun historySnapshot(): List<Message> = emptyList()

    override suspend fun ingestImage(uri: String): HomeChatImage? = null
}

/**
 * 内联调度器（仅 immediate-key 用例）：[isDispatchNeeded] 为 true 但 [dispatch]
 * 立即执行 block，使 `viewModelScope.launch` 在返回前同步跑完协程体，
 * 从而让 wrapper 体先于 `streamJob = launch{}` 赋值完成。
 * 不实现 [kotlinx.coroutines.Delay]，本用例不使用 delay。
 */
private class InlineDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

    override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
}

private open class FakeHomeConversationStore : HomeConversationStore {
    var shouldLoadLastConversationOnStartup: Boolean = true

    /** 非 null 时 [updateDraft] 挂起在此信号上（测试制造提交前的挂起点）。 */
    var draftGate: CompletableDeferred<Unit>? = null

    /** [getConversation] 调用计数（断言恢复/切换只读库一次）。 */
    var getConversationCalls: Int = 0

    override suspend fun loadLastConversationOnStartup(): Boolean =
        shouldLoadLastConversationOnStartup

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

    override suspend fun getConversation(id: String): ConversationRecord? {
        getConversationCalls++
        return records[id]
    }

    override suspend fun updateDraft(conversationId: String, draftText: String) {
        draftGate?.await()
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
            // Fake 与真实 Repo 对齐：ConversationRepo.forkConversation 用 rewindTitleFormat（默认 "Rewind · %1$s"）
            ForkKind.Rewind -> "Rewind · "
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

    override suspend fun writeJsonFromOwner(
        context: Context,
        storeId: String,
        json: String
    ): Boolean {
        values[storeId] = json
        return true
    }

    override suspend fun mutateJson(
        context: Context,
        storeId: String,
        path: String,
        value: Any?
    ): Boolean {
        return true
    }
}

private const val DB_NAME = "test-conversation.db"
