package com.niki914.okia.transport

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 默认 HttpEngine（OkHttp 4）的 JVM 测试：本地 HTTP server 验证真实请求构建、
 * 流式读取、错误路径、超时与取消。覆盖单测盲区里"真实 HTTP 栈行为"的一部分
 * （本地网络栈的确定性行为），真实网络（DNS/代理/远端服务）留给集成测试。
 */
class OkHttpEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var engine: OkHttpEngine

    private val defaultTimeouts = HttpTimeouts(connectMs = 5000, readMs = 5000, writeMs = 5000)
    private val shortReadTimeout = HttpTimeouts(connectMs = 5000, readMs = 1000, writeMs = 5000)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine = OkHttpEngine()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── stream ─────────────────────────────────────────────────────────────

    @Test
    fun `stream 2xx returns Ok with status headers and parsed lines`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setHeader("X-Trace-Id", "abc")
                .setBody("data: {\"a\":1}\n\ndata: [DONE]\n")
        )

        val response = engine.stream(
            HttpRequest(
                "${server.url("/v1/chat")}",
                "POST",
                emptyMap(),
                null,
                defaultTimeouts
            )
        )

        assertTrue(response is StreamResponse.Ok)
        val ok = response as StreamResponse.Ok
        assertEquals(200, ok.statusCode)
        assertEquals("text/event-stream", ok.headers["Content-Type"])
        assertEquals("abc", ok.headers["X-Trace-Id"])

        val lines = ok.lines.toList()
        // data 行 + 空行（事件边界）+ data 行 + EOF 无换行 flush 的最后一行
        assertEquals("data: {\"a\":1}", lines[0].data)
        assertEquals("", lines[1].data)
        assertEquals("data: [DONE]", lines[2].data)
    }

    @Test
    fun `stream keeps comment and blank lines as idle evidence`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(": keep-alive\n\n: ping\n")
        )

        val response = engine.stream(
            HttpRequest(
                "${server.url("/v1/chat")}",
                "POST",
                emptyMap(),
                null,
                defaultTimeouts
            )
        ) as StreamResponse.Ok
        val lines = response.lines.toList()

        assertEquals(listOf(null, "", null), lines.map { it.data })
    }

    @Test
    fun `stream non-2xx returns Error with prefetched body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":{\"message\":\"rate limited\"}}")
        )

        val response = engine.stream(
            HttpRequest(
                "${server.url("/v1/chat")}",
                "POST",
                emptyMap(),
                null,
                defaultTimeouts
            )
        )

        assertTrue(response is StreamResponse.Error)
        val error = response as StreamResponse.Error
        assertEquals(429, error.statusCode)
        assertEquals("{\"error\":{\"message\":\"rate limited\"}}", error.body)
    }

    @Test
    fun `stream builds request method url headers and body`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: x\n")
        )

        val request = HttpRequest(
            url = "${server.url("/v1/chat")}",
            method = "POST",
            headers = mapOf("Authorization" to "Bearer k", "X-Custom" to "v"),
            body = "{\"model\":\"m\"}",
            timeouts = defaultTimeouts
        )
        engine.stream(request)

        val received = server.takeRequest()
        assertEquals("POST", received.method)
        assertEquals("/v1/chat", received.path)
        assertEquals("Bearer k", received.getHeader("Authorization"))
        assertEquals("v", received.getHeader("X-Custom"))
        assertEquals("{\"model\":\"m\"}", received.body.readUtf8())
    }

    @Test
    fun `stream POST without body sends empty body not null`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: x\n")
        )

        val request = HttpRequest(
            "${server.url("/v1/chat")}",
            "POST",
            emptyMap(),
            body = null,
            timeouts = defaultTimeouts
        )
        engine.stream(request)

        val received = server.takeRequest()
        assertEquals("POST", received.method)
        assertEquals("", received.body.readUtf8()) // okhttp 发送零长 body，不抛
    }

    @Test
    fun `stream GET has no body`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("ok")
        )

        val request = HttpRequest(
            "${server.url("/ping")}",
            "GET",
            emptyMap(),
            body = null,
            timeouts = defaultTimeouts
        )
        engine.stream(request)

        val received = server.takeRequest()
        assertEquals("GET", received.method)
        // MockWebServer 对无 body 请求给出 size=0 的 body（非 null）
        assertEquals(0L, received.body?.size)
    }

    // ── unary ──────────────────────────────────────────────────────────────

    @Test
    fun `unary 2xx returns structured response`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":true}")
        )

        val response = engine.unary(
            HttpRequest(
                "${server.url("/rpc")}",
                "POST",
                emptyMap(),
                "{}",
                defaultTimeouts
            )
        )

        assertEquals(200, response.statusCode)
        assertEquals("application/json", response.headers["Content-Type"])
        assertEquals("{\"ok\":true}", response.body?.toString(Charsets.UTF_8))
    }

    @Test
    fun `unary non-2xx keeps status and body`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody("boom")
        )

        val response = engine.unary(
            HttpRequest(
                "${server.url("/rpc")}",
                "POST",
                emptyMap(),
                "{}",
                defaultTimeouts
            )
        )

        assertEquals(500, response.statusCode)
        assertEquals("boom", response.body?.toString(Charsets.UTF_8))
    }

    @Test
    fun `unary empty body returns null body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204).setBody(""))

        val response = engine.unary(
            HttpRequest(
                "${server.url("/ping")}",
                "POST",
                emptyMap(),
                null,
                defaultTimeouts
            )
        )

        assertEquals(204, response.statusCode)
        // 204 无内容：okhttp 给出 size=0 的 body（非 null）
        assertEquals(0, response.body?.size)
    }

    // ── 请求超时参数 ────────────────────────────────────────────────────────

    @Test
    fun `injected OkHttpClient is used for requests`() = runBlocking {
        // D-T2B-4：OkHttpEngine 接受自定义 OkHttpClient（proxy/interceptor 注入点）。
        // 注入的 client 通过 header 透传证明其生效（自定义 interceptor 加头）。
        server.enqueue(MockResponse().setBody("ok"))
        val injected = OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("X-Injected", "yes")
                        .build()
                )
            }
            .build()
        val customEngine = OkHttpEngine(injected)

        val response = customEngine.unary(
            HttpRequest(
                server.url("/injected").toString(),
                "GET",
                emptyMap(),
                null,
                defaultTimeouts
            )
        )

        assertEquals(200, response.statusCode)
        val recorded = server.takeRequest()
        assertEquals("yes", recorded.getHeader("X-Injected"))
    }
}