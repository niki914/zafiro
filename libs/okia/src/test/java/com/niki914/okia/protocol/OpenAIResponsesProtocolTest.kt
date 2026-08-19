package com.niki914.okia.protocol

import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.message.Usage
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolKind
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
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAI Responses API 协议层测试（fixture 单测，不碰网络）。
 * 流 fixture 对齐实测 DeepSeek /responses 字节流形态（2026-08-18）：
 * 命名事件 + data 携带 type 字段。
 */
class OpenAIResponsesProtocolTest {

    private val protocol = OpenAIResponsesProtocol()
    private val codec = Json

    // ── helpers ────────────────────────────────────────────────────────────

    /** 构造 named-event SSE 流：固定 event 行 + data 行 + 空行边界。 */
    private fun sse(vararg payloads: Pair<String, String>): Flow<SseLine> =
        buildList {
            payloads.forEach { (event, data) ->
                add(SseLine("event: $event"))
                add(SseLine("data: $data"))
                add(SseLine(""))
            }
        }.asFlow()

    private fun ev(event: String, data: String) = event to data

    private suspend fun parse(vararg payloads: Pair<String, String>): List<ProtocolEvent> =
        protocol.parseStream(sse(*payloads)).toList()

    private fun snapshot(
        endpoint: String = "https://api.deepseek.com/responses",
        apiKey: String = "sk-test",
        model: String = "deepseek-v4-flash",
        systemPrompt: String? = null,
        temperature: Float = 0.7f,
        maxTokens: Int = 2048,
        headers: Map<String, String> = emptyMap(),
        tools: List<ToolDescriptor> = emptyList()
    ) = RequestSnapshot(
        endpoint = endpoint,
        apiKey = apiKey,
        model = model,
        systemPrompt = systemPrompt,
        temperature = temperature,
        maxTokens = maxTokens,
        headers = headers,
        timeouts = HttpTimeouts(1000, 2000, 3000),
        tools = tools
    )

    private fun user(text: String) = Message.User(listOf(ContentBlock.Text(text)))

    private fun assistant(blocks: List<ContentBlock>) = Message.Assistant(AssistantMessage(blocks))

    private fun toolResult(callId: String, outcome: ToolCallOutcome) =
        Message.ToolResult(callId, "tool-a", outcome)

    private fun body(request: HttpRequest): JsonObject =
        Json.parseToJsonElement(request.body!!).jsonObject

    // 完整文本回合的流序列（实测形态）：created → item added → part added →
    // text delta* → item done → completed
    private fun textTurnStream(text: String): List<Pair<String, String>> = listOf(
        ev("response.created", """{"type":"response.created","response":{"id":"r1","status":"in_progress"}}"""),
        ev("response.output_item.added", """{"type":"response.output_item.added","item":{"type":"message","id":"m1","role":"assistant","content":[]},"output_index":0}"""),
        ev("response.content_part.added", """{"type":"response.content_part.added","content_index":0,"part":{"type":"output_text","text":""}}"""),
        ev("response.output_text.delta", """{"type":"response.output_text.delta","delta":"$text"}"""),
        ev("response.output_item.done", """{"type":"response.output_item.done","item":{"type":"message","id":"m1","status":"completed","content":[{"type":"output_text","text":"$text"}]},"output_index":0}"""),
        ev(
            "response.completed",
            """{"type":"response.completed","response":{"id":"r1","status":"completed","model":"deepseek-v4-flash","output":[{"type":"message","content":[{"type":"output_text","text":"$text"}]}],"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}}"""
        )
    )

    // ── buildRequest：请求外壳 ────────────────────────────────────────────

    @Test
    fun requestShellCarriesEndpointAndBearer() {
        val request = protocol.buildRequest(snapshot(apiKey = "sk-abc"), emptyList())
        assertEquals("https://api.deepseek.com/responses", request.url)
        assertEquals("POST", request.method)
        assertEquals("Bearer sk-abc", request.headers["Authorization"])
    }

