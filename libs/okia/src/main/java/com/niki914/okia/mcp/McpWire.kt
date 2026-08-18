package com.niki914.okia.mcp

import com.niki914.okia.transport.HttpEngine
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpResponse
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.SseEventParser
import com.niki914.okia.transport.SseLineParser
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * MCP 线缆层共享错误：JSON-RPC error / 传输失败 / 协议畸形统一经它表达。
 * discoverTools 与 callTool 抛本类型（不吞、不静默）；上层（refresh /
 * McpExecutor）按「结果回传」契约处理。
 * Design source: codex rmcp error 分类；okia PRD §8.17 容错裁决（G4）。
 */
internal class McpProtocolException(
    message: String,
    /** JSON-RPC error 对象的 code（-32700 / -32601 / -32602 / -32603 等），非协议层错误为 null */
    val jsonRpcCode: Int? = null,
    /** HTTP 状态码（传输层错误为 null） */
    val statusCode: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/** 服务器协商出的协议模式（AutoDetect 探测结果，按服务器缓存）。 */
internal enum class McpMode {
    Discovery,
    Legacy
}

/**
 * JSON-RPC 2.0 over Streamable HTTP 的共享线缆过程：组请求、unary 发送、
 * 响应解析（application/json 直解 / text/event-stream 经 SseEventParser
 * 聚合、event=message 过滤）、JSON-RPC error / id 校验、tools 列表与
 * call 结果的结构化解析。
 * 实现一（legacy）与实现二（2026）共用本过程，只差握手形态与方法集合。
 * 无状态：不维护 Mcp-Session-Id；每次 discoverTools 独立握手，callTool
 * 无握手。传输失败由 HttpEngine.unary 的缺省结构（status/body 为 null，
 * D45）表达，此处转为 McpProtocolException。
 * Design source: MCP 2025-06-18 streamable HTTP；codex rmcp-client
 * （http_client_adapter / sse 响应处理 / event 过滤）。
 */
internal class McpWire(
    private val engine: HttpEngine,
    private val timeouts: HttpTimeouts = DEFAULT_MCP_TIMEOUTS
) {

    private val json = Json { ignoreUnknownKeys = true }

    // JSON-RPC 请求 id：全局自增（Mutex 串行取号，KMP 无 java.util.concurrent）
    private var idCounter = 0L
    private val idMutex = Mutex()
    suspend fun nextId(): Long = idMutex.withLock { ++idCounter }

    // 有状态服务器的会话：initialize 响应头 mcp-session-id 按 serverName 缓存，
    // 后续请求带上。无状态服务器不返回该头 → 不发（无状态模式，两者兼容）。
    // 协商版本：initialize 结果 protocolVersion 按 serverName 缓存，后续请求附
    // MCP-Protocol-Version 头（2025-06-18 transports §Protocol Version Header，MUST）。
    // 会话终止（服务器重启 / 过期，404 或 -32000 session 类错误）→ 二者同时丢弃，
    // 由客户端层以不带旧会话的 initialize 重建（§Session Management）。
    private val sessions = HashMap<String, String>()
    private val protocolVersions = HashMap<String, String>()
    private val sessionMutex = Mutex()

    /** 丢弃服务器的会话与协商版本缓存（会话终止后由客户端层调用；幂等）。 */
    suspend fun discardSession(serverName: String) {
        sessionMutex.withLock {
            sessions.remove(serverName)
            protocolVersions.remove(serverName)
        }
    }

    /**
     * 发送 JSON-RPC 请求并返回 result 对象。JSON-RPC error → 抛（带
     * jsonRpcCode）；传输失败 / 非 2xx / 畸形响应 / id 不匹配 → 抛。
     */
    suspend fun request(server: McpServer, method: String, params: JsonObject?, id: Long): JsonObject {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }
        val result = parseResult(send(server, body), id)
        // 协商版本缓存：initialize 结果携带 protocolVersion，后续请求必须附
        // MCP-Protocol-Version 头（规范 MUST；缺失时不缓存，退化到当前行为）。
        if (method == "initialize") {
            val negotiated = (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull
            if (!negotiated.isNullOrEmpty()) {
                sessionMutex.withLock { protocolVersions[server.name] = negotiated }
            }
        }
        return result
    }

    /** 发送 JSON-RPC 通知（无 id）；响应不解析，2xx 即成功。 */
    suspend fun notify(server: McpServer, method: String, params: JsonObject? = null) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
        }
        checkTransport(send(server, body))
    }

    /**
     * tools/list 分页循环：cursor 分页直到 nextCursor 为空。meta 为可选
     * 的协议级参数（2026 模式的 _meta），legacy 传 null。
     */
    suspend fun listTools(server: McpServer, meta: JsonObject? = null): List<McpDiscoveredTool> {
        val all = ArrayList<McpDiscoveredTool>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            if (++pages > MAX_TOOL_LIST_PAGES) {
                throw McpProtocolException(
                    "tools/list exceeded $MAX_TOOL_LIST_PAGES pages; " +
                        "server keeps returning nextCursor (server bug)"
                )
            }
            val params = buildJsonObject {
                if (meta != null) put("_meta", meta)
                if (cursor != null) put("cursor", cursor)
            }
            val result = request(server, "tools/list", params.ifEmpty { null }, nextId())
            all += parseTools(result)
            val next = result["nextCursor"]?.let { it as? JsonPrimitive }?.contentOrNull
            if (next.isNullOrEmpty()) break
            cursor = next
        }
        return all
    }

    /** tools/call 的参数 JSON 解析；非法输入 → 明确失败（不静默修正）。 */
    fun parseArguments(toolName: String, raw: String): JsonElement = try {
        json.parseToJsonElement(raw)
    } catch (e: Exception) {
        throw McpProtocolException(
            "invalid JSON in tools/call arguments for '$toolName': ${e.message}", cause = e
        )
    }

    // ── 结构化解析 ──────────────────────────────────────────────────────

    /** result 对象 → 工具列表。name 缺失 = 服务器 bug（抛）；description / schema 可空。 */
    fun parseTools(result: JsonObject): List<McpDiscoveredTool> {
        val tools = result["tools"] as? JsonArray
            ?: throw McpProtocolException("tools/list result missing 'tools' array: $result")
        return tools.map { raw ->
            val tool = raw as? JsonObject
                ?: throw McpProtocolException("tools/list entry is not an object: $raw")
            val name = tool["name"]?.let { it as? JsonPrimitive }?.contentOrNull
                ?: throw McpProtocolException("discovered tool missing required 'name': $tool")
            McpDiscoveredTool(
                name = name,
                description = tool["description"]?.let { it as? JsonPrimitive }?.contentOrNull,
                inputSchemaJson = tool["inputSchema"]?.toString()
            )
        }
    }

    /** result 对象 → 结构化调用结果。非文本 content block → 抛（§8.8 #4 收窄）。 */
    fun parseCallResult(result: JsonObject): McpCallResult {
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        val rawContent = result["content"] as? JsonArray ?: JsonArray(emptyList())
        val blocks = rawContent.map { raw ->
            val block = raw as? JsonObject
                ?: throw McpProtocolException("content block is not an object: $raw")
            val type = block["type"]?.let { it as? JsonPrimitive }?.contentOrNull
            if (type != "text") {
                throw McpProtocolException(
                    "MCP tool result contains non-text block type '${type ?: "unknown"}'; " +
                        "only text blocks are supported"
                )
            }
            val text = block["text"]?.let { it as? JsonPrimitive }?.contentOrNull
                ?: throw McpProtocolException("MCP text content block missing 'text': $block")
            McpContentBlock.Text(text)
        }
        return McpCallResult(isError = isError, content = blocks)
    }

    // ── 发送与信封解析 ──────────────────────────────────────────────────

    private suspend fun send(server: McpServer, body: JsonObject): HttpResponse {
        val url = when (val transport = server.transport) {
            is McpTransport.Http -> transport.url
        }
        // 会话头 + 协商版本头（同一锁内读取保持一致）；默认头可被服务器级
        // headers 覆盖（host 注入认证等）。initialize 本身无缓存版本，不带头——
        // 规范只要求「初始化之后的请求」携带（版本协商结果在此之前未知）。
        val (session, version) = sessionMutex.withLock {
            sessions[server.name] to protocolVersions[server.name]
        }
        val sessionHeader = session?.let { mapOf("mcp-session-id" to it) } ?: emptyMap()
        val versionHeader = version?.let { mapOf("MCP-Protocol-Version" to it) } ?: emptyMap()
        val response = engine.unary(
            HttpRequest(
                url = url,
                method = "POST",
                headers = DEFAULT_HEADERS + sessionHeader + versionHeader + server.headers,
                body = body.toString(),
                timeouts = timeouts
            )
        )
        // 会话终止（服务器重启 / 会话过期）：规范要求 404，现实实现常见
        // 400 + JSON-RPC -32000 session 类错误。按规范丢弃缓存并让上层以全新
        // initialize（不带旧会话）重建。仅当缓存仍是本次发送的值时丢弃，
        // 避免并发中已经建立的更新会话被误清。
        if (isSessionTerminatedResponse(response)) {
            sessionMutex.withLock {
                if (sessions[server.name] == session) {
                    sessions.remove(server.name)
                    if (protocolVersions[server.name] == version) protocolVersions.remove(server.name)
                }
            }
            return response
        }
        // 捕获新下发的会话（initialize 握手时服务器常见返回）；头名大小写不敏感
        val issued = response.headers.entries.firstOrNull { (name, _) ->
            name.equals("mcp-session-id", ignoreCase = true)
        }?.value
        if (issued != null) {
            sessionMutex.withLock { sessions[server.name] = issued }
        }
        return response
    }

    private suspend fun parseResult(response: HttpResponse, expectedId: Long): JsonObject {
        checkTransport(response)
        return parseEnvelope(response, expectedId).let { envelope ->
            if (envelope["jsonrpc"]?.let { (it as? JsonPrimitive)?.contentOrNull } != "2.0") {
                throw McpProtocolException("MCP response missing jsonrpc=2.0: ${envelope}")
            }
            val id = envelope["id"]?.let { (it as? JsonPrimitive)?.content }
            if (id != expectedId.toString()) {
                throw McpProtocolException("MCP response id mismatch: expected $expectedId, got $id")
            }
            (envelope["error"] as? JsonObject)?.let { error ->
                val code = error["code"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
                val message = error["message"]?.let { (it as? JsonPrimitive)?.contentOrNull } ?: "unknown error"
                throw McpProtocolException("MCP JSON-RPC error: $message", jsonRpcCode = code)
            }
            envelope["result"] as? JsonObject
                ?: throw McpProtocolException("MCP response missing 'result': $envelope")
        }
    }

    private suspend fun parseEnvelope(response: HttpResponse, expectedId: Long): JsonObject {
        val body = response.body ?: throw McpProtocolException(
            "MCP response had empty body (HTTP ${response.statusCode})", statusCode = response.statusCode
        )
        val text = body.decodeToString()  // UTF-8（KMP stdlib）
        val isSse = response.headers.any { (name, value) ->
            name.equals("Content-Type", ignoreCase = true) && value.contains("text/event-stream")
        }
        return if (isSse) parseSseEnvelope(text, expectedId) else parseJsonEnvelope(text)
    }

    private fun parseJsonEnvelope(text: String): JsonObject = try {
        json.parseToJsonElement(text) as? JsonObject
            ?: throw McpProtocolException("MCP response is not a JSON object: ${text.take(200)}")
    } catch (e: McpProtocolException) {
        throw e
    } catch (e: Exception) {
        throw McpProtocolException("MCP response is not valid JSON: ${text.take(200)}", cause = e)
    }

    /**
     * SSE 信封：聚合 W3C 事件，只取 event=message（缺省 event 亦算，D18），逐事件解析。
     * MCP Streamable HTTP 明确允许服务器在目标响应前发送 JSON-RPC request 或
     * notification（2025-06-18 transports §Response）：不能把第一个 message 事件
     * 当响应，必须按 JSON-RPC 的 id 精确匹配目标响应并跳过其余 server 消息。
     * 找不到匹配 → 明确失败（区别于误导性的「id mismatch」：其余消息是 server
     * 主动消息，本层暂无处理能力，见 §8.17 收窄）。
     */
    private suspend fun parseSseEnvelope(text: String, expectedId: Long): JsonObject {
        val events = SseEventParser().parse(SseLineParser().parse(flowOf(text))).toList()
        val envelopes = events
            .filter { it.event == null || it.event == "message" }
            .map { ev ->
                try {
                    json.parseToJsonElement(ev.data) as? JsonObject
                        ?: throw McpProtocolException("MCP SSE event data is not a JSON object: ${ev.data.take(200)}")
                } catch (e: McpProtocolException) {
                    throw e
                } catch (e: Exception) {
                    throw McpProtocolException("MCP SSE event data is not valid JSON: ${ev.data.take(200)}", cause = e)
                }
            }
        val idOf = { envelope: JsonObject ->
            (envelope["id"] as? JsonPrimitive)?.content
        }
        val matched = envelopes.firstOrNull { idOf(it) == expectedId.toString() }
        if (matched != null) return matched
        val reason = if (envelopes.isEmpty()) {
            "MCP SSE response contained no message events"
        } else {
            "MCP SSE stream contained ${envelopes.size} message(s) but no JSON-RPC response " +
                "with id $expectedId (server request/notification messages are not supported); " +
                "first: ${envelopes.first().toString().take(200)}"
        }
        throw McpProtocolException(reason)
    }

    // 会话终止响应判定（send 层）：404（规范）或 400 + JSON-RPC -32000 session 类
    // 错误（server-everything 等实现的现实形态："No valid session ID provided" /
    // "Server not initialized"）。与 companion.isSessionTerminated 同一语义。
    private fun isSessionTerminatedResponse(response: HttpResponse): Boolean {
        if (response.statusCode == 404) return true
        val status = response.statusCode ?: return false
        if (status in 200..299) return false
        val detail = response.body?.decodeToString() ?: return false
        return try {
            val error = (json.parseToJsonElement(detail) as? JsonObject)?.get("error") as? JsonObject
                ?: return false
            val code = (error["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return false
            code == SESSION_INVALID_CODE &&
                (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty().let { message ->
                    message.contains("session", ignoreCase = true) ||
                        message.contains("not initialized", ignoreCase = true)
                }
        } catch (e: Exception) {
            false
        }
    }

    /** 传输失败（status/body 缺省结构，D45）与非 2xx 统一检查。非 2xx 响应若
     * 携带 JSON-RPC error envelope，解析其 code 填入 jsonRpcCode（服务器用
     * HTTP 400 表达协议错误的现实形态，probe 的 -32601/-32000 回退判定依赖它）。 */
    private fun checkTransport(response: HttpResponse) {
        val status = response.statusCode ?: throw McpProtocolException(
            "MCP transport failure (network error or timeout)"
        )
        if (status !in 200..299) {
            val detail = response.body?.decodeToString()?.take(300) ?: ""
            val rpcCode = try {
                (json.parseToJsonElement(detail).jsonObject["error"] as? JsonObject)
                    ?.get("code")?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            } catch (e: Exception) {
                null
            }
            throw McpProtocolException(
                "MCP request failed with HTTP $status${if (detail.isNotEmpty()) ": $detail" else ""}",
                jsonRpcCode = rpcCode,
                statusCode = status
            )
        }
    }

    companion object {

        // JSON-RPC 标准错误码：Method Not Found（AutoDetect 探测回退依据）
        const val JSONRPC_METHOD_NOT_FOUND = -32601

        /** 会话失效错误码（-32000）：服务器重启 / 会话过期后的常见表达。 */
        const val SESSION_INVALID_CODE = -32000

        /**
         * 会话终止判定（客户端层共享，重试触发用）：404（2025-06-18
         * §Session Management，收到 404 必须不带旧会话重新 initialize）或
         * -32000 session 类错误（"No valid session ID provided" 等实现现实）。
         * 不含 "Server not initialized"：那是「未初始化」而非「已终止」，重试
         * server/discover 无意义，probe 已有 legacy 回退路径处理它。
         */
        fun isSessionTerminated(e: McpProtocolException): Boolean =
            e.statusCode == 404 || (e.jsonRpcCode == SESSION_INVALID_CODE && e.message.orEmpty().let { message ->
                message.contains("session", ignoreCase = true)
            })

        /** 防服务器死循环返回 nextCursor 的页数上限（明确失败优于无限循环）。 */
        const val MAX_TOOL_LIST_PAGES = 50

        /** 默认超时与 M0 默认配置一致（30s 连接 / 60s 读 / 30s 写）。 */
        val DEFAULT_MCP_TIMEOUTS = HttpTimeouts(connectMs = 30_000, readMs = 60_000, writeMs = 30_000)

        val DEFAULT_HEADERS = mapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "application/json, text/event-stream"
        )

        /** 客户端标识（initialize / server/discover 的参数，MCP 规范必填）。 */
        fun clientInfo(): JsonElement = buildJsonObject {
            put("name", "okia")
            put("version", "0.1.0")
        }
    }
}