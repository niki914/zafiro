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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4 协议层测试：DeepSeek Chat Completion 的请求构建与 SSE 流解析。
 * 输入为真实 SseEventParser 聚合后的 data 负载（fixture 字符串），
 * 全部纯数据变换，不碰网络。
 */
class OpenAIChatCompletionProtocolTest {

    private val protocol = OpenAIChatCompletionProtocol()

    // ── helpers ────────────────────────────────────────────────────────────

    /** 每个 payload 构造一个 SSE 事件（data 行 + 空行边界）。 */
    private fun sse(vararg payloads: String): Flow<SseLine> =
        buildList {
            payloads.forEach {
                add(SseLine("data: $it"))
                add(SseLine(""))
            }
        }.asFlow()

    private suspend fun parse(vararg payloads: String): List<ProtocolEvent> =
        protocol.parseStream(sse(*payloads)).toList()

    private fun snapshot(
        endpoint: String = "https://api.deepseek.com/chat/completions",
        apiKey: String = "sk-test",
        model: String = "deepseek-chat",
        systemPrompt: String? = null,
        temperature: Float = 0.7f,
        maxTokens: Int = 4096,
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

    private fun userBlocks(vararg blocks: ContentBlock) = Message.User(blocks.toList())

    private fun assistant(blocks: List<ContentBlock>) = Message.Assistant(AssistantMessage(blocks))

    private fun toolResult(callId: String, outcome: ToolCallOutcome) =
        Message.ToolResult(callId, "tool-a", outcome)

    private fun body(request: HttpRequest): JsonObject =
        Json.parseToJsonElement(request.body!!).jsonObject

    private fun messagesOf(request: HttpRequest): List<JsonObject> =
        body(request)["messages"]!!.jsonArray.map { it.jsonObject }

    // ── buildRequest：请求外壳 ────────────────────────────────────────────

    @Test
    fun requestShellCarriesEndpointMethodAndTimeouts() {
        val request = protocol.buildRequest(
            snapshot(endpoint = "https://example.com/v1/chat/completions"),
            emptyList()
        )
        assertEquals("https://example.com/v1/chat/completions", request.url)
        assertEquals("POST", request.method)
        assertEquals(HttpTimeouts(1000, 2000, 3000), request.timeouts)
    }

    @Test
    fun requestBodyCarriesFixedFields() {
        val request = protocol.buildRequest(
            snapshot(
                model = "deepseek-reasoner",
                maxTokens = 2048,
                temperature = 0.3f
            ), emptyList()
        )
        val json = body(request)
        assertEquals("deepseek-reasoner", json["model"]!!.jsonPrimitive.content)
        assertEquals(2048, json["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("0.3", json["temperature"]!!.jsonPrimitive.content)
        assertEquals(true, json["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            true,
            json["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content.toBoolean()
        )
    }

    @Test
    fun apiKeyBecomesBearerHeader() {
        val request = protocol.buildRequest(snapshot(apiKey = "sk-abc"), emptyList())
        assertEquals("Bearer sk-abc", request.headers["Authorization"])
    }

    @Test
    fun emptyApiKeyOmitsAuthorization() {
        val request = protocol.buildRequest(snapshot(apiKey = ""), emptyList())
        assertNull(request.headers["Authorization"])
    }

    @Test
    fun snapshotHeadersMergedWithAuthHeader() {
        val request = protocol.buildRequest(
            snapshot(apiKey = "sk-abc", headers = mapOf("X-Custom" to "v1")),
            emptyList()
        )
        assertEquals("v1", request.headers["X-Custom"])
        assertEquals("Bearer sk-abc", request.headers["Authorization"])
    }

    // ── buildRequest：messages 映射 ───────────────────────────────────────

    @Test
    fun systemPromptBecomesFirstSystemMessage() {
        val request =
            protocol.buildRequest(snapshot(systemPrompt = "你是助手"), listOf(user("你好")))
        val messages = messagesOf(request)
        assertEquals(2, messages.size)
        assertEquals("system", messages[0]["role"]!!.jsonPrimitive.content)
        assertEquals("你是助手", messages[0]["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1]["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun nullSystemPromptOmitsSystemMessage() {
        val request = protocol.buildRequest(snapshot(), listOf(user("你好")))
        assertEquals(1, messagesOf(request).size)
    }

    @Test
    fun userContentJoinsTextBlocks() {
        val request = protocol.buildRequest(
            snapshot(),
            listOf(userBlocks(ContentBlock.Text("a"), ContentBlock.Text("b")))
        )
        val userMsg = messagesOf(request).single()
        assertEquals("a\nb", userMsg["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun assistantWithThinkingMapsToReasoningContent() {
        val request = protocol.buildRequest(
            snapshot(),
            listOf(assistant(listOf(ContentBlock.Thinking("推导"), ContentBlock.Text("答案"))))
        )
        val msg = messagesOf(request).single()
        assertEquals("assistant", msg["role"]!!.jsonPrimitive.content)
        assertEquals("答案", msg["content"]!!.jsonPrimitive.content)
        assertEquals("推导", msg["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun assistantWithoutThinkingCarriesEmptyReasoningContent() {
        // DeepSeek 要求 assistant 消息带 reasoning_content（可为空）
        val request =
            protocol.buildRequest(snapshot(), listOf(assistant(listOf(ContentBlock.Text("答案")))))
        val msg = messagesOf(request).single()
        assertEquals("", msg["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun assistantWithToolCallsMapsToToolCallsArray() {
        val request = protocol.buildRequest(
            snapshot(),
            listOf(
                assistant(
                    listOf(
                        ContentBlock.ToolCall(
                            "call_1",
                            "get_weather",
                            """{"city":"北京"}"""
                        )
                    )
                )
            )
        )
        val msg = messagesOf(request).single()
        val toolCalls = msg["tool_calls"]!!.jsonArray
        assertEquals(1, toolCalls.size)
        val tc = toolCalls[0].jsonObject
        assertEquals("call_1", tc["id"]!!.jsonPrimitive.content)
        assertEquals("function", tc["type"]!!.jsonPrimitive.content)
        assertEquals("get_weather", tc["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(
            """{"city":"北京"}""",
            tc["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun emptyAssistantMessageIsSkipped() {
        // 无文本无工具调用（被中断的空回复）：跳过，Provider 不接受
        val request = protocol.buildRequest(snapshot(), listOf(assistant(emptyList())))
        assertEquals(0, messagesOf(request).size)
    }

    @Test
    fun toolResultMapsToToolMessage() {
        val request = protocol.buildRequest(
            snapshot(),
            listOf(toolResult("call_1", ToolCallOutcome.Success("""{"temp":26}""")))
        )
        val msg = messagesOf(request).single()
        assertEquals("tool", msg["role"]!!.jsonPrimitive.content)
        assertEquals("call_1", msg["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("""{"temp":26}""", msg["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolResultWithoutContentUsesEmptyString() {
        // 错误结果内容由下游决定，本类不加工；null 用空串
        val request = protocol.buildRequest(
            snapshot(),
            listOf(toolResult("c1", ToolCallOutcome.Failure("boom")))
        )
        val msg = messagesOf(request).single()
        assertEquals("", msg["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun fullHistoryMapsInOrder() {
        val request = protocol.buildRequest(
            snapshot(systemPrompt = "sys"),
            listOf(
                user("你好"),
                assistant(listOf(ContentBlock.ToolCall("c1", "tool-a", "{}"))),
                toolResult("c1", ToolCallOutcome.Success("ok")),
                user("继续")
            )
        )
        assertEquals(
            listOf("system", "user", "assistant", "tool", "user"),
            messagesOf(request).map { it["role"]!!.jsonPrimitive.content }
        )
    }

    // ── buildRequest：tools ───────────────────────────────────────────────

    @Test
    fun toolsSerializeAsFunctions() {
        val tool = ToolDescriptor(
            name = "get_weather",
            description = "查询天气",
            inputSchemaJson = """{"type":"object","properties":{"city":{"type":"string"}}}""",
            kind = ToolKind.Local
        )
        val request = protocol.buildRequest(snapshot(tools = listOf(tool)), emptyList())
        val tools = body(request)["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        val t = tools[0].jsonObject
        assertEquals("function", t["type"]!!.jsonPrimitive.content)
        val fn = t["function"]!!.jsonObject
        assertEquals("get_weather", fn["name"]!!.jsonPrimitive.content)
        assertEquals("查询天气", fn["description"]!!.jsonPrimitive.content)
        assertEquals(
            "object",
            fn["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun toolsOmittedWhenEmpty() {
        val request = protocol.buildRequest(snapshot(), emptyList())
        assertNull(body(request)["tools"])
    }

    @Test
    fun toolWithoutSchemaOmitsParameters() {
        val tool = ToolDescriptor(name = "noop", description = "noop", kind = ToolKind.Local)
        val request = protocol.buildRequest(snapshot(tools = listOf(tool)), emptyList())
        val fn = body(request)["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        assertNull(fn["parameters"])
    }

    // ── parseStream：文本 / 思考 ──────────────────────────────────────────

    @Test
    fun textStreamEmitsDeltasAndCompleted() = runTest {
        val events = parse(
            """{"choices":[{"index":0,"delta":{"content":"你"},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{"content":"好"},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        )
        assertEquals(
            listOf(
                ProtocolEvent.TextDelta("你"),
                ProtocolEvent.TextDelta("好"),
                ProtocolEvent.Completed(null, null, StopReason.Stop)
            ),
            events
        )
    }

    @Test
    fun thinkingStreamEmitsThinkingDelta() = runTest {
        val events = parse(
            """{"choices":[{"index":0,"delta":{"reasoning_content":"推导"},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{"content":"回答"},"finish_reason":"stop"}]}"""
        )
        assertEquals(
            listOf(
                ProtocolEvent.ThinkingDelta("推导"),
                ProtocolEvent.TextDelta("回答"),
                ProtocolEvent.Completed(null, null, StopReason.Stop)
            ),
            events
        )
    }

    @Test
    fun emptyResponseStillCompletes() = runTest {
        val events = parse(
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        )
        assertEquals(listOf(ProtocolEvent.Completed(null, null, StopReason.Stop)), events)
    }

    @Test
    fun lengthFinishReasonMapsToLength() = runTest {
        val events = parse(
            """{"choices":[{"index":0,"delta":{"content":"截断"},"finish_reason":"length"}]}"""
        )
        assertEquals(ProtocolEvent.Completed(null, null, StopReason.Length), events.lastOrNull())
    }

    @Test
    fun modelCarriedIntoCompleted() = runTest {
        val events = parse(
            """{"id":"c1","model":"deepseek-reasoner","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""
        )
        val completed = events.last() as ProtocolEvent.Completed
        assertEquals("deepseek-reasoner", completed.responseModel)
    }

    // ── parseStream：工具调用 ─────────────────────────────────────────────

    @Test
    fun toolCallStreamAssemblesArguments() = runTest {
        val events = parse(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":\"北京\"}"}}]},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
        )
        assertEquals(
            listOf(
                ProtocolEvent.ToolCallStarted("call_1", "get_weather"),
                ProtocolEvent.ToolCallDelta("call_1", "get_weather", """{"city":"北京"}"""),
                ProtocolEvent.ToolCallReady("call_1", "get_weather", """{"city":"北京"}"""),
                ProtocolEvent.Completed(null, null, StopReason.ToolUse)
            ),
            events
        )
    }

    @Test
    fun parallelToolCallsKeepSeparateStateByIndex() = runTest {
        val events = parse(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_a","function":{"name":"a","arguments":"{\"x\":"}}]},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"call_b","function":{"name":"b","arguments":"{\"y\":"}}]},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1}"}}]},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"2}"}}]},"finish_reason":null}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
        )
        assertEquals(
            listOf(
                ProtocolEvent.ToolCallStarted("call_a", "a"),
                ProtocolEvent.ToolCallDelta("call_a", "a", """{"x":"""),
                ProtocolEvent.ToolCallStarted("call_b", "b"),
                ProtocolEvent.ToolCallDelta("call_b", "b", """{"y":"""),
                ProtocolEvent.ToolCallDelta("call_a", "a", "1}"),
                ProtocolEvent.ToolCallDelta("call_b", "b", "2}"),
                ProtocolEvent.ToolCallReady("call_a", "a", """{"x":1}"""),
                ProtocolEvent.ToolCallReady("call_b", "b", """{"y":2}"""),
                ProtocolEvent.Completed(null, null, StopReason.ToolUse)
            ),
            events
        )
    }

    @Test
    fun toolCallsWithNoFinishReasonIsError() = runTest {
        val events = parse(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"t","arguments":"{}"}}]},"finish_reason":null}]}"""
        )
        assertTrue(events.last() is ProtocolEvent.Error)
    }

    // ── parseStream：usage ────────────────────────────────────────────────

    @Test
    fun usageChunkCarriedIntoCompleted() = runTest {
        val events = parse(
            """{"choices":[{"index":0,"delta":{"content":"答"},"finish_reason":"stop"}]}""",
            """{"id":"c1","model":"deepseek-chat","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
        )
        val completed = events.last() as ProtocolEvent.Completed
        assertEquals(Usage(10, 5, 0, 0, 0), completed.usage)
    }

    @Test
    fun usageParsesCacheAndReasoningTokens() = runTest {
        val events = parse(
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":30,"prompt_tokens_details":{"cached_tokens":20,"cache_write_tokens":5},"completion_tokens_details":{"reasoning_tokens":10}}}"""
        )
        val completed = events.last() as ProtocolEvent.Completed
        assertEquals(Usage(75, 30, 20, 5, 10), completed.usage)
    }

    // ── parseStream：失败路径 ─────────────────────────────────────────────

    @Test
    fun nonJsonDataEmitsError() = runTest {
        val events = parse("not-json")
        assertTrue(events.single() is ProtocolEvent.Error)
    }

    @Test
    fun doneMarkerIgnored() = runTest {
        val events = parse(
            """{"choices":[{"index":0,"delta":{"content":"答"},"finish_reason":"stop"}]}""",
            "[DONE]"
        )
        // [DONE] 不产生事件；流正常产出 TextDelta + Completed
        assertEquals(
            listOf(
                ProtocolEvent.TextDelta("答"),
                ProtocolEvent.Completed(null, null, StopReason.Stop)
            ),
            events
        )
    }

    @Test
    fun unknownFinishReasonEmitsError() = runTest {
        val events = parse(
            """{"choices":[{"index":0,"delta":{},"finish_reason":"content_filter"}]}"""
        )
        assertTrue(events.single() is ProtocolEvent.Error)
    }

    @Test
    fun streamWithoutFinishReasonEmitsError() = runTest {
        val events = parse(
            """{"choices":[{"index":0,"delta":{"content":"话没说完"}}]}"""
        )
        // 部分文本产出后流结束无 finish_reason：明确失败
        assertEquals(listOf(ProtocolEvent.TextDelta("话没说完")), events.dropLast(1))
        assertTrue(events.last() is ProtocolEvent.Error)
    }

    @Test
    fun keepAliveCommentLinesIgnored() = runTest {
        val events = protocol.parseStream(
            listOf(
                SseLine(null),
                SseLine("""data: {"choices":[{"index":0,"delta":{"content":"答"},"finish_reason":"stop"}]}"""),
                SseLine("")
            ).asFlow()
        ).toList()
        // 注释行不产出事件，也不影响后续解析
        assertEquals(
            listOf(
                ProtocolEvent.TextDelta("答"),
                ProtocolEvent.Completed(null, null, StopReason.Stop)
            ),
            events
        )
    }

    // ── encodeToolResult ──────────────────────────────────────────────────

    @Test
    fun encodeToolResultWrapsOutcomeFaithfully() {
        val call = ContentBlock.ToolCall("call_1", "tool-a", "{}")
        val outcomes = listOf<ToolCallOutcome>(
            ToolCallOutcome.Success("ok"),
            ToolCallOutcome.Failure("boom", "detail"),
            ToolCallOutcome.Intercepted("blocked", "cached", true),
            ToolCallOutcome.Interrupted("partial"),
            ToolCallOutcome.Unknown("unknown", "partial")
        )
        outcomes.forEach { outcome ->
            val message = protocol.encodeToolResult(call, outcome)
            assertEquals(Message.ToolResult("call_1", "tool-a", outcome), message)
        }
    }

    // ── withCodec ─────────────────────────────────────────────────────────

    @Test
    fun withCodecReturnsNewInstance() {
        val other = protocol.withCodec(Json { prettyPrint = true }) as OpenAIChatCompletionProtocol
        assertTrue(other !== protocol)
        assertEquals("deepseek", other.id)
    }

    // ── OpenAI 官方 compat 形态 ───────────────────────────────────────────

    private val openai = OpenAIChatCompletionProtocol(compat = OpenAIChatCompletionCompat())

    @Test
    fun openaiCompatCarriesIdentityAndEndpoint() {
        assertEquals("openai", openai.id)
        assertEquals("https://api.openai.com/v1/chat/completions", openai.defaultEndpoint)
    }

    @Test
    fun openaiCompatUsesMaxCompletionTokensField() {
        val request = openai.buildRequest(snapshot(maxTokens = 1024), emptyList())
        val json = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals(1024, json["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        assertNull(json["max_tokens"])
    }

    @Test
    fun openaiReasoningDeltaEmitsThinking() = runTest {
        // OpenAI 官方：delta.reasoning 对象（encrypted_content 不可读，忽略）
        val events = openai.parseStream(
            sse(
                """{"choices":[{"index":0,"delta":{"reasoning":{"content":"推导"}},"finish_reason":null}]}""",
                """{"choices":[{"index":0,"delta":{"content":"回答"},"finish_reason":"stop"}]}"""
            )
        ).toList()
        assertEquals(
            listOf(
                ProtocolEvent.ThinkingDelta("推导"),
                ProtocolEvent.TextDelta("回答"),
                ProtocolEvent.Completed(null, null, StopReason.Stop)
            ),
            events
        )
    }

    @Test
    fun openaiAssistantThinkingConvertsToText() {
        // OpenAI 官方不接受 reasoning_content 字段：思考按 requiresThinkingAsText 转文本
        val request = openai.buildRequest(
            snapshot(),
            listOf(assistant(listOf(ContentBlock.Thinking("推导"), ContentBlock.Text("答案"))))
        )
        val msg = messagesOf(request).single()
        assertEquals("assistant", msg["role"]!!.jsonPrimitive.content)
        assertEquals("推导\n答案", msg["content"]!!.jsonPrimitive.content)
        assertNull(msg["reasoning_content"])
    }

    @Test
    fun openaiAssistantWithoutReasoningFieldWhenNoThinking() {
        // 无思考时 OpenAI 官方不补 reasoning_content 字段（与 DeepSeek 空串不同）
        val request =
            openai.buildRequest(snapshot(), listOf(assistant(listOf(ContentBlock.Text("答案")))))
        val msg = messagesOf(request).single()
        assertEquals("答案", msg["content"]!!.jsonPrimitive.content)
        assertNull(msg["reasoning_content"])
    }

    @Test
    fun deepSeekCompatStillUsesMaxTokensAndReasoningContent() {
        // 默认装配（DeepSeek compat）行为不变：max_tokens + reasoning_content 空串
        val request = protocol.buildRequest(snapshot(maxTokens = 1024), emptyList())
        val json = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals(1024, json["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertNull(json["max_completion_tokens"])
        assertEquals("deepseek", protocol.id)
        assertEquals("https://api.deepseek.com/chat/completions", protocol.defaultEndpoint)
        assertTrue(protocol.compat is DeepSeekCompat)
    }
}