    @Test
    fun requestBodyCarriesResponsesFields() {
        val request = protocol.buildRequest(
            snapshot(model = "deepseek-v4-flash", maxTokens = 512, temperature = 0.3f, systemPrompt = "你简短"),
            listOf(user("你好"))
        )
        val json = body(request)
        assertEquals("deepseek-v4-flash", json["model"]!!.jsonPrimitive.content)
        assertEquals(512, json["max_output_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("0.3", json["temperature"]!!.jsonPrimitive.content)
        assertEquals(true, json["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("你简短", json["instructions"]!!.jsonPrimitive.content)
    }

    @Test
    fun inputMapsUserAndAssistantAndToolRoundtrip() {
        val request = protocol.buildRequest(
            snapshot(),
            listOf(
                user("你好"),
                assistant(listOf(ContentBlock.Text("收到"))),
                assistant(listOf(ContentBlock.ToolCall("call_1", "get_weather", """{"city":"北京"}"""))),
                toolResult("call_1", ToolCallOutcome.Success("""{"temp":26}"""))
            )
        )
        val input = body(request)["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("user", "assistant", "function_call", "function_call_output"),
            input.map { it["type"]?.jsonPrimitive?.contentOrNull ?: it["role"]!!.jsonPrimitive.content }
        )
        val call = input[2]
        assertEquals("call_1", call["call_id"]!!.jsonPrimitive.content)
        assertEquals("get_weather", call["name"]!!.jsonPrimitive.content)
        assertEquals("""{"city":"北京"}""", call["arguments"]!!.jsonPrimitive.content)
        val output = input[3]
        assertEquals("call_1", output["call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"temp":26}""", output["output"]!!.jsonPrimitive.content)
    }

    @Test
    fun thinkingConvertsToTextOnReplay() {
        // OpenAI Responses reasoning 加密不可回放：思考块按 requiresThinkingAsText 转文本
        val request = protocol.buildRequest(
            snapshot(),
            listOf(assistant(listOf(ContentBlock.Thinking("推导"), ContentBlock.Text("答案"))))
        )
        val input = body(request)["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("assistant", input[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("推导\n答案", input[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsSerializeAsResponsesFunctions() {
        val tool = ToolDescriptor(
            name = "get_weather",
            description = "查询天气",
            inputSchemaJson = """{"type":"object","properties":{"city":{"type":"string"}}}""",
            kind = ToolKind.Local
        )
        val request = protocol.buildRequest(snapshot(tools = listOf(tool)), emptyList())
        val t = body(request)["tools"]!!.jsonArray[0].jsonObject
        assertEquals("function", t["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", t["name"]!!.jsonPrimitive.content)
        assertEquals("object", t["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolsOmittedWhenEmpty() {
        val request = protocol.buildRequest(snapshot(), emptyList())
        assertNull(body(request)["tools"])
    }

    // ── parseStream：文本 ─────────────────────────────────────────────────

    @Test
    fun textTurnEmitsDeltasAndCompletedStop() = runTest {
        val events = parse(*textTurnStream("你好").toTypedArray())
        assertEquals(
            listOf(
                ProtocolEvent.TextDelta("你好"),
                ProtocolEvent.Completed(Usage(10, 5, 0, 0, 0), "deepseek-v4-flash", StopReason.Stop)
            ),
            events
        )
    }

    @Test
    fun reasoningDeltaEmitsThinkingDelta() = runTest {
        // DeepSeek responses 走 reasoning_text.delta；OpenAI 官方走 reasoning_summary_text.delta
        val events = parse(
            ev("response.reasoning_text.delta", """{"type":"response.reasoning_text.delta","delta":"想"}"""),
            ev("response.reasoning_text.delta", """{"type":"response.reasoning_text.delta","delta":"考"}"""),
            *textTurnStream("答").toTypedArray()
        )
        assertEquals(
            listOf(
                ProtocolEvent.ThinkingDelta("想"),
                ProtocolEvent.ThinkingDelta("考"),
                ProtocolEvent.TextDelta("答"),
                ProtocolEvent.Completed(Usage(10, 5, 0, 0, 0), "deepseek-v4-flash", StopReason.Stop)
            ),
            events
        )
    }

    @Test
    fun reasoningSummaryDeltaEmitsThinkingDelta() = runTest {
        val events = parse(
            ev("response.reasoning_summary_text.delta", """{"type":"response.reasoning_summary_text.delta","delta":"摘要"}"""),
            *textTurnStream("答").toTypedArray()
        )
        assertEquals(
            listOf(
                ProtocolEvent.ThinkingDelta("摘要"),
                ProtocolEvent.TextDelta("答"),
                ProtocolEvent.Completed(Usage(10, 5, 0, 0, 0), "deepseek-v4-flash", StopReason.Stop)
            ),
            events
        )
    }

    // ── parseStream：工具调用 ─────────────────────────────────────────────

    @Test
    fun toolCallRoundtripAssemblesArguments() = runTest {
        val events = parse(
            ev("response.output_item.added", """{"type":"response.output_item.added","item":{"type":"function_call","id":"itm_1","call_id":"call_1","name":"get_weather","arguments":""},"output_index":0}"""),
            ev("response.function_call_arguments.delta", """{"type":"response.function_call_arguments.delta","item_id":"itm_1","delta":"{\"city\":"}"""),
            ev("response.function_call_arguments.delta", """{"type":"response.function_call_arguments.delta","item_id":"itm_1","delta":"\"北京\"}"}"""),
            ev("response.output_item.done", """{"type":"response.output_item.done","item":{"type":"function_call","id":"itm_1","call_id":"call_1","name":"get_weather","arguments":"{\"city\":\"北京\"}"},"output_index":0}"""),
            ev("response.completed", """{"type":"response.completed","response":{"id":"r1","status":"completed","model":"deepseek-v4-flash","output":[{"type":"function_call","call_id":"call_1","name":"get_weather","arguments":"{\"city\":\"北京\"}"}]}}""")
        )
        assertEquals(
            listOf(
                ProtocolEvent.ToolCallStarted("call_1", "get_weather"),
                ProtocolEvent.ToolCallDelta("call_1", "", """{"city":"""),
                ProtocolEvent.ToolCallDelta("call_1", "", """"北京"}"""),
                ProtocolEvent.ToolCallReady("call_1", "get_weather", """{"city":"北京"}"""),
                ProtocolEvent.Completed(null, "deepseek-v4-flash", StopReason.ToolUse)
            ),
            events
        )
    }

    @Test
    fun toolCallWithoutDeltasFillsArgumentsFromDone() = runTest {
        // 某些 Provider 不发 arguments delta：done 携带全量参数，须补发一次 Delta
        val events = parse(
            ev("response.output_item.added", """{"type":"response.output_item.added","item":{"type":"function_call","id":"itm_1","call_id":"call_1","name":"t","arguments":""},"output_index":0}"""),
            ev("response.output_item.done", """{"type":"response.output_item.done","item":{"type":"function_call","id":"itm_1","call_id":"call_1","name":"t","arguments":"{\"x\":1}"},"output_index":0}"""),
            ev("response.completed", """{"type":"response.completed","response":{"id":"r1","status":"completed","output":[{"type":"function_call"}]}}""")
        )
        assertEquals(
            listOf(
                ProtocolEvent.ToolCallStarted("call_1", "t"),
                ProtocolEvent.ToolCallDelta("call_1", "", """{"x":1}"""),
                ProtocolEvent.ToolCallReady("call_1", "t", """{"x":1}"""),
                ProtocolEvent.Completed(null, null, StopReason.ToolUse)
            ),
            events
        )
    }

    // ── parseStream：终态与失败 ───────────────────────────────────────────

    @Test
    fun incompleteMaxOutputMapsToLength() = runTest {
        val events = parse(
            ev("response.completed", """{"type":"response.completed","response":{"id":"r1","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},"model":"m","usage":{"input_tokens":3,"output_tokens":9}}}""")
        )
        assertEquals(ProtocolEvent.Completed(Usage(3, 9, 0, 0, 0), "m", StopReason.Length), events.lastOrNull())
    }

    @Test
    fun responseIncompleteEventMapsToLength() = runTest {
        // 官方流事件：response.incomplete 是独立终态事件（reason=max_tokens），
        // 不是 response.completed 的 status 变体（OpenAI Streaming Events 文档）。
        // 当前事件分发未处理该事件名 → 忽略 → 流结束无 completed → Error。
        val events = parse(
            ev("response.incomplete", """{"type":"response.incomplete","response":{"id":"r1","status":"incomplete","incomplete_details":{"reason":"max_tokens"},"model":"m","usage":{"input_tokens":3,"output_tokens":9}}}""")
        )
        assertEquals(
            ProtocolEvent.Completed(Usage(3, 9, 0, 0, 0), "m", StopReason.Length),
            events.lastOrNull()
        )
    }

    @Test
    fun incompleteStatusWithOfficialMaxTokensReasonMapsToLength() = runTest {
        // 官方 reason 值是 max_tokens（当前实现只匹配 DeepSeek 网关形态的
        // max_output_tokens）；官方形态下即使走 response.completed 容器也会抛错。
        val events = parse(
            ev("response.completed", """{"type":"response.completed","response":{"id":"r1","status":"incomplete","incomplete_details":{"reason":"max_tokens"},"model":"m","usage":{"input_tokens":3,"output_tokens":9}}}""")
        )
        assertEquals(
            ProtocolEvent.Completed(Usage(3, 9, 0, 0, 0), "m", StopReason.Length),
            events.lastOrNull()
        )
    }

    @Test
    fun reasoningItemReplayedLosslesslyFromOpaquePayload() = runTest {
        // 手动历史重放（无 previous_response_id）：OpenAI 官方 reasoning 加密不可
        // 重建，必须把先前 output 的 reasoning item（encrypted_content）原样回带
        // （OpenAI 迁移指南）。带合法 envelope 的 Thinking 块原样还原 reasoning
        // item；其文本不拼进 message item（避免重复）；无 payload 走明文路径。
        val reasoningItem = """{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"摘要"}],"content":[{"type":"reasoning_text","text":"推导"}],"encrypted_content":"U2FsdGVkX1=="}"""
        val payload = "openai-responses:reasoning:v1:" + """{"items":[$reasoningItem]}"""
        val request = protocol.buildRequest(
            snapshot(),
            listOf(
                assistant(
                    listOf(
                        ContentBlock.Thinking("推导", opaquePayload = payload),
                        ContentBlock.Text("答案")
                    )
                )
            )
        )
        val input = body(request)["input"]!!.jsonArray
        // 期望：reasoning item 单独成条（encrypted_content 原样），文本 message 条并存
        assertEquals(2, input.size)
        assertEquals("reasoning", input[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("rs_1", input[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("U2FsdGVkX1==", input[0].jsonObject["encrypted_content"]!!.jsonPrimitive.content)
        // 带 payload 的思考文本不拼进 message（D5）；普通 Text 块照常
        assertEquals("答案", input[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun unknownOpaquePayloadPrefixFallsBackToPlainText() = runTest {
        // 前缀不认识（未来其他 provider 的 payload）：忽略 payload，思考文本按明文合并
        val request = protocol.buildRequest(
            snapshot(),
            listOf(
                assistant(
                    listOf(
                        ContentBlock.Thinking("推导", opaquePayload = "other-provider:v9:whatever"),
                        ContentBlock.Text("答案")
                    )
                )
            )
        )
        val input = body(request)["input"]!!.jsonArray
        assertEquals(1, input.size)
        assertEquals("推导\n答案", input[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun reasoningItemCapturedAsOpaquePayloadEnvelope() = runTest {
        // 解析层：output_item.done(type=reasoning) 的完整 item（含 encrypted_content）
        // 原样累积；阶段边界（下一个非 reasoning item 开始）统一封装为 envelope
        // 发出，先于后续文本 delta。
        val reasoningItem = """{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"摘要"}],"content":[{"type":"reasoning_text","text":"推导"}],"encrypted_content":"U2FsdGVkX1=="}"""
        val events = parse(
            ev("response.output_item.added", """{"type":"response.output_item.added","item":{"type":"reasoning","id":"rs_1","summary":[],"content":[]},"output_index":0}"""),
            ev("response.reasoning_text.delta", """{"type":"response.reasoning_text.delta","delta":"推导"}"""),
            ev("response.output_item.done", """{"type":"response.output_item.done","item":$reasoningItem,"output_index":0}"""),
            ev("response.output_item.added", """{"type":"response.output_item.added","item":{"type":"message","id":"m1","role":"assistant","content":[]},"output_index":1}"""),
            ev("response.output_text.delta", """{"type":"response.output_text.delta","delta":"答案"}"""),
            ev("response.output_item.done", """{"type":"response.output_item.done","item":{"type":"message","id":"m1","status":"completed","content":[{"type":"output_text","text":"答案"}]},"output_index":1}"""),
            ev("response.completed", """{"type":"response.completed","response":{"id":"r1","status":"completed","model":"m","usage":{"input_tokens":3,"output_tokens":9}}}""")
        )
        assertEquals(
            listOf(
                ProtocolEvent.ThinkingDelta("推导"),
                ProtocolEvent.ThinkingOpaquePayload(
                    "openai-responses:reasoning:v1:" + """{"items":[$reasoningItem]}"""
                ),
                ProtocolEvent.TextDelta("答案"),
                ProtocolEvent.Completed(Usage(3, 9, 0, 0, 0), "m", StopReason.Stop)
            ),
            events
        )
    }

    @Test
    fun reasoningItemWithoutTextStillCaptured() = runTest {
        // payload-only：没有 reasoning 文本 delta 也保存完整 item（官方 reasoning
        // summary 需显式启用，不能要求先出现 ThinkingDelta）。
        val reasoningItem = """{"type":"reasoning","id":"rs_1","summary":[],"content":[],"encrypted_content":"U2FsdGVkX1=="}"""
        val events = parse(
            ev("response.output_item.added", """{"type":"response.output_item.added","item":{"type":"reasoning","id":"rs_1"},"output_index":0}"""),
            ev("response.output_item.done", """{"type":"response.output_item.done","item":$reasoningItem,"output_index":0}"""),
            ev("response.completed", """{"type":"response.completed","response":{"id":"r1","status":"completed","model":"m","usage":{"input_tokens":3,"output_tokens":9}}}""")
        )
        assertEquals(
            listOf(
                ProtocolEvent.ThinkingOpaquePayload(
                    "openai-responses:reasoning:v1:" + """{"items":[$reasoningItem]}"""
                ),
                ProtocolEvent.Completed(Usage(3, 9, 0, 0, 0), "m", StopReason.Stop)
            ),
            events
        )
    }

    @Test
    fun multipleReasoningItemsKeptInOneEnvelope() = runTest {
        // D6：多个 reasoning item 全部保留（数组 envelope，不接受 last-wins）
        val item1 = """{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"一"}],"content":[],"encrypted_content":"U2FsdGVkXzE="}"""
        val item2 = """{"type":"reasoning","id":"rs_2","summary":[{"type":"summary_text","text":"二"}],"content":[],"encrypted_content":"U2FsdGVkXzI="}"""
        val events = parse(
            ev("response.output_item.added", """{"type":"response.output_item.added","item":{"type":"reasoning","id":"rs_1"},"output_index":0}"""),
            ev("response.output_item.done", """{"type":"response.output_item.done","item":$item1,"output_index":0}"""),
            ev("response.output_item.added", """{"type":"response.output_item.added","item":{"type":"reasoning","id":"rs_2"},"output_index":1}"""),
            ev("response.output_item.done", """{"type":"response.output_item.done","item":$item2,"output_index":1}"""),
            ev("response.output_item.added", """{"type":"response.output_item.added","item":{"type":"message","id":"m1"},"output_index":2}"""),
            ev("response.output_text.delta", """{"type":"response.output_text.delta","delta":"答"}"""),
            ev("response.output_item.done", """{"type":"response.output_item.done","item":{"type":"message","id":"m1"},"output_index":2}"""),
            ev("response.completed", """{"type":"response.completed","response":{"id":"r1","status":"completed","model":"m","usage":{"input_tokens":3,"output_tokens":9}}}""")
        )
        val payload = events.filterIsInstance<ProtocolEvent.ThinkingOpaquePayload>().single()
        assertEquals(
            "openai-responses:reasoning:v1:" + """{"items":[$item1,$item2]}""",
            payload.payload
        )
    }

    @Test
    fun usageParsesCachedAndReasoningTokens() = runTest {
        val events = parse(
            ev("response.completed", """{"type":"response.completed","response":{"id":"r1","status":"completed","usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":20},"output_tokens":30,"output_tokens_details":{"reasoning_tokens":10}}}}""")
        )
        assertEquals(Usage(80, 30, 20, 0, 10), (events.last() as ProtocolEvent.Completed).usage)
    }

    @Test
    fun responseFailedEmitsError() = runTest {
        val events = parse(
            ev("response.failed", """{"type":"response.failed","response":{"id":"r1","status":"failed","error":{"message":"boom"}}}""")
        )
        assertTrue(events.single() is ProtocolEvent.Error)
    }

    @Test
    fun streamEndWithoutCompletedEmitsError() = runTest {
        val events = parse(
            ev("response.output_text.delta", """{"type":"response.output_text.delta","delta":"话没说完"}""")
        )
        assertEquals(listOf(ProtocolEvent.TextDelta("话没说完")), events.dropLast(1))
        assertTrue(events.last() is ProtocolEvent.Error)
    }

    @Test
    fun nonJsonDataEmitsError() = runTest {
        val events = protocol.parseStream(
            listOf(SseLine("event: response.completed"), SseLine("data: not-json"), SseLine("")).asFlow()
        ).toList()
        assertTrue(events.single() is ProtocolEvent.Error)
    }

    @Test
    fun unknownEventsIgnored() = runTest {
        // created / in_progress / content_part.* / output_text.done 等不影响结果
        val events = parse(
            ev("response.in_progress", """{"type":"response.in_progress","response":{"id":"r1"}}"""),
            ev("response.content_part.done", """{"type":"response.content_part.done"}"""),
            ev("response.output_text.done", """{"type":"response.output_text.done"}"""),
            *textTurnStream("好").toTypedArray()
        )
        assertEquals(
            listOf(
                ProtocolEvent.TextDelta("好"),
                ProtocolEvent.Completed(Usage(10, 5, 0, 0, 0), "deepseek-v4-flash", StopReason.Stop)
            ),
            events
        )
    }

    // ── 身份与编解码器 ────────────────────────────────────────────────────

    @Test
    fun idAndEndpointComeFromCompat() {
        assertEquals("openai-responses", protocol.id)
        assertEquals("https://api.openai.com/v1/responses", protocol.defaultEndpoint)
    }

    @Test
    fun withCodecReturnsNewInstancePreservingCompat() {
        val other = protocol.withCodec(Json { prettyPrint = true }) as OpenAIResponsesProtocol
        assertTrue(other !== protocol)
        assertEquals("openai-responses", other.id)
    }

    @Test
    fun encodeToolResultWrapsOutcomeFaithfully() {
        val call = ContentBlock.ToolCall("call_1", "tool-a", "{}")
        val outcome = ToolCallOutcome.Success("ok")
        assertEquals(
            Message.ToolResult("call_1", "tool-a", outcome),
            protocol.encodeToolResult(call, outcome)
        )
    }
}