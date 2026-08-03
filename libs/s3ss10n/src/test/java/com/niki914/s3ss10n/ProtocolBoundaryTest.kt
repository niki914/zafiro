package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.json.GsonJsonCodec
import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import com.niki914.s3ss10n.ext.protocol.anthropic.AnthropicProtocol
import com.niki914.s3ss10n.ext.protocol.openai.OpenAIProtocol
import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.SseEvent
import com.niki914.s3ss10n.net.SseLineParser
import com.niki914.s3ss10n.net.HttpTimeouts
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolBoundaryTest {
    @Test
    fun `OpenAI 普通文本 delta 解析为 TextDelta`() = runBlocking {
        val events = collectOpenAIEvents(
            """{"choices":[{"delta":{"content":"hello"}}]}"""
        )

        assertTrue(events.contains(ProtocolEvent.TextDelta("hello")))
        assertCompleted(events)
    }

    @Test
    fun `OpenAI reasoning content 解析为 ReasoningDelta`() = runBlocking {
        val events = collectOpenAIEvents(
            """{"choices":[{"delta":{"reasoning_content":"why"}}]}"""
        )

        assertTrue(events.contains(ProtocolEvent.ReasoningDelta("why")))
        assertCompleted(events)
    }

    @Test
    fun `OpenAI tool call arguments 分片累积到完整 JSON 后发出 ToolCallReady`() = runBlocking {
        val events = collectOpenAIEvents(
            """
            {
              "choices": [
                {
                  "delta": {
                    "tool_calls": [
                      {
                        "id": "call-1",
                        "type": "function",
                        "function": {
                          "name": "lookup",
                          "arguments": "{\"query\":"
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent(),
            """
            {
              "choices": [
                {
                  "delta": {
                    "tool_calls": [
                      {
                        "type": "function",
                        "function": {
                          "arguments": "\"hi\"}"
                        }
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                ProtocolEvent.ToolCallReady(
                    callId = "call-1",
                    toolName = "lookup",
                    argumentsJson = """{"query":"hi"}"""
                )
            ),
            events.filterIsInstance<ProtocolEvent.ToolCallReady>()
        )
        assertCompleted(events)
    }

    @Test
    fun `OpenAI invalid frame 解析为 Parse error`() = runBlocking {
        val events = collectOpenAIEvents("not-json")
        val error = events.filterIsInstance<ProtocolEvent.Error>().single()

        assertEquals(SessionEvent.Stage.Parse, error.stage)
        assertCompleted(events)
    }

    @Test
    fun `Anthropic text delta 解析为 TextDelta`() = runBlocking {
        val events = collectAnthropicEvents(
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"hello"}}"""
        )

        assertTrue(events.contains(ProtocolEvent.TextDelta("hello")))
        assertCompleted(events)
    }

    @Test
    fun `Anthropic thinking 和 signature delta 解析为 reasoning events`() = runBlocking {
        val events = collectAnthropicEvents(
            """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"think"}}""",
            """{"type":"content_block_delta","delta":{"type":"signature_delta","signature":"sig"}}"""
        )

        assertTrue(events.contains(ProtocolEvent.ReasoningDelta("think")))
        assertTrue(events.contains(ProtocolEvent.ReasoningSignature("sig")))
        assertCompleted(events)
    }

    @Test
    fun `Anthropic tool_use input_json_delta 累积到 content_block_stop 后发出 ToolCallReady`() = runBlocking {
        val events = collectAnthropicEvents(
            """
            {
              "type": "content_block_start",
              "content_block": {
                "type": "tool_use",
                "id": "toolu-1",
                "name": "lookup"
              }
            }
            """.trimIndent(),
            """{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"{\"query\":"}}""",
            """{"type":"content_block_delta","delta":{"type":"input_json_delta","partial_json":"\"hi\"}"}}""",
            """{"type":"content_block_stop"}"""
        )

        assertEquals(
            listOf(
                ProtocolEvent.ToolCallReady(
                    callId = "toolu-1",
                    toolName = "lookup",
                    argumentsJson = """{"query":"hi"}"""
                )
            ),
            events.filterIsInstance<ProtocolEvent.ToolCallReady>()
        )
        assertCompleted(events)
    }

    @Test
    fun `Anthropic error frame 解析为 Transport error`() = runBlocking {
        val events = collectAnthropicEvents(
            """{"type":"error","error":{"message":"bad request"}}"""
        )
        val error = events.filterIsInstance<ProtocolEvent.Error>().single()

        assertEquals(SessionEvent.Stage.Transport, error.stage)
        assertEquals("bad request", error.cause.message)
        assertCompleted(events)
    }

    @Test
    fun `OpenAI 和 Anthropic encodeToolResult 保持透传行为`() {
        val expected = ChatTurn.ToolResult(
            callId = "call-1",
            toolName = "lookup",
            resultJson = """{"ok":true}"""
        )

        assertEquals(
            expected,
            OpenAIProtocol(GsonJsonCodec()).encodeToolResult("call-1", "lookup", """{"ok":true}""")
        )
        assertEquals(
            expected,
            AnthropicProtocol(GsonJsonCodec()).encodeToolResult("call-1", "lookup", """{"ok":true}""")
        )
    }

    @Test
    fun `OpenAI 和 Anthropic 请求体保留 assistant tool call 与 tool result 对应关系`() {
        val codec = GsonJsonCodec()
        val history = listOf(
            ChatTurn.Assistant(
                content = "",
                toolCalls = listOf(
                    ToolCallSpec(
                        callId = "call-1",
                        toolName = "lookup",
                        argumentsJson = """{"query":"hi"}"""
                    )
                )
            ),
            ChatTurn.ToolResult(
                callId = "call-1",
                toolName = "lookup",
                resultJson = """{"ok":true}"""
            )
        )

        val openAiBody = OpenAIProtocol(codec)
            .buildRequest(testSnapshot(codec), history, pendingUserInput = "next")
            .bodyString()
        val anthropicBody = AnthropicProtocol(codec)
            .buildRequest(testSnapshot(codec), history, pendingUserInput = "next")
            .bodyString()

        assertTrue(openAiBody.contains(""""tool_call_id":"call-1""""))
        assertTrue(openAiBody.contains(""""id":"call-1""""))
        assertTrue(openAiBody.contains(""""name":"lookup""""))
        assertTrue(anthropicBody.contains(""""type":"tool_use""""))
        assertTrue(anthropicBody.contains(""""id":"call-1""""))
        assertTrue(anthropicBody.contains(""""type":"tool_result""""))
        assertTrue(anthropicBody.contains(""""tool_use_id":"call-1""""))
    }

    @Test
    fun `SSE parseEvents 支持 event 多行 data 和注释行`() = runBlocking {
        val events = SseLineParser.parseEvents(
            flowOf(
                ": keep-alive",
                "event: message",
                "data: a",
                "data: b",
                "",
                "data: tail",
                ""
            )
        ).toList()

        assertEquals(
            listOf(
                SseEvent(event = "message", data = "a\nb"),
                SseEvent(event = null, data = "tail")
            ),
            events
        )
    }

    @Test
    fun `SSE parse 遇到 DONE 后停止输出`() = runBlocking {
        val payloads = SseLineParser.parse(
            flowOf(
                "data: first",
                "",
                "data: [DONE]",
                "",
                "data: ignored",
                ""
            )
        ).toList()

        assertEquals(listOf("first"), payloads)
    }

    private suspend fun collectOpenAIEvents(vararg frames: String): List<ProtocolEvent> {
        return OpenAIProtocol(GsonJsonCodec()).parseStream(flowOf(*frames)).toList()
    }

    private suspend fun collectAnthropicEvents(vararg frames: String): List<ProtocolEvent> {
        return AnthropicProtocol(GsonJsonCodec()).parseStream(flowOf(*frames)).toList()
    }

    private fun testSnapshot(codec: JsonCodec): SessionSnapshot {
        return SessionSnapshot(
            endpoint = "https://example.com/chat",
            apiKey = "",
            model = "test-model",
            systemPrompt = null,
            temperature = 0.7f,
            timeouts = HttpTimeouts(connectMs = 1_000, readMs = 1_000, writeMs = 1_000),
            hooksBlock = null,
            appParams = emptyMap(),
            tools = ToolCatalog(descriptors = emptyList()),
            mcpServers = emptyMap(),
            jsonCodec = codec,
            headers = emptyMap(),
            maxTokens = 4096
        )
    }

    private fun com.niki914.s3ss10n.net.HttpRequest.bodyString(): String {
        return body?.toString(Charsets.UTF_8).orEmpty()
    }

    private fun assertCompleted(events: List<ProtocolEvent>) {
        assertEquals(ProtocolEvent.Completed, events.last())
    }
}
