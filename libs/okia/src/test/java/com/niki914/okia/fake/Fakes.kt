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
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolExecutor
import com.niki914.okia.tooling.ToolKind
import com.niki914.okia.transport.HttpEngine
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpResponse
import com.niki914.okia.transport.SseLine
import com.niki914.okia.transport.StreamResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * 协议边界 fake：每次 parseStream 调用返回下一轮事件（多轮工具循环用）。
 * 轮次模型对齐真实协议：每次请求是独立的新响应，从对应轮次从头发射；
 * 超出轮次数时重复最后一段（防御，测试不应触发）。
 * 单 List 构造 = 单轮（兼容 T2/T5）；Flow 构造 = 每次返回同一流
 * （无限热流场景，如取消测试的 SharedFlow）。
 */
class FakeProtocolMapper(
    private val rounds: List<List<ProtocolEvent>>,
    private val sharedFlow: Flow<ProtocolEvent>? = null
) : ProtocolCompatMapper {
    constructor(events: List<ProtocolEvent>) : this(listOf(events))

    constructor(events: Flow<ProtocolEvent>) : this(emptyList(), events)

    val builtRequests = mutableListOf<HttpRequest>()
    val builtHistories = mutableListOf<List<Message>>()
    var buildRequestError: Throwable? = null

    // parseStream 被调用的次数（T3 前置校验断言：非 2xx / HTML 不进入解析）
    var parseStreamCalls = 0
    private var roundIndex = 0

    override suspend fun buildRequest(snapshot: RequestSnapshot, history: List<Message>): HttpRequest {
        buildRequestError?.let { throw it }
        builtHistories += history
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
        sharedFlow?.let { return it }
        val index = roundIndex.coerceAtMost(rounds.lastIndex)
        roundIndex++
        return rounds[index].asFlow()
    }

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

/**
 * 可编程工具 executor：记录调用、可注入 outcome / 延迟 / 异常。
 * 断言执行参数（改写验证）与执行次数（阻断 / 短路验证）都落在这个记录上。
 */
class RecordingToolExecutor : ToolExecutor {
    val calls = mutableListOf<ToolCallContext>()
    var outcome: ToolCallOutcome = ToolCallOutcome.Success("ok")
    var executeError: Throwable? = null
    var executeDelayMs: Long = 0
    var onExecute: (suspend (ToolCallContext) -> Unit)? = null

    override suspend fun execute(call: ToolCallContext): ToolCallOutcome {
        calls += call
        onExecute?.invoke(call)
        if (executeDelayMs > 0) delay(executeDelayMs)
        executeError?.let { throw it }
        return outcome
    }

    override fun onInterrupt(call: ToolCallContext): ToolCallOutcome = ToolCallOutcome.Interrupted()
}

/** 本地工具描述快捷构造。 */
fun localTool(name: String = "tool", description: String = "desc"): ToolDescriptor =
    ToolDescriptor(name, description, null, ToolKind.Local)
