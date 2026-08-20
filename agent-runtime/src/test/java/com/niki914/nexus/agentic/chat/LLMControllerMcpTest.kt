package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.util.SilentLoggerRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeBuiltinToolSetting
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer
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
    val silentLogger = SilentLoggerRule()

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
    fun refresh_mcpDiscoveryFailureStillUpdatesSignatureNoStorm() = runTest {
        val recording = RecordingMcpClient(
            results = mapOf("server1" to listOf(McpDiscoveredTool("echo", "Echo", null))),
            failWith = IllegalStateException("connection refused"),
        )
        installGateway(server("server1", "http://127.0.0.1:3001/mcp"))
        LLMController.okiaFactory = okiaFactoryWith(recording)

        LLMController.refresh()
        recording.awaitDiscoveryCalls(1)
        // 失败后签名已更新 → 下一轮不重试（防风暴）
        LLMController.refresh()
        recording.awaitDiscoveryCalls(1)
        assertTrue(recording.failures > 0)
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
        installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
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
    ) : McpClient {
        val discoveredServers = mutableListOf<McpServer>()
        var failures: Int = 0
            private set

        override suspend fun discoverTools(server: McpServer): List<McpDiscoveredTool> {
            synchronized(discoveredServers) {
                discoveredServers.add(server)
            }
            failWith?.let {
                failures++
                throw it
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
