package com.niki914.okia.protocol

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.SseLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4 适配壳测试：ProtocolCompatMapper.from(protocol) 是纯委托，
 * 每个方法透传给协议实例，不加工数据。
 */
class ProtocolCompatMapperTest {

    /** 记录每次调用的协议 fake。 */
    private class RecordingProtocol : ChatProtocol {
        var buildRequestCalls = 0
        var encodeToolResultCalls = 0
        var parseStreamCalls = 0
        var useApiKeyCalls = 0
        private val deepSeekCompat = DeepSeekCompat()

        override val id: String = "recording"
        override val defaultEndpoint: String? = null
        override fun withCodec(codec: Json): ChatProtocol = this
        override fun useApiKey(apiKey: String): Map<String, String> {
            useApiKeyCalls++
            return if (apiKey.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
        }
        override fun buildRequest(snapshot: RequestSnapshot, history: List<Message>): HttpRequest {
            buildRequestCalls++
            return HttpRequest(snapshot.endpoint, "POST", emptyMap(), null, snapshot.timeouts)
        }
        override fun parseStream(rawSseLines: Flow<SseLine>): Flow<ProtocolEvent> {
            parseStreamCalls++
            return emptyFlow()
        }
        override fun encodeToolResult(call: ContentBlock.ToolCall, outcome: ToolCallOutcome): Message {
            encodeToolResultCalls++
            return Message.ToolResult(call.id, call.name, outcome)
        }
        override val compat: Compat get() = deepSeekCompat
    }

    private fun snapshot() = RequestSnapshot(
        endpoint = "https://example.com",
        apiKey = "sk",
        model = "m",
        systemPrompt = null,
        temperature = 0.7f,
        maxTokens = 100,
        headers = emptyMap(),
        timeouts = HttpTimeouts(1, 2, 3),
        tools = emptyList()
    )

    @Test
    fun buildRequestDelegates() = runTest {
        val protocol = RecordingProtocol()
        val mapper = ProtocolCompatMapper.from(protocol)
        mapper.buildRequest(snapshot(), emptyList())
        assertEquals(1, protocol.buildRequestCalls)
    }

    @Test
    fun encodeToolResultDelegates() = runTest {
        val protocol = RecordingProtocol()
        val mapper = ProtocolCompatMapper.from(protocol)
        val outcome = ToolCallOutcome.Success("ok")
        val message = mapper.encodeToolResult(ContentBlock.ToolCall("c", "t", "{}"), outcome)
        assertEquals(Message.ToolResult("c", "t", outcome), message)
        assertEquals(1, protocol.encodeToolResultCalls)
    }

    @Test
    fun parseStreamDelegates() = runTest {
        val protocol = RecordingProtocol()
        val mapper = ProtocolCompatMapper.from(protocol)
        val events = mapper.parseStream(emptyFlow()).toList()
        assertTrue(events.isEmpty())
        assertEquals(1, protocol.parseStreamCalls)
    }

    @Test
    fun useApiKeyDelegates() {
        val protocol = RecordingProtocol()
        val mapper = ProtocolCompatMapper.from(protocol)
        assertEquals(mapOf("Authorization" to "Bearer k"), mapper.useApiKey("k"))
        assertEquals(1, protocol.useApiKeyCalls)
    }

    @Test
    fun compatExposedFromProtocol() {
        val protocol = RecordingProtocol()
        val mapper = ProtocolCompatMapper.from(protocol)
        assertTrue(mapper.compat is DeepSeekCompat)
    }
}
