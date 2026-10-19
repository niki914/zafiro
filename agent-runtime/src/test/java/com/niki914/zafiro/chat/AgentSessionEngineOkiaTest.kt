// 保护：AgentSessionEngine 与 OKIA 会话生命周期、协议路由、热更新与流式事件契约
package com.niki914.zafiro.chat

import com.niki914.okia.Okia
import com.niki914.okia.OkiaDependencies
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.error.LLMError
import com.niki914.okia.error.LLMErrorCode
import com.niki914.okia.event.StopCause
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.AgentLoop
import com.niki914.okia.loop.CompletionReason
import com.niki914.okia.loop.LoopRequest
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.mcp.McpCallResult
import com.niki914.okia.mcp.McpClient
import com.niki914.okia.mcp.McpDiscoveredTool
import com.niki914.okia.mcp.McpServer
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.protocol.DeepSeekCompat
import com.niki914.okia.protocol.ProtocolCompatMapper
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.tooling.ToolKind
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.SseLine
import com.niki914.zafiro.chat.util.SilentLoggerRule
import com.niki914.zafiro.settings.model.LlmProtocol
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class AgentSessionEngineOkiaTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    private val engine = AgentSessionEngine()

    @Before
    fun setUp() {
        engine.resetForTest()
    }

    @After
    fun tearDown() {
        engine.resetForTest()
    }

    // ── 装配：protocol → 协议 ───────────────────────────────────────────────

    @Test
    fun refresh_passesDeepSeekProtocolToFactory() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                llmConfig = validLlmConfig(
                    provider = "deepseek",
                    protocol = LlmProtocol.DeepSeek.wireId,
                )
            )
        )
        val capturedProtocols = mutableListOf<LlmProtocol>()
        engine.okiaFactory = AgentSessionEngine.OkiaFactory { protocol, _, _ ->
            capturedProtocols += protocol
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)))
        }

        engine.refresh()

        assertEquals(listOf(LlmProtocol.DeepSeek), capturedProtocols)
    }

    @Test
    fun refresh_appliesTimeoutAndRetryToExistingSessionHot() = runTest {
        // 实例复用路径（协议不变）：改设置后 refresh() 应热更新 idle timeout / retry policy，
        // 否则改设置要冷启才生效（回归：曾只在新实例时写入）
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                llmConfig = validLlmConfig(idleTimeoutSeconds = 30L, retryMaxAttempts = 1),
            )
        )
        engine.okiaFactory = AgentSessionEngine.OkiaFactory { _, _, _ ->
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)))
        }

        engine.refresh()
        assertEquals(30L, engine.okia?.config()?.idleTimeoutSeconds)
        assertEquals(1, engine.okia?.config()?.retryPolicy?.maxAttempts)

        // 同一实例复用，仅改设置 → 热更新生效
        val gateway = installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                llmConfig = validLlmConfig(idleTimeoutSeconds = 90L, retryMaxAttempts = 5),
            )
        )
        gateway.llmConfig = validLlmConfig(idleTimeoutSeconds = 90L, retryMaxAttempts = 5)
        engine.refresh()

        assertEquals(90L, engine.okia?.config()?.idleTimeoutSeconds)
        assertEquals(5, engine.okia?.config()?.retryPolicy?.maxAttempts)
    }

    @Test
    fun refresh_registersOnlyEnabledLocalTools() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                llmConfig = validLlmConfig(),
                builtinTools = listOf(
                    RuntimeBuiltinToolSetting("shell", "t", enabled = true),
                    RuntimeBuiltinToolSetting("memory", "m", enabled = false),
                ),
                customPyTools = listOf(
                    RuntimeCustomPyTool(
                        name = "py_x",
                        code = "print('x')",
                        description = "dx",
                        enabled = true
                    ),
                    RuntimeCustomPyTool(
                        name = "py_y",
                        code = "print('y')",
                        description = "dy",
                        enabled = false
                    ),
                ),
            )
        )
        engine.okiaFactory = AgentSessionEngine.OkiaFactory { apiType, restore, config ->
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)))
        }

        engine.refresh()

        // D25 注册装配：refresh 后 registry 只含启用的本地工具
        val names = engine.toolRegistry.snapshot()
            .map { it.descriptor.name }
            .toSet()
        assertEquals(setOf("shell", "py_x"), names)
    }

    @Test
    fun refresh_enabledCustomPyToolsAreRegisteredAsLocalWithSchema() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                llmConfig = validLlmConfig(),
                builtinTools = listOf(
                    RuntimeBuiltinToolSetting("shell", "t", enabled = true),
                ),
            )
        )
        engine.okiaFactory = AgentSessionEngine.OkiaFactory { apiType, restore, config ->
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)))
        }

        engine.refresh()

        val shell = engine.toolRegistry.snapshot()
            .firstOrNull { it.descriptor.name == "shell" }
        assertNotNull(shell)
        // 内置工具携带 inputSchemaJson（D25 描述合法性）；kind = Local
        assertNotNull(shell!!.descriptor.inputSchemaJson)
        assertEquals(ToolKind.Local, shell.descriptor.kind)
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
        engine.okiaFactory =
            AgentSessionEngine.OkiaFactory { _, _, _ -> openOkiaWithStubLoop(loop) }

        val events = engine.stream("hello").toList()

        assertEquals(LlmStreamEvent.RoundStarted, events[0])
        assertEquals("hi", (events[1] as LlmStreamEvent.TextDelta).delta)
        assertEquals(LlmStreamEvent.Completed, events[2])
    }

    @Test
    fun stream_mapsTurnFailedToErrorEvent() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )
        val loop = stubLoop(
            events = listOf(
                TurnEvent.TurnStarted("q"),
                TurnEvent.TurnFailed(
                    AssistantMessage(emptyList()),
                    LLMError(
                        LLMErrorCode.Transport,
                        "boom"
                    )
                ),
            ),
            result = TurnResult.Failed(
                LLMError(
                    LLMErrorCode.Transport,
                    "boom"
                )
            ),
        )
        engine.okiaFactory =
            AgentSessionEngine.OkiaFactory { _, _, _ -> openOkiaWithStubLoop(loop) }

        val events = engine.stream("hello").toList()

        val error = events.first { it is LlmStreamEvent.Error } as LlmStreamEvent.Error
        assertEquals("boom", error.message)
    }

    @Test
    fun stream_propagatesSystemPromptIntoRequestSnapshot() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig(prompt = "Base"))
        )
        val capturedSnapshots = mutableListOf<RequestSnapshot>()
        val loop = object : AgentLoop {
            override suspend fun run(
                request: LoopRequest,
                onEvent: suspend (TurnEvent) -> Unit
            ): TurnResult {
                capturedSnapshots += request.snapshot
                onEvent(TurnEvent.TurnCompleted(AssistantMessage(listOf(ContentBlock.Text("ok")))))
                return TurnResult.Completed(CompletionReason.Stop)
            }
        }
        engine.okiaFactory =
            AgentSessionEngine.OkiaFactory { _, _, _ -> openOkiaWithStubLoop(loop) }

        engine.stream("hello").toList()

        val snapshot = capturedSnapshots.single()
        assertTrue(snapshot.systemPrompt.orEmpty().contains("Base"))
    }

    // ── 单次输出上限（maxTokens）：装配 + 热更新 ──────────────────────────

    @Test
    fun stream_appliesConfiguredMaxTokensToRequestSnapshot() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig(maxTokens = 64_000))
        )
        val capturedSnapshots = mutableListOf<RequestSnapshot>()
        val loop = object : AgentLoop {
            override suspend fun run(
                request: LoopRequest,
                onEvent: suspend (TurnEvent) -> Unit
            ): TurnResult {
                capturedSnapshots += request.snapshot
                return TurnResult.Completed(CompletionReason.Stop)
            }
        }
        engine.okiaFactory =
            AgentSessionEngine.OkiaFactory { _, _, _ -> openOkiaWithStubLoop(loop) }

        engine.stream("hello").toList()

        // 设置页填的输出上限必须原样进请求快照（不是 okia 骨架的 4096）
        assertEquals(64_000, capturedSnapshots.single().maxTokens)
    }

    @Test
    fun stream_hotUpdatesMaxTokensWithoutColdStart() = runTest {
        val gateway = installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig(maxTokens = 64_000))
        )
        val capturedSnapshots = mutableListOf<RequestSnapshot>()
        val loop = object : AgentLoop {
            override suspend fun run(
                request: LoopRequest,
                onEvent: suspend (TurnEvent) -> Unit
            ): TurnResult {
                capturedSnapshots += request.snapshot
                return TurnResult.Completed(CompletionReason.Stop)
            }
        }
        engine.okiaFactory =
            AgentSessionEngine.OkiaFactory { _, _, _ -> openOkiaWithStubLoop(loop) }

        engine.stream("hello").toList()
        // 改设置：同一会话实例复用（不冷启），下一轮请求必须看到新值
        gateway.llmConfig = gateway.llmConfig.copy(maxTokens = 32_000)
        engine.stream("again").toList()

        assertEquals(listOf(64_000, 32_000), capturedSnapshots.map { it.maxTokens })
    }

    // ── 终态：用户停止不发错误事件 ────────────────────────────────────────

    @Test
    fun stream_userStopDoesNotEmitErrorEvent() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )
        engine.okiaFactory = AgentSessionEngine.OkiaFactory { _, _, _ ->
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Aborted(StopCause.UserStop)))
        }

        val events = engine.stream("hello").toList()

        // 停止不是错误：终态由 Agent 按打断结算，这里不能补错误卡（旧守卫会补一张「内部错误」）
        assertTrue(events.none { it is LlmStreamEvent.Error })
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
            override suspend fun run(
                request: LoopRequest,
                onEvent: suspend (TurnEvent) -> Unit
            ): TurnResult {
                entered.complete(Unit)
                gate.await()
                return TurnResult.Completed(CompletionReason.Stop)
            }
        }
        engine.okiaFactory =
            AgentSessionEngine.OkiaFactory { _, _, _ -> openOkiaWithStubLoop(blockingLoop) }

        val firstJob = launch { engine.stream("q1").toList() }
        entered.await()
        // 第二个并发 send：OKIA 活跃回合契约抛 IllegalStateException → TurnConflict
        val secondEvents = engine.stream("q2").toList()
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
        engine.okiaFactory = AgentSessionEngine.OkiaFactory { _, restore, _ ->
            openOkiaWithStubLoop(
                stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)),
                restore
            )
        }

        val id = engine.ensureSession()

        assertTrue(id.isNotBlank())
        assertEquals(id, engine.currentConversation.value?.id)
    }

    @Test
    fun openSession_rebuildsInstanceFromSnapshot() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )
        engine.okiaFactory = AgentSessionEngine.OkiaFactory { _, restore, _ ->
            openOkiaWithStubLoop(
                stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)),
                restore
            )
        }
        engine.refresh()
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

        engine.openSession(snapshot)

        assertEquals("session-restored", engine.currentConversation.value?.id)
        val texts = engine.currentConversation.value?.history.orEmpty().map { entry ->
            when (val message = entry.message) {
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
    fun refresh_providerSwitchCarriesTreeViaRestore() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig(provider = "deepseek"))
        )
        val capturedRestores = mutableListOf<SessionSnapshot?>()
        engine.okiaFactory = AgentSessionEngine.OkiaFactory { _, restore, _ ->
            capturedRestores += restore
            openOkiaWithStubLoop(
                stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)),
                restore,
            )
        }
        val snapshot = SessionSnapshot(
            id = "session-continued",
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
        // 以 deepseek 载入会话（树有内容）
        engine.openSession(snapshot)

        // 切到 anthropic 再 refresh：应带 restore 延续同一棵树（id + entries 不变）
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig(provider = "anthropic"))
        )
        engine.refresh()

        val carried = capturedRestores.last()
        assertNotNull("provider switch must carry session snapshot", carried)
        assertEquals("session-continued", carried?.id)
        assertEquals(snapshot.entries.size, carried?.entries?.size)
    }

    @Test
    fun resetConversation_discardsInstanceAndEmitsNull() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(llmConfig = validLlmConfig())
        )
        engine.okiaFactory = AgentSessionEngine.OkiaFactory { _, restore, _ ->
            openOkiaWithStubLoop(
                stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)),
                restore
            )
        }
        engine.refresh()
        assertTrue(engine.currentConversation.value != null)

        engine.resetConversation()

        assertTrue(engine.currentConversation.value == null)
    }

    @Test
    fun openSession_afterConfigChanged_usesUpdatedConfig() = runTest {
        val gateway = FakeRuntimeSettingsGateway(
            llmConfig = validLlmConfig().copy(model = "model-v1")
        )
        installRuntimeSettingsGatewayForTest(gateway)
        var lastCreatedConfig: ResolvedLlmConfig? = null
        engine.okiaFactory = AgentSessionEngine.OkiaFactory { _, restore, config ->
            lastCreatedConfig = config
            openOkiaWithStubLoop(
                stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)),
                restore
            )
        }
        engine.refresh()
        assertEquals("model-v1", lastCreatedConfig?.model)

        // 外部新配置为 model-v2
        gateway.llmConfig = validLlmConfig().copy(model = "model-v2")

        val snapshot = SessionSnapshot(
            id = "session-restored",
            leafId = "e0",
            version = 1,
            entries = listOf(
                ConversationEntry(
                    id = "e0",
                    parentId = null,
                    timestamp = 1L,
                    message = Message.User(listOf(ContentBlock.Text("a"))),
                ),
            ),
        )
        engine.openSession(snapshot)

        assertEquals("model-v2", lastCreatedConfig?.model)
    }

    @Test
    fun refresh_withBoundConfig_passesConfigIdToGateway() = runTest {
        var requestedConfigId: String? = null
        val gateway = object : FakeRuntimeSettingsGateway() {
            override suspend fun readLlmConfig(
                agentId: String,
                configId: String?
            ): RuntimeLlmConfig {
                requestedConfigId = configId
                return validLlmConfig()
            }
        }
        installRuntimeSettingsGatewayForTest(gateway)

        val engine = AgentSessionEngine()
        engine.okiaFactory = AgentSessionEngine.OkiaFactory { _, _, _ ->
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)))
        }

        engine.bindConfig("cfg-my-bound")
        engine.refresh()
        assertEquals("cfg-my-bound", requestedConfigId)

        engine.bindConfig("")
        engine.refresh()
        assertNull(requestedConfigId)
    }

    @Test
    fun refresh_publishesConfiguredContextWindow() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(validLlmConfig(contextWindowTokens = 200_000L)),
        )

        val engine = AgentSessionEngine()
        engine.okiaFactory = AgentSessionEngine.OkiaFactory { _, _, _ ->
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)))
        }

        engine.refresh()

        assertEquals(200_000L, engine.contextWindowTokens.value)
    }

    @Test
    fun warmup_initializesSessionWhenNull() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(validLlmConfig()),
        )
        val testEngine = AgentSessionEngine()
        var factoryCalls = 0
        testEngine.okiaFactory = AgentSessionEngine.OkiaFactory { _, _, _ ->
            factoryCalls++
            openOkiaWithStubLoop(stubLoop(emptyList(), TurnResult.Completed(CompletionReason.Stop)))
        }

        assertNull(testEngine.okia)
        testEngine.warmup()
        assertEquals(1, factoryCalls)
        assertNotNull(testEngine.okia)

        // 重复调用不重新建会话
        testEngine.warmup()
        assertEquals(1, factoryCalls)
    }

    @Test
    fun warmup_ignoresMissingLlmConfigWithoutThrowing() = runTest {
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                validLlmConfig().copy(endpoint = "", model = ""),
            ),
        )
        val testEngine = AgentSessionEngine()
        // 不应抛出 LlmConfigRequiredException 崩溃
        testEngine.warmup()
        assertNull(testEngine.okia)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun validLlmConfig(
        provider: String = "deepseek",
        protocol: String = LlmProtocol.OpenAiResponses.wireId,
        prompt: String = "Base prompt",
        idleTimeoutSeconds: Long? = 60L,
        retryMaxAttempts: Int = 3,
        maxTokens: Int = 128_000,
        contextWindowTokens: Long = 1_000_000L,
    ): RuntimeLlmConfig {
        return RuntimeLlmConfig(
            provider = provider,
            protocol = protocol,
            endpoint = "https://example.com/v1",
            model = "deepseek-chat",
            prompt = prompt,
            idleTimeoutSeconds = idleTimeoutSeconds,
            retryMaxAttempts = retryMaxAttempts,
            maxTokens = maxTokens,
            contextWindowTokens = contextWindowTokens,
        )
    }


    private fun stubLoop(
        events: List<TurnEvent>,
        result: TurnResult,
    ): AgentLoop = object : AgentLoop {
        override suspend fun run(
            request: LoopRequest,
            onEvent: suspend (TurnEvent) -> Unit
        ): TurnResult {
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
            snapshot: RequestSnapshot,
            history: List<Message>,
        ): HttpRequest = HttpRequest(
            url = snapshot.endpoint,
            method = "POST",
            headers = emptyMap(),
            body = null,
            timeouts = HttpTimeouts(connectMs = 1000, readMs = 1000, writeMs = 1000),
        )

        override suspend fun encodeToolResult(
            call: ContentBlock.ToolCall,
            outcome: ToolCallOutcome
        ): Message =
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
