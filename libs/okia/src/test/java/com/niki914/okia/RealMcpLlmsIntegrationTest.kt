package com.niki914.okia

import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.loop.CompletionReason
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.mcp.McpServer
import com.niki914.okia.mcp.McpTransport
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.protocol.OpenAIChatCompletionProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.Socket

/**
 * 真实 DeepSeek + 真实 server-everything 混合集成测试（T9c 层 3）。
 *
 * 对话 history mock：open(restore) 预置一段「成功回显」先例（User →
 * Assistant(tool_call=mcp__everything__echo) → ToolResult → Assistant），模型
 * 在本轮用户指令下模仿先例自主产出工具调用——验证产品形态的完整链路：
 * 真实发现（12 工具注册）→ 模型自主选择工具 → 真实执行 → 结果回喂 →
 * 模型总结 → 回合完成。
 *
 * 预算控制：maxTokens=512；systemPrompt 强引导避免模型自由探索。
 * 模型行为不确定性：若未调用工具（测试红），重跑一次即可，属 LLM 不遵循
 * 指令，非框架缺陷。
 * 门控：3001 端口 + OKIA_TEST_API_KEY 双 Assume；key 绝不进仓库。
 * Design source: 用户 2026-08-17 既定外部依赖（server-everything + DeepSeek 官方 API）。
 */
class RealMcpLlmsIntegrationTest {

    private val apiKey: String? = System.getenv("OKIA_TEST_API_KEY")

    @Before
    fun requireExternalDeps() {
        assumeTrue("OKIA_TEST_API_KEY 未设置", !apiKey.isNullOrEmpty())
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

    // mock 历史：一次成功的 mcp__everything__echo 先例（自洽：assistant tool_call 与
    // 后续 tool 消息按 call id 配对，provider 才能接受）
    private fun mockSnapshotWithEchoPrecedent(): SessionSnapshot {
        val callId = "precedent-call"
        val entries = listOf(
            ConversationEntry(
                "p-u",
                null,
                1,
                Message.User(listOf(ContentBlock.Text("请回显 'hello'")))
            ),
            ConversationEntry(
                "p-a", "p-u", 2,
                Message.Assistant(
                    AssistantMessage(
                        content = listOf(
                            ContentBlock.ToolCall(
                                callId,
                                "mcp__everything__echo",
                                """{"message":"hello"}"""
                            )
                        ),
                        stopReason = StopReason.ToolUse
                    )
                )
            ),
            ConversationEntry(
                "p-t", "p-a", 3,
                Message.ToolResult(
                    callId,
                    "mcp__everything__echo",
                    ToolCallOutcome.Success("Echoed: hello")
                )
            ),
            ConversationEntry(
                "p-s", "p-t", 4,
                Message.Assistant(
                    AssistantMessage(
                        content = listOf(ContentBlock.Text("已为您回显 'hello'")),
                        stopReason = StopReason.Stop
                    )
                )
            )
        )
        return SessionSnapshot(id = "it-hybrid", leafId = "p-s", version = 1, entries = entries)
    }

    @Test
    fun `llm autonomously calls real mcp tool following mocked precedent`() = runBlocking {
        val key = apiKey!!  // 先取局部变量：DSL 内 apiKey 解析为 Builder 属性（遮蔽陷阱）
        val okia = Okia.open(
            protocol = OpenAIChatCompletionProtocol(),
            restore = mockSnapshotWithEchoPrecedent()
        ) {
            apiKey = key
            model = "deepseek-v4-flash"
            maxTokens = 512
            endpoint = "https://api.deepseek.com/chat/completions"
            mcpServers = listOf(
                McpServer(
                    name = "everything",
                    transport = McpTransport.Http("http://localhost:3001/mcp"),
                    headers = emptyMap(),
                    enabled = true
                )
            )
        }
        try {
            val refresh = okia.refreshMcpTools()
            assertEquals("真实发现成功", listOf("everything"), refresh.refreshedServers)

            val result = okia.send(
                text = "请对 'okia 集成测试' 执行完全相同的回显操作，然后把你从工具收到的原文告诉我。",
                options = com.niki914.okia.TurnOptions(
                    systemPrompt = "回显操作必须调用工具 mcp__everything__echo 完成，工具参数 {\"message\": \"<要回显的文本>\"}。"
                )
            ) {}

            assertTrue(
                "回合 Completed(Stop): $result",
                result is TurnResult.Completed && result.reason == CompletionReason.Stop
            )

            val history = okia.conversation.value.history
            // restore 4 条 + 本轮 User + Assistant(ToolCall) + ToolResult + Assistant
            assertEquals(
                "历史 = 先例4 + 本轮4: ${history.map { it.message::class.simpleName }}",
                8, history.size
            )

            // 本轮链路：User(6) → Assistant(ToolCall mcp__everything__echo)(7) → ToolResult(8) → Assistant(9)
            val user = history[4].message
            assertTrue("本轮开头是 User: $user", user is Message.User)
            val assistantCall = history[5].message as Message.Assistant
            val call = assistantCall.message.content.filterIsInstance<ContentBlock.ToolCall>()
                .firstOrNull()
            assertTrue(
                "模型自主调用 mcp__everything__echo: ${assistantCall.message.content}",
                call != null
            )
            assertEquals("mcp__everything__echo", call?.name)
            val toolResult = history[6].message as Message.ToolResult
            assertTrue(
                "工具真实执行成功: $toolResult",
                toolResult.outcome is ToolCallOutcome.Success
            )
            assertTrue(
                "回显内容在结果中: ${(toolResult.outcome as ToolCallOutcome.Success).content}",
                (toolResult.outcome as ToolCallOutcome.Success).content.contains("okia 集成测试")
            )
        } finally {
            okia.close()
        }
    }
}