package com.niki914.nexus.agentic.chat

import android.content.Context
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.nexus.agentic.chat.util.SilentLoggerRule
import com.niki914.nexus.agentic.runtime.settings.model.LlmApiType
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeBuiltinToolSetting
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool
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
import org.junit.Assert.assertNotNull
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

    @Test
    fun refresh_registersOnlyEnabledLocalTools() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                llmConfig = validLlmConfig(),
                builtinTools = listOf(
                    RuntimeBuiltinToolSetting("terminal", "t", enabled = true),
                    RuntimeBuiltinToolSetting("memory", "m", enabled = false),
                ),
                customTools = listOf(
                    RuntimeCustomTool("custom_x", "dx", "echo", enabled = true),
                    RuntimeCustomTool("custom_y", "dy", "echo", enabled = false),
                ),
            )
        )
        LLMController.okiaFactory = LLMController.OkiaFactory { apiType, restore, config ->
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)))
        }

        LLMController.refresh()

        // D25 注册装配：refresh 后 registry 只含启用的本地工具
        val names = LLMController.toolRegistry.snapshot()
            .map { it.descriptor.name }
            .toSet()
        assertEquals(setOf("terminal", "custom_x"), names)
    }

    @Test
    fun refresh_iconicCustomToolsAreRegisteredAsLocalWithSchema() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                llmConfig = validLlmConfig(),
                builtinTools = listOf(
                    RuntimeBuiltinToolSetting("terminal", "t", enabled = true),
                ),
            )
        )
        LLMController.okiaFactory = LLMController.OkiaFactory { apiType, restore, config ->
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)))
        }

        LLMController.refresh()

        val terminal = LLMController.toolRegistry.snapshot()
            .firstOrNull { it.descriptor.name == "terminal" }
        assertNotNull(terminal)
        // 内置工具携带 inputSchemaJson（D25 描述合法性）；kind = Local
        assertNotNull(terminal!!.descriptor.inputSchemaJson)
        assertEquals(com.niki914.okia.tooling.ToolKind.Local, terminal.descriptor.kind)
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

    // ── T3 会话生命周期 ───────────────────────────────────────────────────

    @Test
    fun ensureSession_createsInstanceAndReturnsTreeId() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )
        LLMController.okiaFactory = LLMController.OkiaFactory { _, restore, _ ->
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)), restore)
        }

        val id = LLMController.ensureSession()

        assertTrue(id.isNotBlank())
        assertEquals(id, LLMController.currentConversation.value?.id)
    }

    @Test
    fun openSession_rebuildsInstanceFromSnapshot() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )
        LLMController.okiaFactory = LLMController.OkiaFactory { _, restore, _ ->
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)), restore)
        }
        LLMController.refresh()
        val snapshot = SessionSnapshot(
            id = "session-restored",
            leafId = "e1",
            version = 1,
            entries = listOf(
                ConversationEntry(
                    id = "e0",
                    parentId = null,
                    timestamp = 1L,
                    message = Message.User(listOf(ContentBlock.Text("a"))),
                ),
                ConversationEntry(
                    id = "e1",
                    parentId = "e0",
                    timestamp = 2L,
                    message = Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("b")))),
                ),
            ),
        )

        LLMController.openSession(snapshot)

        assertEquals("session-restored", LLMController.currentConversation.value?.id)
        val texts = LLMController.historySnapshot().map { message ->
            when (message) {
                is Message.User -> message.content
                    .filterIsInstance<ContentBlock.Text>().map { it.text }.joinToString("\n")
                is Message.Assistant -> message.message.content
                    .filterIsInstance<ContentBlock.Text>().map { it.text }.joinToString("\n")
                is Message.ToolResult -> ""
            }
        }
        assertEquals(listOf("a", "b"), texts)
    }

    @Test
    fun resetConversation_discardsInstanceAndEmitsNull() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )
        LLMController.okiaFactory = LLMController.OkiaFactory { _, restore, _ ->
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)), restore)
        }
        LLMController.refresh()
        assertTrue(LLMController.currentConversation.value != null)

        LLMController.resetConversation()

        assertTrue(LLMController.currentConversation.value == null)
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