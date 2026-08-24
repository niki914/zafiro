package com.niki914.okia.transport

import com.niki914.okia.transport.HttpTimeouts
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

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

        val response = engine.stream(HttpRequest("${server.url("/v1/chat")}", "POST", emptyMap(), null, defaultTimeouts))

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

        val response = engine.stream(HttpRequest("${server.url("/v1/chat")}", "POST", emptyMap(), null, defaultTimeouts)) as StreamResponse.Ok
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

        val response = engine.stream(HttpRequest("${server.url("/v1/chat")}", "POST", emptyMap(), null, defaultTimeouts))

        assertTrue(response is StreamResponse.Error)
        val error = response as StreamResponse.Error
        assertEquals(429, error.statusCode)
        assertEquals("{\"error\":{\"message\":\"rate limited\"}}", error.body)
    }

    @Test
    fun `stream connection refused throws IOException`() = runBlocking {
        // 直接用已关闭的端口：connect refused
        val refused = HttpRequest("http://127.0.0.1:1/v1/chat", "POST", emptyMap(), null, defaultTimeouts)
        try {
            engine.stream(refused)
            fail("expected IOException on connection refused")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun `stream read timeout throws SocketTimeoutException`() = runBlocking {
        // 服务端接受连接但不发数据 → 读间隔超时
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val request = HttpRequest("${server.url("/v1/chat")}", "POST", emptyMap(), null, shortReadTimeout)
        try {
            engine.stream(request)
            fail("expected SocketTimeoutException")
        } catch (e: SocketTimeoutException) {
            // expected
        }
    }

    @Test
    fun `stream cancellation interrupts blocked read quickly`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val request = HttpRequest("${server.url("/v1/chat")}", "POST", emptyMap(), null, defaultTimeouts)
        val job = launch {
            val response = engine.stream(request) as StreamResponse.Ok
            response.lines.toList() // 永远阻塞（服务端不发数据）
        }

        // 等待请求已发出、读取挂起后再取消
        delay(200)
        val start = System.currentTimeMillis()
        job.cancelAndJoin()
        val elapsed = System.currentTimeMillis() - start

        // 取消由 call.cancel() 打断阻塞读，应在短于 readTimeout(5s) 内完成
        assertTrue("cancellation took ${elapsed}ms", elapsed < 3000)
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

        val request = HttpRequest("${server.url("/v1/chat")}", "POST", emptyMap(), body = null, timeouts = defaultTimeouts)
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

        val request = HttpRequest("${server.url("/ping")}", "GET", emptyMap(), body = null, timeouts = defaultTimeouts)
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

        val response = engine.unary(HttpRequest("${server.url("/rpc")}", "POST", emptyMap(), "{}", defaultTimeouts))

        assertEquals(200, response.statusCode)
        assertEquals("application/json", response.headers["Content-Type"])
        assertEquals("{\"ok\":true}", response.body?.toString(Charsets.UTF_8))
    }

    @Test
    fun `unary non-2xx keeps status and body`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody("boom")
        )

        val response = engine.unary(HttpRequest("${server.url("/rpc")}", "POST", emptyMap(), "{}", defaultTimeouts))

        assertEquals(500, response.statusCode)
        assertEquals("boom", response.body?.toString(Charsets.UTF_8))
    }

    @Test
    fun `unary network failure returns null status and body`() = runBlocking {
        val refused = HttpRequest("http://127.0.0.1:1/rpc", "POST", emptyMap(), "{}", defaultTimeouts)

        val response = engine.unary(refused)

        assertNull(response.statusCode)
        assertNull(response.body)
        assertTrue(response.headers.isEmpty())
    }

    @Test
    fun `unary empty body returns null body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204).setBody(""))

        val response = engine.unary(HttpRequest("${server.url("/ping")}", "POST", emptyMap(), null, defaultTimeouts))

        assertEquals(204, response.statusCode)
        // 204 无内容：okhttp 给出 size=0 的 body（非 null）
        assertEquals(0, response.body?.size)
    }

    // ── 请求超时参数 ────────────────────────────────────────────────────────

    @Test
    fun `unary read timeout returns null status and body`() = runBlocking {
        // NO_RESPONSE：接受连接后不发任何数据 → 读超时（传输失败语义：
        // 返回缺省结构而非抛异常，HttpResponse 契约）
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val short = HttpTimeouts(connectMs = 5000, readMs = 150, writeMs = 5000)
        val request = HttpRequest("${server.url("/slow")}", "GET", emptyMap(), null, short)
        val response = engine.unary(request)

        assertNull(response.statusCode)
        assertNull(response.body)
        assertTrue(response.headers.isEmpty())
    }

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
            HttpRequest(server.url("/injected").toString(), "GET", emptyMap(), null, defaultTimeouts)
        )

        assertEquals(200, response.statusCode)
        val recorded = server.takeRequest()
        assertEquals("yes", recorded.getHeader("X-Injected"))
    }
}