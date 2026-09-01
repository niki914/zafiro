package com.niki914.okia.protocol

import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.message.Usage
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.SseLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Gemini（Generative Language API）协议层测试（fixture 单测，不碰网络）。
 *
 * 本文件同时是评审发现的回归载体（PR #feat/implement-okia 评审，2026-08）：
 * - [functionCallWithStopFinishReasonEmitsToolUse]：finishReason=STOP 但内容含
 *   functionCall 时须映射 ToolUse（pi 语义：stop + content 含 toolCall → toolUse，
 *   pi api/google-generative-ai.ts）；修复前一律 Stop → 工具永远不执行。
 * - [thinkingPartWrappingFunctionCallEmitsToolCall]：Gemini 3 把 functionCall 与
 *   thoughtSignature 置于同一 part（thought:true 包裹）；修复前走 handleThinkingPart
 *   后 functionCall 被静默丢弃，工具调用整个丢失。
 * - [functionCallPartThoughtSignatureCarriedOnToolCall]：thoughtSignature 可出现在任意
 *   part 类型（Google 文档，pi google-shared.ts 协议注记）；签名随 ToolCallReady
 *   落到 ToolCall 块，回放时原样带回到 functionCall part。
 */
class GeminiProtocolTest {

    private val protocol = GeminiProtocol()

    // ── helpers ────────────────────────────────────────────────────────────

    /** Gemini SSE 流：data 行 + 空行边界（无 event 名）。 */
    private fun chunk(json: String): Flow<SseLine> =
        listOf(SseLine("data: $json"), SseLine("")).asFlow()

    private suspend fun parse(json: String): List<ProtocolEvent> =
        protocol.parseStream(chunk(json)).toList()

    private fun snapshot(
        endpoint: String = "https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent",
        apiKey: String = "sk-test",
        model: String = "gemini-3-pro-preview",
        maxTokens: Int = 2048
    ) = RequestSnapshot(
        endpoint = endpoint,
        apiKey = apiKey,
        model = model,
        systemPrompt = null,
        temperature = 0.7f,
        maxTokens = maxTokens,
        headers = emptyMap(),
        timeouts = HttpTimeouts(1000, 2000, 3000),
        tools = emptyList()
    )

    private fun body(request: HttpRequest): JsonObject =
        Json.parseToJsonElement(request.body!!).jsonObject

    // ── finding 1：STOP + functionCall 须映射 ToolUse ─────────────────────

    @Test
    fun functionCallWithStopFinishReasonEmitsToolUse() = runTest {
        // Gemini 返回 functionCall 后通常以正常结束状态（finishReason=STOP）收尾。
        // pi 语义：content 含 toolCall 且 finishReason=stop → stopReason="toolUse"
        // （pi api/google-generative-ai.ts，loop 只在 ToolUse 时执行工具）。
        val events = parse(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"city":"北京"},"id":"call_1"}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}"""
        )
        assertEquals(
            listOf(
                ProtocolEvent.ToolCallStarted("call_1", "get_weather"),
                ProtocolEvent.ToolCallDelta("call_1", "get_weather", """{"city":"北京"}"""),
                ProtocolEvent.ToolCallReady("call_1", "get_weather", """{"city":"北京"}"""),
                ProtocolEvent.Completed(Usage(10, 5, 0, 0, 0), null, StopReason.ToolUse)
            ),
            events
        )
    }

    // ── finding 2：Gemini 3 thoughtSignature / 嵌套 functionCall ───────────

    @Test
    fun thinkingPartWrappingFunctionCallEmitsToolCall() = runTest {
        // Gemini 3 思维内工具调用：functionCall 与 thoughtSignature 同 part
        // （thought:true 包裹）。pi 对 functionCall 的处理不依赖 thought 标志
        // （google-generative-ai.ts：isThinking 只决定块类型，part.functionCall
        // 单独解析）。当前实现把整个 part 交给 handleThinkingPart → functionCall
        // 静默丢弃 → 该轮工具调用丢失。
        val events = parse(
            """{"candidates":[{"content":{"parts":[{"thought":true,"text":"查天气","thoughtSignature":"c2ln","functionCall":{"name":"get_weather","args":{"city":"北京"},"id":"call_1"}}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":5}}"""
        )
        assertEquals(
            listOf(
                ProtocolEvent.ThinkingDelta("查天气"),
                ProtocolEvent.ThinkingSignature("c2ln"),
                ProtocolEvent.ToolCallStarted("call_1", "get_weather"),
                ProtocolEvent.ToolCallDelta("call_1", "get_weather", """{"city":"北京"}"""),
                ProtocolEvent.ToolCallReady(
                    "call_1",
                    "get_weather",
                    """{"city":"北京"}""",
                    signature = "c2ln"
                ),
                ProtocolEvent.Completed(Usage(10, 5, 0, 0, 0), null, StopReason.ToolUse)
            ),
            events
        )
    }

    @Test
    fun functionCallPartThoughtSignatureCarriedOnToolCall() = runTest {
        // thoughtSignature 可出现在任意 part（含 functionCall，Google 文档 /
        // pi 协议注记：signature-bearing parts must be preserved as-is）。
        // 签名随 ToolCallReady 落到 ToolCall 块，回放时原样带回到 functionCall
        // part（assistantParts）——Gemini 3 工具循环否则下一步返回 400。
        val events = parse(
            """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"t","args":{},"id":"c1"},"thoughtSignature":"c2ln"}]},"finishReason":"STOP"}]}"""
        )
        assertEquals(
            listOf(
                ProtocolEvent.ToolCallStarted("c1", "t"),
                ProtocolEvent.ToolCallDelta("c1", "t", "{}"),
                ProtocolEvent.ToolCallReady("c1", "t", "{}", signature = "c2ln"),
                ProtocolEvent.Completed(null, null, StopReason.ToolUse)
            ),
            events
        )
    }

    @Test
    fun toolCallSignatureReplayedOnFunctionCallPart() {
        // 回放契约（pi 语义）：带签名的 ToolCall 块在历史序列化时，签名原样
        // 带回到 functionCall part（thoughtSignature）——Gemini 3 工具循环下一步
        // 否则返回 400。
        val request = protocol.buildRequest(
            snapshot(),
            listOf(
                Message.User(listOf(ContentBlock.Text("hi"))),
                Message.Assistant(
                    AssistantMessage(
                        listOf(
                            ContentBlock.ToolCall(
                                "call_1",
                                "get_weather",
                                "{}",
                                signature = "c2ln"
                            )
                        )
                    )
                )
            )
        )
        val contents = body(request)["contents"]!!.jsonArray
        val call = contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject
        assertEquals("c2ln", call["thoughtSignature"]!!.jsonPrimitive.content)
        assertEquals("call_1", call["functionCall"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }
}
