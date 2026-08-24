package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.util.SilentLoggerRule
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import com.niki914.okia.Okia
import com.niki914.okia.OkiaDependencies
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.AgentLoop
import com.niki914.okia.loop.CompletionReason
import com.niki914.okia.loop.LoopRequest
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.mcp.McpCallResult
import com.niki914.okia.mcp.McpClient
import com.niki914.okia.mcp.McpDiscoveredTool
import com.niki914.okia.mcp.McpServer
import com.niki914.okia.mcp.McpTransport
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.protocol.DeepSeekCompat
import com.niki914.okia.protocol.ProtocolCompatMapper
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.SseLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * T2b MCP 装配与发现时序（方案 B，D-T2B-3）：
 * - 装配：McpServerDefinition.Http → OkiaConfig.mcpServers（update 写入）
 * - 时序不变量：首次 refresh 恰好触发 1 次 discoverTools（启动 eager）；
 *   签名未变不刷；配置变化再刷；失败也更新签名（防风暴）
 * - 发现结果注册进 LLMController.toolRegistry（wireName = mcp__server__tool）
 * 全程 fake（RecordingMcpClient），无真实网络。
 */
class LLMControllerMcpTest {

    @get:Rule
    val silentLogger = _root_ide_package_.com.niki914.zafiro.chat.util.SilentLoggerRule()

    @Before
    fun setUp() {
        LLMController.resetForTest()
    }

    @After
    fun tearDown() {
        LLMController.resetForTest()
    }

    @Test
    fun refresh_triggersDiscoveryOnceOnFirstRefresh() = runTest {
        val recording = RecordingMcpClient(
            results = mapOf("server1" to listOf(McpDiscoveredTool("echo", "Echo", null))),
        )
        installGateway(server("server1", "http://127.0.0.1:3001/mcp"))
        LLMController.okiaFactory = okiaFactoryWith(recording)

        LLMController.refresh()

        recording.awaitDiscoveryCalls(1)
        assertEquals(listOf("server1"), recording.discoveredServers.map { it.name })
        // 发现结果注册进 registry（wireName 前缀 mcp__）
        val wireNames = LLMController.toolRegistry.snapshot().map { it.descriptor.wireName }
        assertTrue(wireNames.any { it.startsWith("mcp__server1__") })
    }

    @Test
    fun refresh_skipsDiscoveryWhenSignatureUnchanged() = runTest {
        val recording = RecordingMcpClient(
            results = mapOf("server1" to listOf(McpDiscoveredTool("echo", "Echo", null))),
        )
        installGateway(server("server1", "http://127.0.0.1:3001/mcp"))
        LLMController.okiaFactory = okiaFactoryWith(recording)

        LLMController.refresh()
        recording.awaitDiscoveryCalls(1)
        // 同配置再 refresh：签名未变 → 不触发
        LLMController.refresh()
        recording.awaitDiscoveryCalls(1)
    }

    @Test
    fun refresh_rediscoverWhenServerConfigChanged() = runTest {
        val recording = RecordingMcpClient(
            results = mapOf(
                "server1" to listOf(McpDiscoveredTool("echo", "Echo", null)),
                "server2" to listOf(McpDiscoveredTool("getWeather", "Weather", null)),
            ),
        )
        installGateway(server("server1", "http://127.0.0.1:3001/mcp"))
        LLMController.okiaFactory = okiaFactoryWith(recording)

        LLMController.refresh()
        recording.awaitDiscoveryCalls(1)

        // 服务器变化（新增 server2）→ 签名变化 → 重新发现
        installGateway(server("server1", "http://127.0.0.1:3001/mcp"), server("server2", "http://127.0.0.1:3002/mcp"))
        LLMController.refresh()
        recording.awaitDiscoveryCalls(2)
        assertEquals(setOf("server1", "server2"), recording.discoveredServers.map { it.name }.toSet())
    }

    @Test
    fun refresh_retriesDiscoveryAfterFailure() = runTest {
        // 问题 4 修复：失败不再记为成功——第一次失败后，退避重试直至成功，
        // 服务器暂时不可用不导致 MCP 整会话不可用。
        val recording = RecordingMcpClient(
            results = mapOf("server1" to listOf(McpDiscoveredTool("echo", "Echo", null))),
            failWith = IllegalStateException("connection refused"),
            failTimes = 1, // 第一次失败，之后成功
        )
        installGateway(server("server1", "http://127.0.0.1:3001/mcp"))
        LLMController.okiaFactory = okiaFactoryWith(recording)

        LLMController.refresh()
        recording.awaitDiscoveryCalls(1)
        assertTrue(recording.failures > 0)
        // 退避重试后成功：工具最终注册（失败不阻塞重试）
        recording.awaitDiscoveryCalls(2)
        val wireNames = LLMController.toolRegistry.snapshot().map { it.descriptor.wireName }
        assertTrue(wireNames.any { it.startsWith("mcp__server1__") })
    }

