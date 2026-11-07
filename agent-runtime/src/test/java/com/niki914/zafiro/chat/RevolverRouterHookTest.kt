// 保护：r22c B4 轮值路由 hook —— 每次发送尝试前把请求重指到网关选定的候选
package com.niki914.zafiro.chat

import com.niki914.logging.Logger
import com.niki914.okia.hooks.HttpRequestHolder
import com.niki914.okia.transport.HttpRequest
import com.niki914.okia.transport.HttpTimeouts
import com.niki914.zafiro.chat.util.SilentLoggerRule
import com.niki914.zafiro.settings.model.LlmProtocol
import com.niki914.zafiro.settings.model.RuntimeRevolverPick
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class RevolverRouterHookTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    private val timeouts = HttpTimeouts(30_000L, 120_000L, 30_000L)

    @Before
    fun setUp() {
        installRuntimeSettingsGatewayForTest()
    }

    @After
    fun tearDown() {
        com.niki914.zafiro.settings.RuntimeEnvironment.clearForTest()
    }

    private fun originalRequest(
        url: String = "https://a.example/v1/chat/completions",
        headers: Map<String, String> = mapOf("Authorization" to "Bearer key-a"),
        body: String = """{"model":"model-a","messages":[]}""",
    ) = HttpRequest(url, "POST", headers, body, timeouts)

    private fun pick(
        endpoint: String = "https://b.example/v1/chat/completions",
        apiKey: String = "key-b",
        model: String = "model-b",
        protocol: String = LlmProtocol.OpenAiChatCompletions.wireId,
    ) = RuntimeRevolverPick(
        configId = "b",
        endpoint = endpoint,
        apiKey = apiKey,
        model = model,
        protocol = protocol,
        label = "model-b · B",
        waitMs = 0L,
    )

    @Test
    fun `openai candidate repoints url auth and body model`() = runTest {
        val gw = installRuntimeSettingsGatewayForTest()
        gw.pickSequence.add(pick())

        val holder = HttpRequestHolder(originalRequest())
        RevolverRouterHook.beforeRequest(holder)

        val req = holder.request
        assertEquals("https://b.example/v1/chat/completions", req.url)
        assertEquals("Bearer key-b", req.headers["Authorization"])
        assertNull(req.headers["x-api-key"])
        assertTrue(req.body!!.contains("\"model\":\"model-b\""))
        assertEquals("revolver", holder.lastWriter)
        assertEquals(1, gw.pickCallCount)
    }

    @Test
    fun `anthropic candidate switches auth header to x-api-key and version`() = runTest {
        val gw = installRuntimeSettingsGatewayForTest()
        gw.pickSequence.add(pick(protocol = LlmProtocol.AnthropicMessages.wireId))

        val holder = HttpRequestHolder(originalRequest())
        RevolverRouterHook.beforeRequest(holder)

        val req = holder.request
        assertEquals("key-b", req.headers["x-api-key"])
        assertEquals("2023-06-01", req.headers["anthropic-version"])
        assertNull(req.headers["Authorization"])
    }

    @Test
    fun `null pick is a no-op keeping original request`() = runTest {
        // 轮值关闭 / 候选不足：网关回落默认实现（null），hook 必须原样放行。
        val holder = HttpRequestHolder(originalRequest())
        RevolverRouterHook.beforeRequest(holder)

        assertEquals("https://a.example/v1/chat/completions", holder.request.url)
        assertEquals("Bearer key-a", holder.request.headers["Authorization"])
        assertNull(holder.lastWriter)
    }

    @Test
    fun `same-source candidate short-circuits without writing`() = runTest {
        val gw = installRuntimeSettingsGatewayForTest()
        gw.pickSequence.add(
            pick(
                endpoint = "https://a.example/v1/chat/completions",
                apiKey = "key-a",
                model = "model-a",
            )
        )

        val holder = HttpRequestHolder(originalRequest())
        RevolverRouterHook.beforeRequest(holder)

        // 候选与请求同源：不 write（无签名），请求逐字节不变。
        assertNull(holder.lastWriter)
        assertEquals(originalRequest(), holder.request)
        assertEquals(1, gw.pickCallCount)
    }

    @Test
    fun `non-object body keeps original body`() = runTest {
        val gw = installRuntimeSettingsGatewayForTest()
        gw.pickSequence.add(pick())

        val holder = HttpRequestHolder(originalRequest(body = "plain-text"))
        RevolverRouterHook.beforeRequest(holder)

        assertEquals("plain-text", holder.request.body)
        assertEquals("revolver", holder.lastWriter)
    }

    @Test
    fun `gateway failure degrades to original request`() = runTest {
        // pick 抛异常（网关不可用）：hook 必须吞掉并原样放行，绝不中断会话。
        val gw = installRuntimeSettingsGatewayForTest()
        gw.failPickWith = IllegalStateException("gateway down")
        val holder = HttpRequestHolder(originalRequest())
        RevolverRouterHook.beforeRequest(holder)
        assertEquals(originalRequest(), holder.request)
        assertNull(holder.lastWriter)
    }
}
