package com.niki914.okia.mcp

import com.niki914.okia.Okia
import com.niki914.okia.OkiaDependencies
import com.niki914.okia.fake.FakeHttpEngine
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.loop.CompletionReason
import com.niki914.okia.loop.RealAgentLoop
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.message.Usage
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.okia.transport.OkHttpEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket

/**
 * 门面 + 真实 server-everything 全链路集成测试（T9c 层 1）。
 * 链路：refreshMcpTools 真实发现 → 注册表含 mcp__everything__* 线缆工具 →
 * 真实 loop 收到模型工具调用（fake mapper 受控产出）→ McpExecutor 真实
 * callTool → 结果回喂 → 第二段请求 → 回合完成。
 * 服务器未启动时 Assume 跳过（不是失败）。
 * 与 McpServerEverythingIntegrationTest（T9a 线缆级）的区别：本文件走
 * 门面 + RealAgentLoop，验证发现 → 注册 → 执行 → 回喂的完整产品链路。
 * Design source: 用户 2026-08-17 提供测试服务器（npm
 * @modelcontextprotocol/server-everything，本机 3001 端口）。
 */
class RealOkiaMcpIntegrationTest {

    private val baseUrl = "http://localhost:3001/mcp"

    @Before
    fun requireServer() {
        assumeTrue(
            "server-everything 未启动（本机 3001）",
            try {
                Socket().use { it.connect(java.net.InetSocketAddress("localhost", 3001), 1000) }
                true
            } catch (e: Exception) {
                false
            }
        )
    }

    private fun server(name: String, base: String = baseUrl): McpServer = McpServer(
        name = name,
        transport = McpTransport.Http(base),
        headers = emptyMap(),
        enabled = true
    )

    /** 真实 MCP 客户端（AutoDetect 探测旧版新协议） + 真实 loop + fake mapper。 */
    private fun deps(mapper: FakeProtocolMapper): OkiaDependencies {
        val engine = OkHttpEngine()
        val auto = AutoDetectMcpClient(
            legacy = LegacyStreamableHttpMcpClient(engine),
            discovery = DiscoveryStreamableHttpMcpClient(engine)
        )
        return object : OkiaDependencies {
            override val agentLoop = RealAgentLoop()
            override val protocolMapper = mapper
            override val mcpClient = auto
        }
    }

    private fun toolsRound(toolName: String, argumentsJson: String): List<ProtocolEvent> = listOf(
        // 流式顺序（对齐 DeepSeek 真实形态）：Started → Delta（参数累积）→ Ready → Completed(ToolUse)
        ProtocolEvent.ToolCallStarted("it-call-1", toolName),
        ProtocolEvent.ToolCallDelta("it-call-1", toolName, argumentsJson),
        ProtocolEvent.ToolCallReady("it-call-1", toolName, argumentsJson),
        ProtocolEvent.Completed(Usage(10, 5, 0, 0, 0), "deepseek-v4-flash", StopReason.ToolUse)
    )

    private fun textRound(text: String): List<ProtocolEvent> = listOf(
        ProtocolEvent.TextDelta(text),
        ProtocolEvent.Completed(Usage(20, 10, 0, 0, 0), "deepseek-v4-flash", StopReason.Stop)
    )

    @Test
    fun `refresh discovers and registers prefixed real tools`() = runBlocking {
        val mapper = FakeProtocolMapper(listOf(textRound("ok")))
        val registry = DefaultToolRegistry()
        val okia = Okia.open(deps(mapper)) {
            mcpServers = listOf(server("everything"))
            toolRegistry = registry
            httpEngine = FakeHttpEngine()
        }
        try {
            val result = okia.refreshMcpTools()
            assertEquals("一个服务器刷新成功", listOf("everything"), result.refreshedServers)

            val names = registry.snapshot().map { it.descriptor.wireName }
            assertTrue("注册表含 mcp__everything__echo: $names",
                names.contains("mcp__everything__echo"))
            assertTrue("注册表含 mcp__everything__get-sum",
                names.contains("mcp__everything__get-sum"))

            val snapshot = okia.getMcpDiscoverySnapshot()
            val st = snapshot.servers["everything"]
            assertNotNull("发现快照含 everything", st)
            assertEquals("发现状态 Available", McpDiscoveryState.Available, st?.state)
            assertTrue("发现的工具数 ≥ 12", (st?.discoveredToolCount ?: 0) >= 12)
        } finally {
            okia.close()
        }
    }

