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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anthropic Messages API 协议层测试（fixture 单测，不碰网络）。
 * 流 fixture 对齐实测 DeepSeek /anthropic 网关字节流（2026-08-18）：
 * 命名事件 message_start / content_block_start / content_block_delta /
 * content_block_stop / message_delta / message_stop / ping。
 */
class AnthropicMessagesProtocolTest {

    private val protocol = AnthropicMessagesProtocol()

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
        endpoint: String = "https://api.deepseek.com/anthropic/v1/messages",
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

    private fun messagesOf(request: HttpRequest): List<JsonObject> =
        body(request)["messages"]!!.jsonArray.map { it.jsonObject }

    // 完整文本回合的流序列（实测形态）
    private fun textTurnStream(text: String): List<Pair<String, String>> = listOf(
        ev("message_start", """{"type":"message_start","message":{"id":"m1","type":"message","role":"assistant","model":"deepseek-v4-flash","content":[],"stop_reason":null,"usage":{"input_tokens":6,"cache_creation_input_tokens":2,"cache_read_input_tokens":1,"output_tokens":0}}}"""),
        ev("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
        ev("ping", """{"type":"ping"}"""),
        ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""),
        ev("content_block_stop", """{"type":"content_block_stop","index":0}"""),
        ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"input_tokens":6,"cache_creation_input_tokens":2,"cache_read_input_tokens":1,"output_tokens":12}}"""),
        ev("message_stop", """{"type":"message_stop"}""")
    )

    // ── buildRequest：请求外壳 ────────────────────────────────────────────

    @Test
    fun requestShellCarriesEndpointAndAnthropicHeaders() {
        val request = protocol.buildRequest(snapshot(apiKey = "sk-abc"), emptyList())
        assertEquals("https://api.deepseek.com/anthropic/v1/messages", request.url)
        assertEquals("POST", request.method)
        // 认证走 x-api-key + 固定 anthropic-version 版本头
        assertEquals("sk-abc", request.headers["x-api-key"])
        assertEquals("2023-06-01", request.headers["anthropic-version"])
        // x-api-key 命中通用 -key 片段脱敏
        assertTrue(request.toString().contains("x-api-key=██"))
    }

    @Test
    fun requestBodyCarriesAnthropicFields() {
        val request = protocol.buildRequest(
            snapshot(model = "deepseek-v4-flash", maxTokens = 512, systemPrompt = "你简短"),
            listOf(user("你好"))
        )
        val json = body(request)
        assertEquals("deepseek-v4-flash", json["model"]!!.jsonPrimitive.content)
        assertEquals(512, json["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, json["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("你简短", json["system"]!!.jsonPrimitive.content)  // system 是顶层字段
    }

    // ── buildRequest：消息映射与合并 ─────────────────────────────────────

    @Test
    fun userMessageMapsToTextBlock() {
        val request = protocol.buildRequest(snapshot(), listOf(user("你好")))
        val msg = messagesOf(request).single()
        assertEquals("user", msg["role"]!!.jsonPrimitive.content)
        val block = msg["content"]!!.jsonArray[0].jsonObject
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertEquals("你好", block["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun assistantWithThinkingAndSignatureMapsToThinkingBlock() {
        val request = protocol.buildRequest(
            snapshot(),
            listOf(assistant(listOf(
                ContentBlock.Thinking("推导", signature = "sig_1"),
                ContentBlock.Text("答案")
            )))
        )
        val msg = messagesOf(request).single()
        assertEquals("assistant", msg["role"]!!.jsonPrimitive.content)
        val blocks = msg["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("thinking", "text"), blocks.map { it["type"]!!.jsonPrimitive.content })
        assertEquals("推导", blocks[0]["thinking"]!!.jsonPrimitive.content)
        assertEquals("sig_1", blocks[0]["signature"]!!.jsonPrimitive.content)
    }

    @Test
    fun thinkingWithoutSignatureConvertsToText() {
        // 无 signature 的思考不能回带（Anthropic 要求签名）：转文本防御
        val request = protocol.buildRequest(
            snapshot(),
            listOf(assistant(listOf(ContentBlock.Thinking("推导"))))
        )
        val block = messagesOf(request).single()["content"]!!.jsonArray[0].jsonObject
        assertEquals("text", block["type"]!!.jsonPrimitive.content)
        assertEquals("推导", block["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolUseMapsWithParsedInput() {
        val request = protocol.buildRequest(
            snapshot(),
            listOf(assistant(listOf(ContentBlock.ToolCall("toolu_1", "get_weather", """{"city":"北京"}"""))))
        )
        val block = messagesOf(request).single()["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_use", block["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", block["id"]!!.jsonPrimitive.content)
        assertEquals("get_weather", block["name"]!!.jsonPrimitive.content)
        assertEquals("北京", block["input"]!!.jsonObject["city"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolResultsMergeIntoUserMessage() {
        // Anthropic 严格交替：连续 ToolResult + assistant 前后不插空 user
        val request = protocol.buildRequest(
            snapshot(),
            listOf(
                assistant(listOf(ContentBlock.ToolCall("toolu_1", "get_weather", "{}"))),
                toolResult("toolu_1", ToolCallOutcome.Success("""{"temp":26}""")),
                toolResult("toolu_1", ToolCallOutcome.Failure("boom", "detail"))
            )
        )
        val messages = messagesOf(request)
        assertEquals(2, messages.size)  // assistant + 合并后的 user
        assertEquals("assistant", messages[0]["role"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1]["role"]!!.jsonPrimitive.content)
        val blocks = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, blocks.size)
        val first = blocks[0]
        assertEquals("tool_result", first["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", first["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("""{"temp":26}""", first["content"]!!.jsonPrimitive.content)
        // 失败结果标记 is_error
        val second = blocks[1]
        assertEquals(true, second["is_error"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun toolResultsAndFollowingUserTextShareOneMessage() {
        // 工具结果后跟用户输入：并入同一 user 消息（Anthropic 不允许连续 user）
        val request = protocol.buildRequest(
            snapshot(),
            listOf(
                assistant(listOf(ContentBlock.ToolCall("t1", "tool-a", "{}"))),
                toolResult("t1", ToolCallOutcome.Success("ok")),
                user("继续")
            )
        )
        val messages = messagesOf(request)
        assertEquals(2, messages.size)
        val blocks = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("tool_result", "text"), blocks.map { it["type"]!!.jsonPrimitive.content })
    }

    @Test
    fun consecutiveSameRoleMessagesMerge() {
        // 防御：恢复的历史出现连续 assistant / user 时合并（严格交替规则）
        val request = protocol.buildRequest(
            snapshot(),
            listOf(
                assistant(listOf(ContentBlock.Text("a"))),
                assistant(listOf(ContentBlock.Text("b"))),
                user("x"),
                user("y")
            )
        )
        val messages = messagesOf(request)
        assertEquals(2, messages.size)
        val assistantBlocks = messages[0]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, assistantBlocks.size)  // a + b 合并进同一 assistant
    }

    // ── buildRequest：tools ───────────────────────────────────────────────

    @Test
    fun toolsSerializeWithInputSchema() {
        val tool = ToolDescriptor(
            name = "get_weather",
            description = "查询天气",
            inputSchemaJson = """{"type":"object","properties":{"city":{"type":"string"}}}""",
            kind = ToolKind.Local
        )
        val request = protocol.buildRequest(snapshot(tools = listOf(tool)), emptyList())
        val t = body(request)["tools"]!!.jsonArray[0].jsonObject
        assertEquals("get_weather", t["name"]!!.jsonPrimitive.content)
        assertEquals("object", t["input_schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(t["type"])  // Anthropic 工具无 type 字段
    }

    // ── parseStream：文本 ─────────────────────────────────────────────────

    @Test
    fun textTurnEmitsDeltasAndCompletedStop() = runTest {
        val events = parse(*textTurnStream("你好").toTypedArray())
        assertEquals(
            listOf(
                ProtocolEvent.TextDelta("你好"),
                ProtocolEvent.Completed(Usage(6, 12, 1, 2, 0), "deepseek-v4-flash", StopReason.Stop)
            ),
            events
        )
    }

    @Test
    fun cacheTokensPreservedWhenMessageDeltaOmitsCacheFields() = runTest {
        // CR5 回归：真实 Anthropic API 的 message_delta.usage 只携带 output_tokens，
        // cache 字段仅在 message_start 出现。原实现 delta 缺失字段读 0 并整体覆盖
        // state.usage，prompt caching 下 cacheRead/cacheWrite 每轮清零。delta 明确
        // 携带新值（含 0）时以新值覆盖（textTurnStream fixture 断言覆盖）。
        val events = parse(
            ev("message_start", """{"type":"message_start","message":{"model":"m","usage":{"input_tokens":100,"cache_read_input_tokens":5000,"cache_creation_input_tokens":300,"output_tokens":0}}}"""),
            ev("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
            ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}"""),
            ev("content_block_stop", """{"type":"content_block_stop","index":0}"""),
            ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":80}}"""),
            ev("message_stop", """{"type":"message_stop"}""")
        )
        val completed = events.filterIsInstance<ProtocolEvent.Completed>().single()
        assertEquals(
            Usage(
                inputTokens = 100,
                outputTokens = 80,
                cacheReadTokens = 5000,
                cacheWriteTokens = 300,
                reasoningTokens = 0
            ),
            completed.usage
        )
    }

    @Test
    fun thinkingBlockEmitsThinkingAndSignature() = runTest {
        val events = parse(
            ev("message_start", """{"type":"message_start","message":{"model":"m","usage":{"input_tokens":5}}}"""),
            ev("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}"""),
            ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"推导"}}"""),
            ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"过程"}}"""),
            ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig_1"}}"""),
            ev("content_block_stop", """{"type":"content_block_stop","index":0}"""),
            ev("content_block_start", """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}"""),
            ev("content_block_delta", """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"答案"}}"""),
            ev("content_block_stop", """{"type":"content_block_stop","index":1}"""),
            ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":9}}"""),
            ev("message_stop", """{"type":"message_stop"}""")
        )
        assertEquals(
            listOf(
                ProtocolEvent.ThinkingDelta("推导"),
                ProtocolEvent.ThinkingDelta("过程"),
                ProtocolEvent.ThinkingSignature("sig_1"),
                ProtocolEvent.TextDelta("答案"),
                ProtocolEvent.Completed(Usage(5, 9, 0, 0, 0), "m", StopReason.Stop)
            ),
            events
        )
    }

    @Test
    fun toolUseBlockEmitsLifecycleAndToolUseStop() = runTest {
        val events = parse(
            ev("message_start", """{"type":"message_start","message":{"model":"m","usage":{"input_tokens":5}}}"""),
            ev("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{}}}"""),
            ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"city\":"}}"""),
            ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\"北京\"}"}}"""),
            ev("content_block_stop", """{"type":"content_block_stop","index":0}"""),
            ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":8}}"""),
            ev("message_stop", """{"type":"message_stop"}""")
        )
        assertEquals(
            listOf(
                ProtocolEvent.ToolCallStarted("toolu_1", "get_weather"),
                ProtocolEvent.ToolCallDelta("toolu_1", "get_weather", """{"city":"""),
                ProtocolEvent.ToolCallDelta("toolu_1", "get_weather", """"北京"}"""),
                ProtocolEvent.ToolCallReady("toolu_1", "get_weather", """{"city":"北京"}"""),
                ProtocolEvent.Completed(Usage(5, 8, 0, 0, 0), "m", StopReason.ToolUse)
            ),
            events
        )
    }

    // ── parseStream：终态与失败 ───────────────────────────────────────────

    @Test
    fun maxTokensStopMapsToLength() = runTest {
        val events = parse(
            ev("message_start", """{"type":"message_start","message":{"model":"m"}}"""),
            ev("content_block_start", """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""),
            ev("content_block_delta", """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"截断"}}"""),
            ev("content_block_stop", """{"type":"content_block_stop","index":0}"""),
            ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"max_tokens"}}"""),
            ev("message_stop", """{"type":"message_stop"}""")
        )
        assertEquals(ProtocolEvent.Completed(null, "m", StopReason.Length), events.lastOrNull())
    }

    @Test
    fun streamErrorEventEmitsError() = runTest {
        val events = parse(
            ev("error", """{"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}""")
        )
        val error = events.single()
        assertTrue(error is ProtocolEvent.Error)
        assertTrue((error as ProtocolEvent.Error).cause.message!!.contains("overloaded"))
    }

    @Test
    fun overloadedStreamErrorIsMarkedRetryable() = runTest {
        // Anthropic 官方临时错误（overloaded 对应 529）：HTTP 200 后 SSE error event
        // 仍可达，retryable 标志让 loop 走可重试分类（问题 2）
        val events = parse(
            ev("error", """{"type":"error","error":{"type":"overloaded_error","message":"overloaded"}}""")
        )
        val error = events.single() as ProtocolEvent.Error
        assertTrue(error.retryable)
    }

    @Test
    fun clientStreamErrorIsNotRetryable() = runTest {
        // 客户端错误类型（invalid_request_error）：不可重试；message 从嵌套 error 对象解析
        val events = parse(
            ev("error", """{"type":"error","error":{"type":"invalid_request_error","message":"bad request"}}""")
        )
        val error = events.single() as ProtocolEvent.Error
        assertTrue(!error.retryable)
        assertTrue(error.cause.message!!.contains("bad request"))
    }

    @Test
    fun streamEndWithoutMessageStopEmitsError() = runTest {
        // message_start 后直接 EOF：协议不完整
        val events = parse(
            ev("message_start", """{"type":"message_start","message":{"model":"m"}}""")
        )
        assertTrue(events.single() is ProtocolEvent.Error)
    }

    @Test
    fun unknownStopReasonEmitsError() = runTest {
        val events = parse(
            ev("message_start", """{"type":"message_start","message":{"model":"m"}}"""),
            ev("message_delta", """{"type":"message_delta","delta":{"stop_reason":"weird"}}"""),
            ev("message_stop", """{"type":"message_stop"}""")
        )
        assertTrue(events.single() is ProtocolEvent.Error)
    }

    @Test
    fun nonJsonDataEmitsError() = runTest {
        val events = protocol.parseStream(
            listOf(SseLine("event: message_start"), SseLine("data: not-json"), SseLine("")).asFlow()
        ).toList()
        assertTrue(events.single() is ProtocolEvent.Error)
    }

    // ── 身份 / encodeToolResult ───────────────────────────────────────────

    @Test
    fun idAndEndpointComeFromCompat() {
        assertEquals("anthropic", protocol.id)
        assertEquals("https://api.anthropic.com/v1/messages", protocol.defaultEndpoint)
    }

    @Test
    fun withCodecReturnsNewInstancePreservingCompat() {
        val other = protocol.withCodec(Json { prettyPrint = true }) as AnthropicMessagesProtocol
        assertTrue(other !== protocol)
        assertEquals("anthropic", other.id)
    }

    @Test
    fun encodeToolResultWrapsOutcomeFaithfully() {
        val call = ContentBlock.ToolCall("toolu_1", "tool-a", "{}")
        val outcome = ToolCallOutcome.Interrupted("partial")
        assertEquals(
            Message.ToolResult("toolu_1", "tool-a", outcome),
            protocol.encodeToolResult(call, outcome)
        )
    }
}