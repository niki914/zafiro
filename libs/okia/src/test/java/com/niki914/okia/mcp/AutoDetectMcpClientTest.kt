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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * AutoDetect 包装类测试：2000 探测（server/discover）成功 → 2026 模式；
 * -32601 / -32000-not-initialized → legacy 模式；模式按服务器缓存；
 * 服务器名校验 fail-fast。
 */
class AutoDetectMcpClientTest {

    private lateinit var server: MockWebServer
    private lateinit var auto: AutoDetectMcpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        auto = AutoDetectMcpClient(
            legacy = LegacyStreamableHttpMcpClient(OkHttpEngine(), HttpTimeouts(3000, 3000, 3000)),
            discovery = DiscoveryStreamableHttpMcpClient(
                OkHttpEngine(),
                HttpTimeouts(3000, 3000, 3000)
            )
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun mcpServer(name: String = "srv"): McpServer = McpServer(
        name = name,
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

    companion object {
        const val DISCOVER_RESULT =
            """{"resultType":"complete","supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
        const val INIT_RESULT =
            """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"t","version":"1"}}"""
    }

    /** 可编程服务器：discover 阶段与 legacy 阶段各自独立可配；其余方法（tools/call）走 legacy 分支。 */
    private fun programmableServer(
        onDiscover: (JsonObject) -> MockResponse,
        onLegacy: (JsonObject) -> MockResponse
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val body = Json.parseToJsonElement(request.body.peek().readUtf8()).jsonObject
            return when (body["method"]?.jsonPrimitive?.content) {
                "server/discover" -> onDiscover(body)
                else -> onLegacy(body)
            }
        }
    }

    private fun methodsOf(requests: List<RecordedRequest>): List<String> = requests.map {
        Json.parseToJsonElement(
            it.body.peek().readUtf8()
        ).jsonObject["method"]?.jsonPrimitive?.content!!
    }

    private fun awaitRequests(count: Int): List<RecordedRequest> {
        val out = ArrayList<RecordedRequest>(count)
        repeat(count) { out += server.takeRequest() }
        return out
    }

    // ── 探测与模式选择 ───────────────────────────────────────────────────

    @Test
    fun `discovery-capable server uses discovery mode`() = runBlocking {
        server.dispatcher = programmableServer(
            onDiscover = { b -> rpcResult(b, DISCOVER_RESULT) },
            onLegacy = { b -> rpcResult(b, """{"tools":[]}""") }
        )
        auto.discoverTools(mcpServer())
        // probe(1) + discover(2) + tools/list(3)
        assertEquals(
            listOf("server/discover", "server/discover", "tools/list"),
            methodsOf(awaitRequests(3))
        )
    }

    @Test
    fun `method-not-found server falls back to legacy`() = runBlocking {
        server.dispatcher = programmableServer(
            onDiscover = { b -> rpcError(b, -32601, "Method not found") },
            onLegacy = { b ->
                when (b["method"]?.jsonPrimitive?.content) {
                    "initialize" -> rpcResult(b, INIT_RESULT)
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    else -> rpcResult(b, """{"tools":[{"name":"echo"}]}""")
                }
            }
        )
        val tools = auto.discoverTools(mcpServer())
        assertEquals(listOf("echo"), tools.map { it.name })
        assertEquals(
            listOf("server/discover", "initialize", "notifications/initialized", "tools/list"),
            methodsOf(awaitRequests(4))
        )
    }

    @Test
    fun `-32000 not-initialized server falls back to legacy`() = runBlocking {
        server.dispatcher = programmableServer(
            onDiscover = { b -> rpcError(b, -32000, "Bad Request: Server not initialized") },
            onLegacy = { b ->
                when (b["method"]?.jsonPrimitive?.content) {
                    "initialize" -> rpcResult(b, INIT_RESULT)
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    else -> rpcResult(b, """{"tools":[]}""")
                }
            }
        )
        auto.discoverTools(mcpServer())
        assertEquals(
            listOf("server/discover", "initialize", "notifications/initialized", "tools/list"),
            methodsOf(awaitRequests(4))
        )
    }

    @Test
    fun `unrelated probe error propagates`() = runBlocking {
        server.dispatcher = programmableServer(
            onDiscover = { b -> rpcError(b, -32602, "bad params") },
            onLegacy = { b -> rpcResult(b, """{"tools":[]}""") }
        )
        val e = try {
            auto.discoverTools(mcpServer()); null
        } catch (x: McpProtocolException) {
            x
        }
        assertNotNull(e)
    }

    // ── 模式缓存 ─────────────────────────────────────────────────────────

    @Test
    fun `mode is cached for the server after first probe`() = runBlocking {
        server.dispatcher = programmableServer(
            onDiscover = { b -> rpcError(b, -32601, "Method not found") },
            onLegacy = { b ->
                when (b["method"]?.jsonPrimitive?.content) {
                    "initialize" -> rpcResult(b, INIT_RESULT)
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    else -> rpcResult(b, """{"tools":[]}""")
                }
            }
        )
        auto.discoverTools(mcpServer())
        awaitRequests(4)
        auto.discoverTools(mcpServer())
        // 第二次：模式已缓存，不再发 server/discover，直接 legacy 握手
        assertEquals(
            listOf("initialize", "notifications/initialized", "tools/list"),
            methodsOf(awaitRequests(3))
        )
    }

    @Test
    fun `call tool routes by cached mode without re-probing`() = runBlocking {
        server.dispatcher = programmableServer(
            onDiscover = { b -> rpcError(b, -32601, "Method not found") },
            onLegacy = { b ->
                when (b["method"]?.jsonPrimitive?.content) {
                    "initialize" -> rpcResult(b, INIT_RESULT)
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> rpcResult(b, """{"tools":[]}""")
                    else -> rpcResult(b, """{"content":[{"type":"text","text":"ok"}]}""")
                }
            }
        )
        auto.discoverTools(mcpServer())
        awaitRequests(4)
        val result = auto.callTool(mcpServer(), "echo", "{}")
        assertEquals(listOf("ok"), result.content.map { (it as McpContentBlock.Text).text })
        val calls = methodsOf(awaitRequests(1))
        assertEquals(listOf("tools/call"), calls)
    }
}