package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.json.GsonJsonCodec
import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.net.HttpFrame
import com.niki914.s3ss10n.net.HttpRequest
import com.niki914.s3ss10n.net.HttpTimeouts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

internal class RecordingChatProtocol(
    private val parseHandler: (Flow<String>) -> Flow<ProtocolEvent>
) : ChatProtocol {
    var lastSnapshot: SessionSnapshot? = null
    var lastHistory: List<ChatTurn> = emptyList()
    var lastPendingUserInput: String? = null
    var lastRequest: HttpRequest? = null
    var apiKeyHeaders: Map<String, String> = emptyMap()

    override fun useApiKey(apiKey: String): Map<String, String> = apiKeyHeaders

    override fun buildRequest(
        snapshot: SessionSnapshot,
        history: List<ChatTurn>,
        pendingUserInput: String?
    ): HttpRequest {
        lastSnapshot = snapshot
        lastHistory = history
        lastPendingUserInput = pendingUserInput
        val request = HttpRequest(
            method = "POST",
            url = snapshot.endpoint,
            headers = emptyMap(),
            body = null,
            timeoutMs = HttpTimeouts(
                connectMs = 1_000,
                readMs = 1_000,
                writeMs = 1_000
            )
        )
        lastRequest = request
        return request
    }

    override fun parseStream(rawSseLines: Flow<String>): Flow<ProtocolEvent> = parseHandler(rawSseLines)

    override fun encodeToolResult(
        callId: String,
        toolName: String,
        resultJson: String
    ): ChatTurn.ToolResult {
        return ChatTurn.ToolResult(
            callId = callId,
            toolName = toolName,
            resultJson = resultJson
        )
    }
}

internal class FakeHttpEngine(
    private val streamHandler: (HttpRequest) -> Flow<String> = { emptyFlow() },
    private val unaryHandler: suspend (HttpRequest) -> String = {
        error("Unexpected unary request: ${it.url}")
    },
    private val frameHandler: (HttpRequest) -> Flow<HttpFrame> = { request ->
        streamHandler(request).map { HttpFrame.Text(it) }
    }
) : HttpEngine {
    val streamRequests = mutableListOf<HttpRequest>()
    val unaryRequests = mutableListOf<HttpRequest>()
    val frameRequests = mutableListOf<HttpRequest>()
    var closed: Boolean = false

    override fun stream(request: HttpRequest): Flow<String> {
        streamRequests += request
        return streamHandler(request)
    }

    override suspend fun unary(request: HttpRequest): String {
        unaryRequests += request
        return unaryHandler(request)
    }

    override fun frames(request: HttpRequest): Flow<HttpFrame> {
        frameRequests += request
        return frameHandler(request)
    }

    override fun close() {
        closed = true
    }
}

internal class FakeMcpHttpEngine(
    private val toolsByUrl: Map<String, List<McpDiscoveredTool>>,
    failuresByUrl: Map<String, Throwable> = emptyMap(),
    private val toolCallResultsByUrl: Map<String, String> = emptyMap(),
    private val toolCallFailuresByUrl: Map<String, Throwable> = emptyMap(),
    private val sseMethodsByUrl: Map<String, Set<String>> = emptyMap(),
    private val codec: JsonCodec = GsonJsonCodec()
) : HttpEngine {
    val unaryCalls = mutableListOf<Pair<String, String>>()
    val frameCalls = mutableListOf<Pair<String, String>>()
    val failuresByUrl: MutableMap<String, Throwable> = failuresByUrl.toMutableMap()
    var beforeToolsListResponse: (suspend (url: String) -> Unit)? = null

    override fun stream(request: HttpRequest): Flow<String> = emptyFlow()

    override suspend fun unary(request: HttpRequest): String {
        val method = rpcMethod(request)
        unaryCalls += request.url to method
        return responseBody(request, method)
    }

    override fun frames(request: HttpRequest): Flow<HttpFrame> {
        if (request.body == null && request.url == "https://example.com/chat") return emptyFlow()
        val method = rpcMethod(request)
        frameCalls += request.url to method
        return flow {
            val body = responseBody(request, method)
            val frame = if (sseMethodsByUrl[request.url]?.contains(method) == true) {
                HttpFrame.SseData(body, event = "message")
            } else {
                HttpFrame.Text(body)
            }
            emit(frame)
        }
    }

    override fun close() = Unit

    private suspend fun responseBody(request: HttpRequest, method: String): String {
        if (method == "tools/list") {
            beforeToolsListResponse?.invoke(request.url)
            failuresByUrl[request.url]?.let { throw it }
        }
        return when (method) {
            "initialize" -> codec.encode(
                response(request, mapOf(
                    "result" to mapOf(
                        "protocolVersion" to "2025-06-18"
                    )
                ))
            )

            "notifications/initialized" -> codec.encode(
                response(request, mapOf("result" to emptyMap<String, Any?>()))
            )

            "tools/list" -> {
                val tools = toolsByUrl[request.url]
                    ?: error("Unexpected tools/list request: ${request.url}")
                codec.encode(
                    response(request, mapOf(
                        "result" to mapOf(
                            "tools" to tools.map { tool ->
                                mapOf(
                                    "name" to tool.name,
                                    "description" to tool.description,
                                    "inputSchema" to tool.inputSchema
                                )
                            }
                        )
                    ))
                )
            }

            "tools/call" -> {
                toolCallFailuresByUrl[request.url]?.let { throw it }
                val resultJson = toolCallResultsByUrl[request.url]
                    ?: error("Unexpected tools/call request: ${request.url}")
                codec.encode(
                    response(request, mapOf(
                        "result" to mapOf(
                            "content" to listOf(
                                mapOf(
                                    "type" to "text",
                                    "text" to resultJson
                                )
                            )
                        )
                    ))
                )
            }

            else -> error("Unexpected RPC method: $method")
        }
    }

    private fun rpcMethod(request: HttpRequest): String {
        val payload = rpcPayload(request)
        return payload["method"] as? String
            ?: error("Missing RPC method: ${request.body?.toString(Charsets.UTF_8)}")
    }

    private fun rpcPayload(request: HttpRequest): Map<String, Any?> {
        val body = request.body?.toString(Charsets.UTF_8)
            ?: error("Missing request body")
        return codec.decodeMap(body)
            ?: error("Invalid JSON body: $body")
    }

    private fun response(request: HttpRequest, values: Map<String, Any?>): Map<String, Any?> {
        val payload = rpcPayload(request)
        val response = linkedMapOf<String, Any?>("jsonrpc" to "2.0")
        payload["id"]?.let { response["id"] = it }
        response.putAll(values)
        return response
    }
}

internal fun newChatSession(
    protocol: ChatProtocol,
    engine: HttpEngine,
    configure: SessionConfig.() -> Unit = {}
): ChatSession {
    val config = SessionConfig().apply {
        endpoint = "https://example.com/chat"
        model = "test-model"
        httpEngine = engine
        configure()
    }
    return ChatSession(initialConfig = config, protocol = protocol)
}
