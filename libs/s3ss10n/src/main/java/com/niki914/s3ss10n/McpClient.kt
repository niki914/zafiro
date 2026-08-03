package com.niki914.s3ss10n

import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.net.HttpFrame
import com.niki914.s3ss10n.net.HttpRequest
import com.niki914.s3ss10n.net.HttpTimeouts
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

internal interface McpClient {
    suspend fun call(server: McpServerConfig, toolName: String, argumentsJson: String): String
    suspend fun listTools(server: McpServerConfig): List<McpDiscoveredTool>
}

internal class HttpMcpClient(
    private val engine: HttpEngine,
    private val codec: JsonCodec
) : McpClient {
    private val lifecycleCache = McpLifecycleCache()

    private data class JsonRpcPayload(val id: String?, val body: String)

    private fun jsonRpcRequest(
        method: String,
        params: Map<String, Any?> = emptyMap(),
        expectResponse: Boolean = true
    ): JsonRpcPayload {
        val id = if (expectResponse) System.currentTimeMillis().toString() else null
        val request = linkedMapOf<String, Any?>(
            "jsonrpc" to "2.0",
            "method" to method,
            "params" to params
        )
        if (id != null) {
            request["id"] = id
        }
        return JsonRpcPayload(id = id, body = codec.encode(request))
    }

    override suspend fun call(server: McpServerConfig, toolName: String, argumentsJson: String): String {
        val transport = server.transport
        if (transport !is McpTransport.Http || transport.url.isBlank()) {
            throw IllegalArgumentException("Unsupported MCP transport")
        }
        val fingerprint = server.discoveryFingerprint("call")
        xTrySuspend("McpClient.call", onError = { t ->
            throw IllegalStateException("MCP initialize failed: ${t.message}", t)
        }) {
            ensureInitialized(server, fingerprint)
        }
        val arguments = codec.decodeMap(argumentsJson) ?: emptyMap<String, Any?>()
        val root = sendJsonRpc(
            server = server,
            method = "tools/call",
            params = mapOf("name" to toolName, "arguments" to arguments)
        )
        return normalizeResult(root)
    }

    private fun normalizeResult(root: Map<String, Any?>): String {
        val error = root["error"]
        if (error != null) {
            val errorMap = error as? Map<*, *>
            val errMsg = errorMap?.get("message") as? String ?: "MCP tool call error"
            return codec.encode(mapOf("error" to errMsg))
        }
        val result = root["result"] ?: return codec.encode(mapOf("error" to "Missing result"))
        val resultMap = result as? Map<*, *> ?: return codec.encode(result)
        val isError = resultMap["isError"] as? Boolean == true
        val normalized: String? = normalizeStructuredContent(resultMap)
            ?: normalizeContentArray(resultMap)
        if (normalized != null) {
            if (isError) {
                return codec.encode(
                    mapOf("error" to "MCP tool error", "detail" to codec.decodeMap(normalized))
                )
            }
            return normalized
        }
        return codec.encode(resultMap)
    }

    private fun normalizeStructuredContent(result: Map<*, *>): String? {
        val sc = result["structuredContent"] ?: return null
        return sc as? String ?: codec.encode(sc)
    }

    private fun normalizeContentArray(result: Map<*, *>): String? {
        val content = result["content"] as? List<*> ?: return null
        if (content.isEmpty()) return null
        val first = content.first()
        if (first is Map<*, *>) {
            val type = first["type"] as? String
            if (type == "text") {
                val text = first["text"] as? String ?: return null
                val parsed = codec.decodeMap(text)
                if (parsed != null) {
                    return codec.encode(parsed)
                }
                return text
            }
        }
        return codec.encode(content)
    }

    override suspend fun listTools(server: McpServerConfig): List<McpDiscoveredTool> {
        val transport = server.transport
        if (transport !is McpTransport.Http || transport.url.isBlank()) {
            throw IllegalArgumentException("Unsupported MCP transport")
        }
        val fingerprint = server.discoveryFingerprint("discovery")
        xTrySuspend("McpClient.listTools") {
            ensureInitialized(server, fingerprint)
        }
        val root = sendJsonRpc(server, "tools/list")
        val result = root["result"].asStringMap()
            ?: throw IllegalStateException("Missing MCP tools/list result")
        val tools = result["tools"] as? List<*>
            ?: throw IllegalStateException("Missing MCP tools/list tools")
        return tools.mapNotNull { item ->
            val tool = item.asStringMap() ?: return@mapNotNull null
            val name = tool["name"] as? String ?: return@mapNotNull null
            val schema = tool["inputSchema"].asStringMap() ?: emptyMap()
            McpDiscoveredTool(
                name = name,
                description = tool["description"] as? String ?: "",
                inputSchema = schema
            )
        }
    }

    private suspend fun ensureInitialized(server: McpServerConfig, fingerprint: String) {
        if (lifecycleCache.isInitialized(fingerprint)) return
        val initRoot = sendJsonRpc(
            server = server,
            method = "initialize",
            params = mapOf(
                "protocolVersion" to "2025-06-18",
                "capabilities" to emptyMap<String, Any?>(),
                "clientInfo" to mapOf("name" to "com.niki914.s3ss10n", "version" to "2.1.6")
            )
        )
        if (initRoot["error"] != null) {
            val errMsg = (initRoot["error"] as? Map<*, *>)?.get("message") ?: "initialize failed"
            throw IllegalStateException("MCP initialize error: $errMsg")
        }
        sendJsonRpcNotification(server, "notifications/initialized")
        lifecycleCache.markInitialized(fingerprint)
    }

    private suspend fun sendJsonRpc(
        server: McpServerConfig,
        method: String,
        params: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> {
        val transport = server.transport as? McpTransport.Http
            ?: throw IllegalArgumentException("Unsupported MCP transport")
        val payload = jsonRpcRequest(method, params)
        val request = mcpRequest(transport.url, server, payload.body)
        return engine.frames(request)
            .mapNotNull { frame ->
                val root = codec.decodeMap(frame.payload()) ?: return@mapNotNull null
                if (root.matchesJsonRpcResponse(payload.id, allowIdless = frame is HttpFrame.Text)) {
                    root
                } else {
                    null
                }
            }
            .firstOrNull()
            ?: throw IllegalStateException("Missing MCP $method response")
    }

    private suspend fun sendJsonRpcNotification(
        server: McpServerConfig,
        method: String,
        params: Map<String, Any?> = emptyMap()
    ) {
        val transport = server.transport as? McpTransport.Http
            ?: throw IllegalArgumentException("Unsupported MCP transport")
        val payload = jsonRpcRequest(method, params, expectResponse = false)
        val request = mcpRequest(transport.url, server, payload.body)
        withTimeoutOrNull(1_000) {
            engine.frames(request).firstOrNull()
        }
    }

    private fun mcpRequest(url: String, server: McpServerConfig, body: String): HttpRequest {
        return HttpRequest(
            method = "POST",
            url = url,
            headers = mcpHeaders(server),
            body = body.toByteArray(Charsets.UTF_8),
            timeoutMs = HttpTimeouts(
                connectMs = 30_000,
                readMs = 60_000,
                writeMs = 30_000
            ),
            isStreaming = false
        )
    }

    private fun mcpHeaders(server: McpServerConfig): Map<String, String> {
        val headers = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/event-stream"
        )
        server.headers.forEach { (key, value) ->
            val existingKey = headers.keys.firstOrNull { it.equals(key, ignoreCase = true) }
            if (existingKey != null) {
                headers.remove(existingKey)
            }
            headers[key] = value
        }
        return headers
    }

    private fun HttpFrame.payload(): String {
        return when (this) {
            is HttpFrame.Text -> value
            is HttpFrame.SseData -> value
        }
    }

    private fun Map<String, Any?>.matchesJsonRpcResponse(requestId: String?, allowIdless: Boolean): Boolean {
        val responseId = this["id"]?.toString()
        if (requestId != null && responseId == requestId) {
            return true
        }
        return allowIdless && responseId == null && ("result" in this || "error" in this)
    }

    private fun Any?.asStringMap(): Map<String, Any?>? {
        val raw = this as? Map<*, *> ?: return null
        return raw.entries.associate { (key, value) ->
            (key as? String ?: return null) to value
        }
    }
}