    @Test
    fun `turn executes real mcp tool and feeds result back`() = runBlocking {
        // 轮次：round0 = 模型产出 everything_get-sum 调用；round1 = 工具执行后的最终文本
        val mapper = FakeProtocolMapper(
            listOf(
                toolsRound("mcp__everything__get-sum", """{"a":40,"b":2}"""),
                textRound("40 加 2 等于 42")
            )
        )
        val registry = DefaultToolRegistry()
        val okia = Okia.open(deps(mapper)) {
            mcpServers = listOf(server("everything"))
            toolRegistry = registry
            httpEngine = FakeHttpEngine()
        }
        try {
            okia.refreshMcpTools()
            assertEquals("注册表含 mcp__everything__get-sum", true,
                registry.snapshot().any { it.descriptor.wireName == "mcp__everything__get-sum" })

            val events = mutableListOf<com.niki914.okia.event.TurnEvent>()
            val result = okia.send("40 加 2 等于多少（用工具）") { events += it }

            assertTrue("回合 Completed(Stop): $result",
                result is TurnResult.Completed && result.reason == CompletionReason.Stop)

            // 历史链：User → Assistant(ToolCall) → ToolResult → Assistant(最终文本)
            val history = okia.conversation.value.history
            assertEquals("4 条消息", 4, history.size)
            val assistant1 = history[1].message as Message.Assistant
            val call = assistant1.message.content.filterIsInstance<ContentBlock.ToolCall>().first()
            assertEquals("mcp__everything__get-sum", call.name)
            val toolResult = history[2].message as Message.ToolResult
            assertTrue("工具结果 Success: $toolResult",
                toolResult.outcome is ToolCallOutcome.Success)
            // 真实 server-everything 返回原文（非翻译）——链路真实性的证据
            assertEquals("The sum of 40 and 2 is 42.",
                (toolResult.outcome as ToolCallOutcome.Success).content)
            val assistant2 = history[3].message as Message.Assistant
            val finalText = assistant2.message.content.filterIsInstance<ContentBlock.Text>()
                .joinToString("",) { it.text }
            // 最终文本来自 fake mapper 第二轮（工具结果回喂后的模型总结）
            assertEquals("最终文本", "40 加 2 等于 42", finalText)

            // 第二段请求的历史含 ToolResult（结果真实回喂）
            val secondHistory = mapper.builtHistories[1]
            assertTrue("第二段历史含 ToolResult",
                secondHistory.last() is Message.ToolResult)

            // 事件链含真实执行路径
            assertTrue("出现 ToolRunning", events.any { it is com.niki914.okia.event.TurnEvent.ToolRunning })
            assertTrue("出现 ToolSucceeded", events.any { it is com.niki914.okia.event.TurnEvent.ToolSucceeded })
        } finally {
            okia.close()
        }
    }

    @Test
    fun `refresh marks unreachable server as failed`() = runBlocking {
        val mapper = FakeProtocolMapper(listOf(textRound("ok")))
        val okia = Okia.open(deps(mapper)) {
            mcpServers = listOf(
                server("everything"),
                server("dead", base = "http://localhost:3999/mcp")
            )
            httpEngine = FakeHttpEngine()
        }
        try {
            val result = okia.refreshMcpTools()
            assertEquals("仅 everything 成功", listOf("everything"), result.refreshedServers)
            assertEquals("dead 失败", listOf("dead"), result.failedServers)

            val snapshot = okia.getMcpDiscoverySnapshot()
            assertEquals("dead 状态 Failed", McpDiscoveryState.Failed, snapshot.servers["dead"]?.state)
            assertNotNull("dead 有错误信息", snapshot.servers["dead"]?.errorMessage)
            assertEquals("everything 状态 Available", McpDiscoveryState.Available,
                snapshot.servers["everything"]?.state)
        } finally {
            okia.close()
        }
    }
}