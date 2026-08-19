package com.niki914.nexus.agentic.chat

import android.content.Context
import com.niki914.kai.ChatTurn
import com.niki914.nexus.agentic.chat.util.SilentLoggerRule
import com.niki914.nexus.agentic.runtime.settings.model.LlmApiType
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig
import com.niki914.okia.Okia
import com.niki914.okia.OkiaDependencies
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.AgentLoop
import com.niki914.okia.loop.CompletionReason
import com.niki914.okia.loop.LoopRequest
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.mcp.McpCallResult
import com.niki914.okia.mcp.McpClient
import com.niki914.okia.mcp.McpContentBlock
import com.niki914.okia.mcp.McpDiscoveredTool
import com.niki914.okia.mcp.McpServer
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.protocol.DeepSeekCompat
import com.niki914.okia.protocol.ProtocolCompatMapper
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.SseLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class LLMControllerOkiaTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @Before
    fun setUp() {
        LLMController.resetForTest()
    }

    @After
    fun tearDown() {
        LLMController.resetForTest()
    }

    // ── 装配：apiType → 协议 ────────────────────────────────────────────────

    @Test
    fun refresh_passesDeepSeekApiTypeToFactory() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig(provider = "deepseek"))
        )
        val capturedApiTypes = mutableListOf<LlmApiType>()
        LLMController.okiaFactory = LLMController.OkiaFactory { apiType, _, _ ->
            capturedApiTypes += apiType
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)))
        }

        LLMController.refresh()

        assertEquals(listOf(LlmApiType.DeepSeek), capturedApiTypes)
    }

    // ── stream：文本流与终态 ─────────────────────────────────────────────────

    @Test
    fun stream_mapsTextStreamAndCompletion() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )
        val loop = stubLoop(
            events = listOf(
                TurnEvent.TurnStarted("hello"),
                TurnEvent.TextDelta(0, "hi", AssistantMessage(listOf(ContentBlock.Text("hi")))),
                TurnEvent.TurnCompleted(AssistantMessage(listOf(ContentBlock.Text("hi")))),
            ),
            result = TurnResult.Completed(CompletionReason.Stop),
        )
        LLMController.okiaFactory = LLMController.OkiaFactory { _, _, _ -> openOkiaWithStubLoop(loop) }

        val events = LLMController.stream("hello", mockContext()).toList()

        assertEquals(LlmStreamEvent.RoundStarted, events[0])
        assertEquals("hi", (events[1] as LlmStreamEvent.TextDelta).delta)
        assertEquals(LlmStreamEvent.Completed("hi"), events[2])
    }

    @Test
    fun stream_mapsTurnFailedToErrorEvent() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )
        val loop = stubLoop(
            events = listOf(
                TurnEvent.TurnStarted("q"),
                TurnEvent.TurnFailed(AssistantMessage(emptyList()), com.niki914.okia.error.LLMError(com.niki914.okia.error.LLMErrorCode.Transport, "boom")),
            ),
            result = TurnResult.Failed(com.niki914.okia.error.LLMError(com.niki914.okia.error.LLMErrorCode.Transport, "boom")),
        )
        LLMController.okiaFactory = LLMController.OkiaFactory { _, _, _ -> openOkiaWithStubLoop(loop) }

        val events = LLMController.stream("hello", mockContext()).toList()

        val error = events.first { it is LlmStreamEvent.Error } as LlmStreamEvent.Error
        assertEquals("boom", error.message)
    }

    @Test
    fun stream_propagatesSystemPromptIntoRequestSnapshot() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig(prompt = "Base"))
        )
        val capturedSnapshots = mutableListOf<com.niki914.okia.protocol.RequestSnapshot>()
        val loop = object : AgentLoop {
            override suspend fun run(request: LoopRequest, onEvent: suspend (TurnEvent) -> Unit): TurnResult {
                capturedSnapshots += request.snapshot
                onEvent(TurnEvent.TurnCompleted(AssistantMessage(listOf(ContentBlock.Text("ok")))))
                return TurnResult.Completed(CompletionReason.Stop)
            }
        }
        LLMController.okiaFactory = LLMController.OkiaFactory { _, _, _ -> openOkiaWithStubLoop(loop) }

        LLMController.stream("hello", mockContext()).toList()

        val snapshot = capturedSnapshots.single()
        assertTrue(snapshot.systemPrompt.orEmpty().contains("Base"))
    }

    // ── 并发：活跃回合中二次 send → TurnConflict ────────────────────────────

    @Test
    fun stream_concurrentSendEmitsTurnConflict() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val blockingLoop = object : AgentLoop {
            override suspend fun run(request: LoopRequest, onEvent: suspend (TurnEvent) -> Unit): TurnResult {
                entered.complete(Unit)
                gate.await()
                return TurnResult.Completed(CompletionReason.Stop)
            }
        }
        LLMController.okiaFactory = LLMController.OkiaFactory { _, _, _ -> openOkiaWithStubLoop(blockingLoop) }

        val firstJob = launch { LLMController.stream("q1", mockContext()).toList() }
        entered.await()
        // 第二个并发 send：OKIA 活跃回合契约抛 IllegalStateException → TurnConflict
        val secondEvents = LLMController.stream("q2", mockContext()).toList()
        val error = secondEvents.first { it is LlmStreamEvent.Error } as LlmStreamEvent.Error
        assertEquals(LlmErrorCode.TurnConflict, error.code)

        gate.complete(Unit)
        firstJob.join()
    }

    // ── 历史桥接 ────────────────────────────────────────────────────────────

    @Test
    fun getHistory_projectsOkiaTreeToChatTurns() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )
        val loop = object : AgentLoop {
            override suspend fun run(request: LoopRequest, onEvent: suspend (TurnEvent) -> Unit): TurnResult {
                onEvent(TurnEvent.TurnStarted("hello"))
                onEvent(TurnEvent.TextDelta(0, "hi", AssistantMessage(listOf(ContentBlock.Text("hi")))))
                // 真实 loop 会把 assistant 提交进树；模拟 onCommit 以见到历史投影
                request.onCommit.invoke(
                    listOf(Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("hi")))))
                )
                onEvent(TurnEvent.TurnCompleted(AssistantMessage(listOf(ContentBlock.Text("hi")))))
                return TurnResult.Completed(CompletionReason.Stop)
            }
        }
        LLMController.okiaFactory = LLMController.OkiaFactory { _, _, _ -> openOkiaWithStubLoop(loop) }

        LLMController.stream("hello", mockContext()).toList()

        val history = LLMController.getHistory()
        assertEquals(2, history.size)
        assertEquals(ChatTurn.User("hello"), history[0])
        assertEquals(ChatTurn.Assistant(content = "hi"), history[1])
    }

    @Test
    fun replaceHistory_rebuildsSessionWithGivenHistory() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )
        LLMController.okiaFactory = LLMController.OkiaFactory { _, restore, _ ->
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)), restore)
        }

        LLMController.refresh()
        LLMController.replaceHistory(
            listOf(ChatTurn.User("a"), ChatTurn.Assistant("b"))
        )

        val history = LLMController.getHistory()
        assertEquals(listOf(ChatTurn.User("a"), ChatTurn.Assistant("b")), history)
    }

    @Test
    fun resetConversation_rebuildsEmptySession() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )
        LLMController.okiaFactory = LLMController.OkiaFactory { _, restore, _ ->
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)), restore)
        }

        LLMController.refresh()
        LLMController.resetConversation()

        assertTrue(LLMController.getHistory().isEmpty())
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun validLlmConfig(
        provider: String = "deepseek",
        prompt: String = "Base prompt",
    ): RuntimeLlmConfig {
        return RuntimeLlmConfig(
            provider = provider,
            endpoint = "https://example.com/v1",
            model = "deepseek-chat",
            prompt = prompt,
        )
    }

    private fun mockContext(): Context = mock(Context::class.java).apply {
        `when`(getString(com.niki914.nexus.agentic.runtime.R.string.error_llm_request_failed))
            .thenReturn("Request failed")
    }

    private fun stubLoop(
        events: List<TurnEvent>,
        result: TurnResult,
    ): AgentLoop = object : AgentLoop {
        override suspend fun run(request: LoopRequest, onEvent: suspend (TurnEvent) -> Unit): TurnResult {
            events.forEach { onEvent(it) }
            return result
        }
    }

    private suspend fun openOkiaWithStubLoop(
        loop: AgentLoop,
        restore: SessionSnapshot? = null,
    ): Okia =
        Okia.open(
            object : OkiaDependencies {
                override val agentLoop = loop
                override val protocolMapper = FakeMapper
                override val mcpClient = NoopMcpClient
            },
            restore = restore,
        ) {
            endpoint = "https://example.com/v1"
            apiKey = "test-key"
        }

    private object FakeMapper : ProtocolCompatMapper {
        override val compat = DeepSeekCompat()

        override suspend fun buildRequest(
            snapshot: com.niki914.okia.protocol.RequestSnapshot,
            history: List<Message>,
        ): HttpRequest = HttpRequest(
            url = snapshot.endpoint,
            method = "POST",
            headers = emptyMap(),
            body = null,
            timeouts = HttpTimeouts(connectMs = 1000, readMs = 1000, writeMs = 1000),
        )

        override suspend fun encodeToolResult(call: ContentBlock.ToolCall, outcome: ToolCallOutcome): Message =
            Message.ToolResult(call.id, call.name, outcome)

        override fun parseStream(rawSseLines: Flow<SseLine>): Flow<ProtocolEvent> = emptyFlow()

        override fun useApiKey(apiKey: String): Map<String, String> = emptyMap()
    }

    private object NoopMcpClient : McpClient {
        override suspend fun discoverTools(server: McpServer): List<McpDiscoveredTool> = emptyList()
        override suspend fun callTool(
            server: McpServer,
            toolName: String,
            argumentsJson: String,
        ): McpCallResult = McpCallResult(isError = false, content = emptyList())
    }
}