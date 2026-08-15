package com.niki914.okia.fake

import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.AgentLoop
import com.niki914.okia.loop.CompletionReason
import com.niki914.okia.loop.LoopRequest
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.mcp.McpCallResult
import com.niki914.okia.mcp.McpClient
import com.niki914.okia.mcp.McpDiscoveredTool
import com.niki914.okia.mcp.McpServer
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.protocol.Compat
import com.niki914.okia.protocol.DeepSeekCompat
import com.niki914.okia.protocol.ProtocolCompatMapper
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.protocol.RequestSnapshot
import com.niki914.okia.transport.HttpEngine
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpResponse
import com.niki914.okia.transport.SseLine
import com.niki914.okia.transport.StreamResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 协议边界 fake：测试直接控制 ProtocolEvent 序列（parseStream 忽略
 * 原始 SseLine 流——T2 不验证真实解析，那是 T3/T4 职责）。
 */
class FakeProtocolMapper(private val events: Flow<ProtocolEvent>) : ProtocolCompatMapper {
    constructor(events: List<ProtocolEvent>) : this(events.asFlow())

    val builtRequests = mutableListOf<HttpRequest>()
    var buildRequestError: Throwable? = null

    override suspend fun buildRequest(snapshot: RequestSnapshot, history: List<Message>): HttpRequest {
        buildRequestError?.let { throw it }
        val request = HttpRequest(
            url = snapshot.endpoint,
            method = "POST",
            headers = useApiKey(snapshot.apiKey),
            body = null,
            timeouts = snapshot.timeouts
        )
        builtRequests += request
        return request
    }

    override suspend fun encodeToolResult(call: ContentBlock.ToolCall, outcome: ToolCallOutcome): Message =
        Message.ToolResult(call.id, call.name, outcome)

    override fun parseStream(rawSseLines: Flow<SseLine>): Flow<ProtocolEvent> {
        parseStreamCalls++
        return events
    }

    // parseStream 被调用的次数（T3 前置校验断言：非 2xx / HTML 不进入解析）
    var parseStreamCalls = 0

    override fun useApiKey(apiKey: String): Map<String, String> =
        if (apiKey.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    override val compat: Compat get() = DeepSeekCompat()
}

/** 传输 fake：默认 200 + 空行流；可注入 streamError / 自定义响应。 */
class FakeHttpEngine : HttpEngine {
    var streamError: Throwable? = null
    var streamResult: () -> StreamResponse = { StreamResponse.Ok(200, emptyMap(), emptyFlow()) }
    val streamedRequests = mutableListOf<HttpRequest>()

    override suspend fun stream(request: HttpRequest): StreamResponse {
        streamedRequests += request
        streamError?.let { throw it }
        return streamResult()
    }

    override suspend fun unary(request: HttpRequest): HttpResponse = TODO("MCP transport lands in T8")

    override fun close(): Unit = Unit
}

/** 回合驱动 fake：行为可注入（默认直接完成）。 */
class FakeAgentLoop(
    var behavior: suspend (LoopRequest, suspend (TurnEvent) -> Unit) -> TurnResult =
        { _, _ -> TurnResult.Completed(CompletionReason.Stop) }
) : AgentLoop {
    override suspend fun run(request: LoopRequest, onEvent: suspend (TurnEvent) -> Unit): TurnResult =
        behavior(request, onEvent)
}

/** MCP 客户端 stub：T2 门面不消费，仅满足依赖装配。 */
object StubMcpClient : McpClient {
    override suspend fun discoverTools(server: McpServer): List<McpDiscoveredTool> = emptyList()
    override suspend fun callTool(server: McpServer, toolName: String, argumentsJson: String): McpCallResult =
        McpCallResult(false, emptyList())
}