    @Test
    fun refresh_partialServerFailureDoesNotMarkSignatureSuccess() = runTest {
        // 问题 4：部分服务器失败（failedServers 非空）不算成功签名——
        // 退避重试发生，失败服务器不被永久跳过（旧行为：一次尝试后
        // 同签名永不重刷）。
        val recording = RecordingMcpClient(
            results = mapOf(
                "server1" to listOf(McpDiscoveredTool("echo", "Echo", null)),
                "server2" to listOf(McpDiscoveredTool("getWeather", "Weather", null)),
            ),
            failServerNames = setOf("server2"), // server2 持续失败
        )
        installGateway(
            server("server1", "http://127.0.0.1:3001/mcp"),
            server("server2", "http://127.0.0.1:3002/mcp"),
        )
        LLMController.okiaFactory = okiaFactoryWith(recording)

        LLMController.refresh()
        recording.awaitDiscoveryCalls(2) // 首轮全量刷（两台各一次）
        assertTrue(recording.failures > 0)
        // 退避重试：server2 失败后仍再刷（旧行为在首轮失败后即停止）
        recording.awaitDiscoveryCalls(4)

        // 部分成功也注册：server1 工具已进 registry
        val wireNames = LLMController.toolRegistry.snapshot().map { it.descriptor.wireName }
        assertTrue(wireNames.any { it.startsWith("mcp__server1__") })
    }

    @Test
    fun refresh_convertsServerConfigIntoOkiaMcpServer() = runTest {
        val recording = RecordingMcpClient(results = emptyMap())
        installGateway(
            server(
                "secure",
                "http://127.0.0.1:3001/mcp",
                enabled = true,
                headers = mapOf("Authorization" to "Bearer x"),
            ),
        )
        LLMController.okiaFactory = okiaFactoryWith(recording)

        LLMController.refresh()
        recording.awaitDiscoveryCalls(1)

        val seen = recording.discoveredServers.single()
        assertEquals("secure", seen.name)
        assertEquals(McpTransport.Http("http://127.0.0.1:3001/mcp"), seen.transport)
        assertEquals(true, seen.enabled)
        assertEquals(mapOf("Authorization" to "Bearer x"), seen.headers)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun installGateway(vararg servers: RuntimeMcpServer) {
        _root_ide_package_.com.niki914.zafiro.chat.installRuntimeSettingsGatewayForTest(
            _root_ide_package_.com.niki914.zafiro.chat.FakeRuntimeSettingsGateway(
                llmConfig = validLlmConfig(),
                builtinTools = listOf(RuntimeBuiltinToolSetting("memory", "m", enabled = true)),
                mcpServers = servers.toList(),
            )
        )
    }

    private fun server(
        name: String,
        url: String,
        enabled: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): RuntimeMcpServer = RuntimeMcpServer(name, url, enabled, headers)

    private fun validLlmConfig(): RuntimeLlmConfig = RuntimeLlmConfig(
        provider = "deepseek",
        endpoint = "https://example.com/v1",
        model = "deepseek-chat",
        prompt = "Base",
    )

    private fun okiaFactoryWith(client: McpClient) = LLMController.OkiaFactory { _, _, _ ->
        Okia.open(
            object : OkiaDependencies {
                override val agentLoop = stubLoop()
                override val protocolMapper = FakeMapper
                override val mcpClient = client
            },
        ) {
            endpoint = "https://example.com/v1"
            apiKey = "test-key"
            // 与生产一致：注入 LLMController 持有的注册表（MCP 发现注册进它）
            toolRegistry = LLMController.toolRegistry
        }
    }

    private fun stubLoop(): AgentLoop = object : AgentLoop {
        override suspend fun run(request: LoopRequest, onEvent: suspend (TurnEvent) -> Unit): TurnResult {
            return TurnResult.Completed(CompletionReason.Stop)
        }
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

    private class RecordingMcpClient(
        private val results: Map<String, List<McpDiscoveredTool>>,
        private val failWith: Throwable? = null,
        private val failTimes: Int = Int.MAX_VALUE,
        private val failServerNames: Set<String> = emptySet(),
    ) : McpClient {
        val discoveredServers = mutableListOf<McpServer>()
        var failures: Int = 0
            private set
        private var failuresRemaining = failTimes

        override suspend fun discoverTools(server: McpServer): List<McpDiscoveredTool> {
            synchronized(discoveredServers) {
                discoveredServers.add(server)
            }
            val shouldFail = synchronized(this) {
                val byServer = server.name in failServerNames
                val byCount = failWith != null && failuresRemaining > 0
                if (byCount) failuresRemaining--
                byServer || byCount
            }
            if (shouldFail) {
                synchronized(this) { failures++ }
                throw failWith ?: IllegalStateException("connection refused")
            }
            return results[server.name].orEmpty()
        }

        override suspend fun callTool(
            server: McpServer,
            toolName: String,
            argumentsJson: String,
        ): McpCallResult = McpCallResult(isError = false, content = emptyList())

        suspend fun awaitDiscoveryCalls(expected: Int) {
            // runTest 使用虚拟时间，后台刷新跑在 Dispatchers.IO（真实线程），
            // 虚拟 delay 不会推进——用真实时间轮询等待。
            withContext(Dispatchers.Default.limitedParallelism(1)) {
                withTimeout(5_000) {
                    while (true) {
                        val count = synchronized(discoveredServers) { discoveredServers.size }
                        if (count >= expected) break
                        delay(10)
                    }
                    // 让后台刷新彻底收尾（McpDiscovery 注册/合并 + finally 释放
                    // inFlight），避免下一次 refresh 的 CAS 撞上未完成的前一次
                    delay(300)
                }
            }
        }
    }
}
