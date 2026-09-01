package com.niki914.okia.protocol

import com.niki914.okia.Okia
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.CompletionReason
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * 门面 + 真实 DeepSeek /anthropic 端点集成测试（多协议 M1 层 2）。
 * 验证 AnthropicMessagesProtocol 对真实字节流的吻合（DeepSeek 提供
 * Anthropic 兼容端点，2026-08-18 实测可用）：
 * - 命名事件流解析（content_block_delta / message_delta / message_stop）
 * - x-api-key + anthropic-version 请求头、system 顶层字段
 * - usage 解析（input/output tokens）
 * key 从 OKIA_TEST_API_KEY 环境变量读取（运行测试时注入，不进仓库）；
 * 缺失时 Assume 跳过（不是失败）。
 */
class AnthropicApiIntegrationTest {

    private val apiKey: String? = System.getenv("OKIA_TEST_API_KEY")

    @Before
    fun requireKey() {
        assumeTrue("OKIA_TEST_API_KEY 未设置（集成测试需真实 API key）", !apiKey.isNullOrEmpty())
    }

    @Test
    fun `real deepseek anthropic turn completes with text and usage`() = runBlocking {
        val key = apiKey!!  // 先取局部变量再写入 builder：DSL 内 apiKey 解析为 Builder 属性（遮蔽陷阱）
        val okia = Okia.open(
            protocol = AnthropicMessagesProtocol(),
        ) {
            apiKey = key
            model = "deepseek-v4-flash"
            maxTokens = 256
            endpoint = "https://api.deepseek.com/anthropic/v1/messages"
        }
        try {
            val events = mutableListOf<TurnEvent>()
            val result = okia.send("用一句话解释什么是 JSON。") { events += it }

            assertTrue(
                "回合 Completed(Stop): $result",
                result is TurnResult.Completed && result.reason == CompletionReason.Stop
            )

            val history = okia.conversation.value.history
            val last = history.last().message
            assertTrue("最后一条是 Assistant: $last", last is Message.Assistant)
            val text = (last as Message.Assistant).message.content
                .filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
            assertTrue("回答文本非空", text.isNotBlank())

            // 流式过程证据：文本 delta
            assertTrue(
                "事件流含文本中间态",
                events.any { it is TurnEvent.TextDelta || it is TurnEvent.TextEnded }
            )

            // usage 记账（message_start input + message_delta output）
            val completed = events.filterIsInstance<TurnEvent.TurnCompleted>().firstOrNull()
            assertNotNull("观测到 TurnCompleted 事件", completed)
            val usage = completed?.message?.usage
            assertNotNull("Completed 消息带 usage", usage)
            assertTrue(
                "outputTokens ≥ 1（实际 ${usage?.outputTokens}）",
                (usage?.outputTokens ?: 0) >= 1
            )
        } finally {
            okia.close()
        }
    }
}