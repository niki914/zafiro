package com.niki914.okia.mcp

import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.OkHttpEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Legacy 2025-06-18 线缆客户端测试：MockWebServer 扮演 MCP 服务器（真实
 * HTTP 栈，OkHttpEngine），覆盖握手、通知、SSE/JSON 双响应形态、会话头
 * 往返、分页、错误路径、传输失败、取消。服务器回显请求 id（真实服务器
 * 行为，codex mcp_2026_* 测试同构）。
 */
class LegacyStreamableHttpMcpClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: LegacyStreamableHttpMcpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = LegacyStreamableHttpMcpClient(OkHttpEngine(), HttpTimeouts(3000, 3000, 3000))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun mcpServer(): McpServer = McpServer(
        name = "srv",
        transport = McpTransport.Http(server.url("/mcp").toString()),
        headers = emptyMap(),
        enabled = true
    )

    // ── 服务器构造 helpers（按方法分发；id 一律回显请求 id）──────────────

    private fun sseResponse(body: String) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body)

    private fun jsonResponse(body: String) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)

    private fun rpcResult(body: JsonObject, result: String): MockResponse {
        val id = body["id"]?.jsonPrimitive?.content ?: "null"
        return sseResponse("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}\n\n")
    }

    private fun rpcError(body: JsonObject, code: Int, message: String): MockResponse {
        val id = body["id"]?.jsonPrimitive?.content ?: "null"
        return sseResponse(
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id," +
                "\"error\":{\"code\":$code,\"message\":\"$message\"}}\n\n"
        )
    }

    private fun jsonRpcResult(body: JsonObject, result: String): MockResponse {
        val id = body["id"]?.jsonPrimitive?.content ?: "null"
        return jsonResponse("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
    }

    private fun serverWith(
        init: (JsonObject) -> MockResponse = { b -> rpcResult(b, DEFAULT_INIT_RESULT) },
        notify: (JsonObject) -> MockResponse = { MockResponse().setResponseCode(202) },
        list: (JsonObject) -> MockResponse = { b -> rpcResult(b, """{"tools":[$DEFAULT_TOOL]}""") },
        call: (JsonObject) -> MockResponse = { b -> rpcResult(b, """{"content":[{"type":"text","text":"ok"}]}""") }
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            // peek：dispatch 与测试后续断言共读同一 body（Buffer 只能消费一次）
            val body = Json.parseToJsonElement(request.body.peek().readUtf8()).jsonObject
            return when (body["method"]?.jsonPrimitive?.content) {
                "initialize" -> init(body)
                "notifications/initialized" -> notify(body)
                "tools/list" -> list(body)
                "tools/call" -> call(body)
                else -> MockResponse().setResponseCode(400).setBody("unknown method")
            }
        }
    }

    private fun awaitRequests(count: Int): List<RecordedRequest> {
        val out = ArrayList<RecordedRequest>(count)
        repeat(count) { out += server.takeRequest() }
        return out
    }

    private fun parseJson(req: RecordedRequest): JsonObject =
        Json.parseToJsonElement(req.body.peek().readUtf8()).jsonObject

    companion object {
        const val DEFAULT_INIT_RESULT =
            """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"t","version":"1"}}"""
        const val DEFAULT_TOOL =
            """{"name":"echo","description":"Echo","inputSchema":{"type":"object","properties":{"m":{"type":"string"}}}}"""
    }

    // ── initialize 握手 ─────────────────────────────────────────────────

    @Test
    fun `initialize sends protocolVersion capabilities and clientInfo`() = runBlocking {
        server.dispatcher = serverWith()
        client.discoverTools(mcpServer())
        val init = parseJson(awaitRequests(3)[0])
        assertEquals("2.0", init["jsonrpc"]?.jsonPrimitive?.content)
        assertNotNull(init["id"])
        assertEquals("initialize", init["method"]?.jsonPrimitive?.content)
        val params = init["params"]!!.jsonObject
        assertEquals("2025-06-18", params["protocolVersion"]?.jsonPrimitive?.content)
        assertNotNull(params["capabilities"])
        assertEquals("okia", params["clientInfo"]!!.jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `initialize request carries content-type and accept headers`() = runBlocking {
        server.dispatcher = serverWith()
        client.discoverTools(mcpServer())
        val req = awaitRequests(3)[0]
        assertEquals("application/json; charset=utf-8", req.getHeader("Content-Type"))
        assertEquals("application/json, text/event-stream", req.getHeader("Accept"))
        assertEquals("POST", req.method)
    }

    @Test
    fun `discover works with plain json response`() = runBlocking {
        server.dispatcher = serverWith(
            init = { b -> jsonRpcResult(b, DEFAULT_INIT_RESULT) },
            list = { b -> jsonRpcResult(b, """{"tools":[$DEFAULT_TOOL]}""") }
        )
        val tools = client.discoverTools(mcpServer())
        assertEquals(listOf("echo"), tools.map { it.name })
    }

    @Test
    fun `discover works with sse response with message event`() = runBlocking {
        server.dispatcher = serverWith()  // 默认全部走 SSE
        val tools = client.discoverTools(mcpServer())
        assertEquals(listOf("echo"), tools.map { it.name })
    }

    @Test
    fun `sse event without event field is accepted as message`() = runBlocking {
        val noEvent: (JsonObject) -> MockResponse = { body ->
            val id = body["id"]?.jsonPrimitive?.content ?: "null"
            sseResponse("data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$DEFAULT_INIT_RESULT}\n\n")
        }
        server.dispatcher = serverWith(init = noEvent)
        val tools = client.discoverTools(mcpServer())
        assertEquals(listOf("echo"), tools.map { it.name })
    }

    @Test
    fun `non-message sse events are filtered out`() = runBlocking {
        val mixed: (JsonObject) -> MockResponse = { body ->
            val id = body["id"]?.jsonPrimitive?.content ?: "null"
            sseResponse("event: ping\ndata: {\"ignored\":true}\n\n" +
                "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$DEFAULT_INIT_RESULT}\n\n")
        }
        server.dispatcher = serverWith(init = mixed)
        val tools = client.discoverTools(mcpServer())
        assertEquals(1, tools.size)
    }

    @Test
    fun `initialize json rpc error surfaces code and message`() = runBlocking {
        server.dispatcher = serverWith(init = { b -> rpcError(b, -32602, "bad params") })
        val e = try { client.discoverTools(mcpServer()); null } catch (x: McpProtocolException) { x }
        assertNotNull(e)
        assertEquals(-32602, e!!.jsonRpcCode)
    }

    @Test
    fun `response id mismatch is a protocol error`() = runBlocking {
        server.dispatcher = serverWith(
            init = { sseResponse("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":999,\"result\":\"x\"}\n\n") }
        )
        val e = try { client.discoverTools(mcpServer()); null } catch (x: McpProtocolException) { x }
        assertNotNull(e)
    }

    @Test
    fun `http non-2xx surfaces status code`() = runBlocking {
        server.dispatcher = serverWith(init = { MockResponse().setResponseCode(503).setBody("down") })
        val e = try { client.discoverTools(mcpServer()); null } catch (x: McpProtocolException) { x }
        assertNotNull(e)
        assertEquals(503, e!!.statusCode)
    }

    @Test
    fun `http 2xx with empty body is a protocol error`() = runBlocking {
        server.dispatcher = serverWith(init = { MockResponse().setResponseCode(200) })
        val e = try { client.discoverTools(mcpServer()); null } catch (x: McpProtocolException) { x }
        assertNotNull(e)
    }

    @Test
    fun `non-json body is a protocol error`() = runBlocking {
        server.dispatcher = serverWith(init = { MockResponse().setResponseCode(200).setBody("<html>") })
        val e = try { client.discoverTools(mcpServer()); null } catch (x: McpProtocolException) { x }
        assertNotNull(e)
    }

    @Test
    fun `connection refused surfaces transport failure`() = runBlocking {
        server.shutdown()  // 关闭后再访问 → 连接拒绝
        try {
            client.discoverTools(mcpServer())
            fail("expected transport failure")
        } catch (e: McpProtocolException) {
            assertNull(e.statusCode)
            assertNull(e.jsonRpcCode)
        }
    }

    @Test
    fun `read timeout surfaces transport failure`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
        }
        val slowClient = LegacyStreamableHttpMcpClient(OkHttpEngine(), HttpTimeouts(1000, 500, 1000))
        try {
            slowClient.discoverTools(mcpServer())
            fail("expected timeout")
        } catch (e: McpProtocolException) {
            assertNull(e.statusCode)
        }
    }

    // ── initialized 通知与请求序列 ───────────────────────────────────────

    @Test
    fun `discover issues initialize then initialized then tools list`() = runBlocking {
        server.dispatcher = serverWith()
        client.discoverTools(mcpServer())
        val methods = awaitRequests(3).map { parseJson(it)["method"]?.jsonPrimitive?.content }
        assertEquals(listOf("initialize", "notifications/initialized", "tools/list"), methods)
    }

    @Test
    fun `initialized notification has no id`() = runBlocking {
        server.dispatcher = serverWith()
        client.discoverTools(mcpServer())
        val notified = parseJson(awaitRequests(3)[1])
        assertEquals("notifications/initialized", notified["method"]?.jsonPrimitive?.content)
        assertNull(notified["id"])
    }

    @Test
    fun `initialized notification failing with non-2xx fails discovery`() = runBlocking {
        server.dispatcher = serverWith(notify = { MockResponse().setResponseCode(500) })
        val e = try { client.discoverTools(mcpServer()); null } catch (x: McpProtocolException) { x }
        assertNotNull(e)
        assertEquals(500, e!!.statusCode)
    }

    // ── mcp-session-id 会话头 ────────────────────────────────────────────

    @Test
    fun `session id from initialize response is attached to subsequent requests`() = runBlocking {
        server.dispatcher = serverWith(
            init = { b -> rpcResult(b, DEFAULT_INIT_RESULT).setHeader("mcp-session-id", "sess-123") }
        )
        client.discoverTools(mcpServer())
        assertEquals("sess-123", awaitRequests(3)[2].getHeader("mcp-session-id"))
    }

    @Test
    fun `no session header from server means no session header sent`() = runBlocking {
        server.dispatcher = serverWith()
        client.discoverTools(mcpServer())
        assertNull(awaitRequests(3)[2].getHeader("mcp-session-id"))
    }

    @Test
    fun `second discover re-initializes and refreshes session`() = runBlocking {
        server.dispatcher = serverWith(
            init = { b -> rpcResult(b, DEFAULT_INIT_RESULT).setHeader("mcp-session-id", "s1") }
        )
        client.discoverTools(mcpServer())
        awaitRequests(3)
        server.dispatcher = serverWith(
            init = { b -> rpcResult(b, DEFAULT_INIT_RESULT).setHeader("mcp-session-id", "s2") }
        )
        client.discoverTools(mcpServer())
        assertEquals("s2", awaitRequests(3)[2].getHeader("mcp-session-id"))
    }

    @Test
    fun `session id header name matching is case insensitive`() = runBlocking {
        server.dispatcher = serverWith(
            init = { b -> rpcResult(b, DEFAULT_INIT_RESULT).setHeader("MCP-Session-Id", "sess-upper") }
        )
        client.discoverTools(mcpServer())
        assertEquals("sess-upper", awaitRequests(3)[2].getHeader("mcp-session-id"))
    }

    // ── SSE 响应选择（MCP Streamable HTTP：目标响应前可插入 server 消息） ──

    @Test
    fun `sse response with server notification before reply is matched by id`() = runBlocking {
        // 服务器在目标响应前发 JSON-RPC notification（2025-06-18 transports
        // 明确允许）：不能把第一个 message 事件当响应，必须按 JSON-RPC id 匹配。
        server.dispatcher = serverWith(
            init = { b ->
                val id = b["id"]?.jsonPrimitive?.content ?: "null"
                sseResponse(
                    "event: message\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"," +
                        "\"params\":{\"level\":\"warning\",\"data\":\"hi\"}}\n\n" +
                        "event: message\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$DEFAULT_INIT_RESULT}\n\n"
                )
            }
        )
        assertEquals("echo", client.discoverTools(mcpServer()).single().name)
    }

    @Test
    fun `sse stream with only server messages fails with clear protocol error`() = runBlocking {
        // 只有通知 / server 请求、没有目标响应：明确报错（而非误导性的 id mismatch）
        server.dispatcher = serverWith(
            init = {
                sseResponse(
                    "event: message\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\"," +
                        "\"params\":{\"level\":\"warning\",\"data\":\"hi\"}}\n\n" +
                        "event: message\n" +
                        "data: {\"jsonrpc\":\"2.0\",\"id\":100,\"method\":\"ping\"}\n\n"
                )
            }
        )
        val e = try { client.discoverTools(mcpServer()); null } catch (x: McpProtocolException) { x }
        assertNotNull(e)
        assertTrue(e!!.message!!.contains("no JSON-RPC response with id 1"))
    }

    // ── MCP-Protocol-Version 协商版本头（2025-06-18 §Protocol Version Header）──

    @Test
    fun `requests after initialize carry negotiated protocol version header`() = runBlocking {
        server.dispatcher = serverWith()
        client.discoverTools(mcpServer())
        val requests = awaitRequests(3)
        // initialized 通知与 tools/list 都附协商版本（initialize 本身无缓存版本，不带）
        assertEquals("2025-06-18", requests[1].getHeader("MCP-Protocol-Version"))
        assertEquals("2025-06-18", requests[2].getHeader("MCP-Protocol-Version"))
        assertNull(requests[0].getHeader("MCP-Protocol-Version"))
    }

    @Test
    fun `negotiated protocol version from server result is attached`() = runBlocking {
        // 服务器协商返回的版本（而非客户端硬编码）进后续请求头
        server.dispatcher = serverWith(
            init = { b ->
                rpcResult(
                    b,
                    """{"protocolVersion":"2030-01-01","capabilities":{},"serverInfo":{"name":"t","version":"1"}}"""
                )
            }
        )
        client.discoverTools(mcpServer())
        assertEquals("2030-01-01", awaitRequests(3)[2].getHeader("MCP-Protocol-Version"))
    }

    // ── 会话终止自愈（2025-06-18 §Session Management） ────────────────────

    @Test
    fun `discover re-initializes without stale session after 404 session termination`() = runBlocking {
        // 规范路径：服务器 404 终止会话 → 客户端丢弃旧会话、以不带旧会话的
        // initialize 重建（MUST），无需重建整个 Okia 实例。
        var i = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                i++
                val body = Json.parseToJsonElement(request.body.peek().readUtf8()).jsonObject
                return when (body["method"]?.jsonPrimitive?.content) {
                    "initialize" ->
                        if (i == 1) rpcResult(body, DEFAULT_INIT_RESULT).setHeader("mcp-session-id", "s1")
                        else rpcResult(body, DEFAULT_INIT_RESULT).setHeader("mcp-session-id", "s2")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" ->
                        if (i == 3) MockResponse().setResponseCode(404).setBody("session terminated")
                        else if (i == 6) rpcResult(body, """{"tools":[$DEFAULT_TOOL]}""")
                        else MockResponse().setResponseCode(404).setBody("unexpected")
                    else -> MockResponse().setResponseCode(400).setBody("unknown method")
                }
            }
        }
        val tools = client.discoverTools(mcpServer())
        assertEquals("echo", tools.single().name)
        val requests = awaitRequests(6)
        assertEquals(
            listOf("initialize", "notifications/initialized", "tools/list", "initialize", "notifications/initialized", "tools/list"),
            requests.map { parseJson(it)["method"]?.jsonPrimitive?.content }
        )
        // 重建的 initialize 不带旧会话（规范 MUST）；tools/list 用新会话
        assertNull(requests[3].getHeader("mcp-session-id"))
        assertEquals("s2", requests[5].getHeader("mcp-session-id"))
    }

    @Test
    fun `discover recovers from stale session -32000 error like real servers`() = runBlocking {
        // 实现现实（server-everything 实测）：会话失效返回 HTTP 400 + JSON-RPC
        // -32000 "Bad Request: No valid session ID provided" → 同样自愈。
        var i = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                i++
                val body = Json.parseToJsonElement(request.body.peek().readUtf8()).jsonObject
                return when (body["method"]?.jsonPrimitive?.content) {
                    "initialize" ->
                        if (i == 1) rpcResult(body, DEFAULT_INIT_RESULT).setHeader("mcp-session-id", "s1")
                        else rpcResult(body, DEFAULT_INIT_RESULT).setHeader("mcp-session-id", "s2")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" ->
                        if (i == 3) {
                            MockResponse().setResponseCode(400).setBody(
                                """{"jsonrpc":"2.0","error":{"code":-32000,"message":"Bad Request: No valid session ID provided"}}"""
                            )
                        } else if (i == 6) rpcResult(body, """{"tools":[$DEFAULT_TOOL]}""")
                        else MockResponse().setResponseCode(400).setBody("unexpected")
                    else -> MockResponse().setResponseCode(400).setBody("unknown method")
                }
            }
        }
        val tools = client.discoverTools(mcpServer())
        assertEquals("echo", tools.single().name)
        val requests = awaitRequests(6)
        assertNull(requests[3].getHeader("mcp-session-id"))
        assertEquals("s2", requests[5].getHeader("mcp-session-id"))
    }

    @Test
    fun `callTool re-establishes session and retries after session termination`() = runBlocking {
        // 回合中途服务器重启：callTool 第一个请求 -32000 → 丢弃旧会话、重新握手
        // （initialize + initialized）、重试成功——host 无需重建实例。
        server.dispatcher = serverWith(init = { b -> rpcResult(b, DEFAULT_INIT_RESULT).setHeader("mcp-session-id", "s1") })
        client.discoverTools(mcpServer())
        awaitRequests(3)

        var i = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                i++
                val body = Json.parseToJsonElement(request.body.peek().readUtf8()).jsonObject
                return when (body["method"]?.jsonPrimitive?.content) {
                    "initialize" -> rpcResult(body, DEFAULT_INIT_RESULT).setHeader("mcp-session-id", "s2")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/call" ->
                        if (i == 1) {
                            MockResponse().setResponseCode(400).setBody(
                                """{"jsonrpc":"2.0","error":{"code":-32000,"message":"Bad Request: No valid session ID provided"}}"""
                            )
                        } else rpcResult(body, """{"content":[{"type":"text","text":"ok"}]}""")
                    else -> MockResponse().setResponseCode(400).setBody("unknown method")
                }
            }
        }
        val result = client.callTool(mcpServer(), "echo", """{"m":"hi"}""")
        assertEquals("ok", (result.content.single() as McpContentBlock.Text).text)
        // 重试成功且新会话已生效
        assertEquals("s2", awaitRequests(4).last().getHeader("mcp-session-id"))
    }

    // ── tools/list 与分页 ───────────────────────────────────────────────

    @Test
    fun `empty tool list`() = runBlocking {
        server.dispatcher = serverWith(list = { b -> rpcResult(b, """{"tools":[]}""") })
        assertTrue(client.discoverTools(mcpServer()).isEmpty())
    }

    @Test
    fun `tool description and schema can be absent`() = runBlocking {
        server.dispatcher = serverWith(list = { b -> rpcResult(b, """{"tools":[{"name":"bare"}]}""") })
        val tools = client.discoverTools(mcpServer())
        assertEquals(listOf("bare"), tools.map { it.name })
        assertNull(tools[0].description)
        assertNull(tools[0].inputSchemaJson)
    }

    @Test
    fun `tool missing name is a protocol error`() = runBlocking {
        server.dispatcher = serverWith(list = { b -> rpcResult(b, """{"tools":[{"description":"x"}]}""") })
        val e = try { client.discoverTools(mcpServer()); null } catch (x: McpProtocolException) { x }
        assertNotNull(e)
    }

    @Test
    fun `schema json is passed through verbatim`() = runBlocking {
        server.dispatcher = serverWith()
        val tools = client.discoverTools(mcpServer())
        val schema = Json.parseToJsonElement(tools[0].inputSchemaJson!!).jsonObject
        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `pagination merges pages and forwards cursor`() = runBlocking {
        server.dispatcher = serverWith(
            list = { b ->
                val cursor = b["params"]?.jsonObject?.get("cursor")?.jsonPrimitive?.content
                if (cursor == null) {
                    rpcResult(b, """{"tools":[{"name":"a"}],"nextCursor":"page2"}""")
                } else {
                    assertEquals("page2", cursor)
                    rpcResult(b, """{"tools":[{"name":"b"}]}""")
                }
            }
        )
        val tools = client.discoverTools(mcpServer())
        assertEquals(listOf("a", "b"), tools.map { it.name })
    }

    @Test
    fun `tools list json rpc error fails discovery`() = runBlocking {
        server.dispatcher = serverWith(list = { b -> rpcError(b, -32603, "internal error") })
        val e = try { client.discoverTools(mcpServer()); null } catch (x: McpProtocolException) { x }
        assertNotNull(e)
        assertEquals(-32603, e!!.jsonRpcCode)
    }

    @Test
    fun `pagination loop is bounded`() = runBlocking {
        // 服务器永远给 nextCursor → 第 51 页时客户端拒绝（明确失败）
        server.dispatcher = serverWith(list = { b -> rpcResult(b, """{"tools":[],"nextCursor":"again"}""") })
        val e = try { client.discoverTools(mcpServer()); null } catch (x: McpProtocolException) { x }
        assertNotNull(e)
    }

    // ── tools/call ──────────────────────────────────────────────────────

    @Test
    fun `call tool passes arguments verbatim and returns text result`() = runBlocking {
        server.dispatcher = serverWith(
            call = { b -> rpcResult(b, """{"content":[{"type":"text","text":"hello back"}]}""") }
        )
        val result = client.callTool(mcpServer(), "echo", """{"message":"hi"}""")
        assertFalse(result.isError)
        assertEquals(listOf("hello back"), result.content.map { (it as McpContentBlock.Text).text })
        val callBody = parseJson(awaitRequests(1)[0])
        assertEquals("tools/call", callBody["method"]?.jsonPrimitive?.content)
        val params = callBody["params"]!!.jsonObject
        assertEquals("echo", params["name"]?.jsonPrimitive?.content)
        assertEquals("hi", params["arguments"]!!.jsonObject["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `call result with isError true is preserved`() = runBlocking {
        server.dispatcher = serverWith(
            call = { b -> rpcResult(b, """{"content":[{"type":"text","text":"nope"}],"isError":true}""") }
        )
        val result = client.callTool(mcpServer(), "t", "{}")
        assertTrue(result.isError)
        assertEquals(listOf("nope"), result.content.map { (it as McpContentBlock.Text).text })
    }

    @Test
    fun `multiple text blocks are all preserved`() = runBlocking {
        server.dispatcher = serverWith(
            call = { b ->
                rpcResult(b, """{"content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}""")
            }
        )
        val result = client.callTool(mcpServer(), "t", "{}")
        assertEquals(listOf("a", "b"), result.content.map { (it as McpContentBlock.Text).text })
    }

    @Test
    fun `non-text content block is a protocol error`() = runBlocking {
        server.dispatcher = serverWith(
            call = { b ->
                rpcResult(b, """{"content":[{"type":"image","data":"AAAA","mimeType":"image/png"}]}""")
            }
        )
        val e = try { client.callTool(mcpServer(), "t", "{}"); null } catch (x: McpProtocolException) { x }
        assertNotNull(e)
    }

    @Test
    fun `invalid arguments json fails fast before any request`() = runBlocking {
        try {
            client.callTool(mcpServer(), "t", "not json")
            fail("expected invalid arguments failure")
        } catch (e: McpProtocolException) {
            // 参数解析失败 → 请求未发出
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `call json rpc error surfaces code`() = runBlocking {
        server.dispatcher = serverWith(call = { b -> rpcError(b, -32602, "invalid params") })
        val e = try { client.callTool(mcpServer(), "t", "{}"); null } catch (x: McpProtocolException) { x }
        assertNotNull(e)
        assertEquals(-32602, e!!.jsonRpcCode)
    }

    @Test
    fun `call read timeout surfaces transport failure`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
        }
        val slowClient = LegacyStreamableHttpMcpClient(OkHttpEngine(), HttpTimeouts(1000, 500, 1000))
        try {
            slowClient.callTool(mcpServer(), "t", "{}")
            fail("expected timeout")
        } catch (e: McpProtocolException) {
            assertNull(e.statusCode)
        }
    }

    @Test
    fun `call cancellation interrupts pending request`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setBodyDelay(3, TimeUnit.SECONDS)
                    .setBody("""{"jsonrpc":"2.0","id":4,"result":{"content":[]}}""")
        }
        try {
            withTimeout(1000) { client.callTool(mcpServer(), "t", "{}") }
            fail("expected timeout to cancel")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // withTimeout 取消 call 协程：unary 经 invokeOnCancellation 打断请求
        }
    }
}