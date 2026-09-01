package com.niki914.okia.mcp

import com.niki914.okia.transport.HttpTimeouts
import com.niki914.okia.transport.OkHttpEngine
import kotlinx.coroutines.runBlocking
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
import org.junit.Before
import org.junit.Test

/**
 * 2026-07-28 客户端测试：server/discover 握手形态（_meta 自包含元数据）、
 * 版本协商、tools/list 与 tools/call 的 _meta 携带、探测回退信号。
 */
class DiscoveryStreamableHttpMcpClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DiscoveryStreamableHttpMcpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DiscoveryStreamableHttpMcpClient(OkHttpEngine(), HttpTimeouts(3000, 3000, 3000))
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

    private fun sse(body: String) =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
            .setBody(body)

    private fun rpcResult(b: JsonObject, result: String): MockResponse {
        val id = b["id"]?.jsonPrimitive?.content ?: "null"
        return sse("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}\n\n")
    }

    private fun rpcError(b: JsonObject, code: Int, message: String): MockResponse {
        val id = b["id"]?.jsonPrimitive?.content ?: "null"
        return sse(
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":$id," +
                    "\"error\":{\"code\":$code,\"message\":\"$message\"}}\n\n"
        )
    }

    private fun discoverServer(
        discoverResult: String = DEFAULT_DISCOVER_RESULT,
        list: (JsonObject) -> MockResponse = { b -> rpcResult(b, """{"tools":[]}""") },
        call: (JsonObject) -> MockResponse = { b -> rpcResult(b, """{"content":[]}""") }
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = Json.parseToJsonElement(request.body.peek().readUtf8()).jsonObject
            return when (body["method"]?.jsonPrimitive?.content) {
                "server/discover" -> rpcResult(body, discoverResult)
                "tools/list" -> list(body)
                "tools/call" -> call(body)
                else -> MockResponse().setResponseCode(400)
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
        const val DEFAULT_DISCOVER_RESULT =
            """{"resultType":"complete","supportedVersions":["2026-07-28"],""" +
                    """"capabilities":{"tools":{}},"ttlMs":0,"cacheScope":"private"}"""
    }

    // ── server/discover 形态 ─────────────────────────────────────────────

    @Test
    fun `discover request carries self-contained meta metadata`() = runBlocking {
        server.dispatcher = discoverServer()
        client.discoverTools(mcpServer())
        val discover = parseJson(awaitRequests(2)[0])
        assertEquals("server/discover", discover["method"]?.jsonPrimitive?.content)
        val meta = discover["params"]!!.jsonObject["_meta"]!!.jsonObject
        assertEquals(
            "2026-07-28",
            meta["io.modelcontextprotocol/protocolVersion"]?.jsonPrimitive?.content
        )
        assertEquals(
            "okia",
            meta["io.modelcontextprotocol/clientInfo"]!!.jsonObject["name"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `discover issues server discover then tools list`() = runBlocking {
        server.dispatcher = discoverServer()
        client.discoverTools(mcpServer())
        val methods = awaitRequests(2).map { parseJson(it)["method"]?.jsonPrimitive?.content }
        assertEquals(listOf("server/discover", "tools/list"), methods)
    }

    @Test
    fun `tools list request also carries meta`() = runBlocking {
        server.dispatcher = discoverServer()
        client.discoverTools(mcpServer())
        val listBody = parseJson(awaitRequests(2)[1])
        val meta = listBody["params"]!!.jsonObject["_meta"]!!.jsonObject
        assertEquals(
            "2026-07-28",
            meta["io.modelcontextprotocol/protocolVersion"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `discover result tools are parsed`() = runBlocking {
        server.dispatcher = discoverServer(list = { b ->
            rpcResult(b, """{"tools":[{"name":"a"},{"name":"b"}]}""")
        })
        val tools = client.discoverTools(mcpServer())
        assertEquals(listOf("a", "b"), tools.map { it.name })
    }

    // ── probe ────────────────────────────────────────────────────────────

    @Test
    fun `probe returns true when server advertises the version`() = runBlocking {
        server.dispatcher = discoverServer()
        assertTrue(client.probe(mcpServer()))
    }

    @Test
    fun `probe returns true when server advertises multiple versions including ours`() =
        runBlocking {
            server.dispatcher = discoverServer(
                """{"supportedVersions":["2026-07-28","2026-01-01"],"capabilities":{}}"""
            )
            assertTrue(client.probe(mcpServer()))
        }

    @Test
    fun `probe returns false on method-not-found`() = runBlocking {
        server.dispatcher = discoverServer(
            discoverResult = """{"noreturn":1}"""
        )
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.peek().readUtf8()).jsonObject
                return rpcError(body, -32601, "Method not found")
            }
        }
        assertFalse(client.probe(mcpServer()))
    }

    @Test
    fun `probe returns false on -32000 not-initialized stateful server`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.peek().readUtf8()).jsonObject
                return rpcError(body, -32000, "Bad Request: Server not initialized")
            }
        }
        assertFalse(client.probe(mcpServer()))
    }

    @Test
    fun `probe throws when advertised versions do not include ours`() = runBlocking {
        server.dispatcher =
            discoverServer("""{"supportedVersions":["2026-01-01"],"capabilities":{}}""")
        val e = try {
            client.probe(mcpServer()); null
        } catch (x: McpProtocolException) {
            x
        }
        assertNotNull(e)
    }

    @Test
    fun `probe throws when supportedVersions missing`() = runBlocking {
        server.dispatcher = discoverServer("""{"capabilities":{}}""")
        val e = try {
            client.probe(mcpServer()); null
        } catch (x: McpProtocolException) {
            x
        }
        assertNotNull(e)
    }

    @Test
    fun `probe discards stale session and retries once after session termination`() = runBlocking {
        // 服务器重启后的陈旧 session id：第一次 server/discover 拿到 -32000 session
        // 类错误 → 丢弃旧会话重试；第二次（无会话）成功。此前会一直携带失效 id，
        // 只能重建整个 Okia 实例恢复。
        var i = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                i++
                val body = Json.parseToJsonElement(request.body.peek().readUtf8()).jsonObject
                return if (i == 1) {
                    rpcError(body, -32000, "Bad Request: No valid session ID provided")
                } else {
                    rpcResult(body, DEFAULT_DISCOVER_RESULT)
                }
            }
        }
        assertTrue(client.probe(mcpServer()))
        val second = awaitRequests(2)[1]
        assertNull(second.getHeader("mcp-session-id"))
    }

    @Test
    fun `probe propagates unrelated json-rpc errors`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = Json.parseToJsonElement(request.body.peek().readUtf8()).jsonObject
                return rpcError(body, -32602, "invalid params")
            }
        }
        val e = try {
            client.probe(mcpServer()); null
        } catch (x: McpProtocolException) {
            x
        }
        assertNotNull(e)
        assertEquals(-32602, e!!.jsonRpcCode)
    }

    // ── tools/call ───────────────────────────────────────────────────────

    @Test
    fun `call tool carries meta and arguments`() = runBlocking {
        server.dispatcher = discoverServer(call = { b ->
            rpcResult(b, """{"content":[{"type":"text","text":"out"}]}""")
        })
        val result = client.callTool(mcpServer(), "t", """{"k":1}""")
        assertEquals(listOf("out"), result.content.map { (it as McpContentBlock.Text).text })
        val body = parseJson(awaitRequests(1)[0])
        val params = body["params"]!!.jsonObject
        assertNotNull(params["_meta"])
        assertEquals("t", params["name"]?.jsonPrimitive?.content)
        assertEquals(
            1,
            params["arguments"]!!.jsonObject["k"]?.jsonPrimitive?.let { it.content.toIntOrNull() })
    }

    @Test
    fun `call result isError true is preserved`() = runBlocking {
        server.dispatcher = discoverServer(
            call = { b -> rpcResult(b, """{"content":[],"isError":true}""") }
        )
        val result = client.callTool(mcpServer(), "t", "{}")
        assertTrue(result.isError)
    }
}