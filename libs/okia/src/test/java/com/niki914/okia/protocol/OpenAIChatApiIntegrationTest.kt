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
 * 门面 + 真实 DeepSeek 官方 API 集成测试（T9c 层 2）。
 * 验证 OpenAIChatCompletionProtocol（DeepSeek compat 形态）对真实字节流的吻合：
 * - SSE 流式解析（delta.content / reasoning_content / [DONE]）
 * - usage 解析（stream_options.include_usage）
 * - finish_reason → Completed(Stop) → 回合正常结束
 * 预算：单回合 maxTokens=512，一次请求（含思考内容），远小于 100K 总预算。
 * key 从 OKIA_TEST_API_KEY 环境变量读取（运行测试时注入，不进仓库）；
 * 缺失时 Assume 跳过（不是失败）。
 * Design source: 用户 2026-08-17 提供官方 DeepSeek API（deepseek-v4-flash）。
 */
class OpenAIChatApiIntegrationTest {

    private val apiKey: String? = System.getenv("OKIA_TEST_API_KEY")

    @Before
    fun requireKey() {
        assumeTrue("OKIA_TEST_API_KEY 未设置（集成测试需真实 API key）", !apiKey.isNullOrEmpty())
    }

    @Test
    fun `real deepseek turn completes with text and usage`() = runBlocking {
        val key = apiKey!!  // 先取局部变量再写入 builder：DSL 内 apiKey 解析为 Builder 属性（遮蔽陷阱）
        val okia = Okia.open(protocol = OpenAIChatCompletionProtocol()) {
            apiKey = key
            model = "deepseek-v4-flash"
            maxTokens = 512
        }
        try {
            val events = mutableListOf<TurnEvent>()
            val result =
                okia.send("用一句话解释什么是 MCP（Model Context Protocol）。") { events += it }

            assertTrue(
                "回合 Completed(Stop): $result",
                result is TurnResult.Completed && result.reason == CompletionReason.Stop
            )

            // 历史末尾 = 真实模型回答（v4-flash 先思考再输出文本）
            val history = okia.conversation.value.history
            val last = history.last().message
            assertTrue("最后一条是 Assistant: $last", last is Message.Assistant)
            val text = (last as Message.Assistant).message.content
                .filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }
            assertTrue("回答文本非空", text.isNotBlank())

            // 流式过程证据：出现文本 delta 或思考 delta
            assertTrue(
                "事件流含流式中间态",
                events.any {
                    it is TurnEvent.TextDelta || it is TurnEvent.ThinkingDelta || it is TurnEvent.TextEnded
                }
            )
            // usage 记账（DeepSeek 带 include_usage 的流式响应含 usage 块）
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