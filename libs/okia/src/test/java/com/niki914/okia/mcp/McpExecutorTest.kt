package com.niki914.okia.mcp

import com.niki914.okia.message.ToolCallOutcome
import com.niki914.okia.tooling.ToolCallContext
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * McpExecutor 路由与 outcome 映射测试（T9b-1）：路由正确性（kind 的服务器名
 * → client 收到的配置）、工具名还原、5 态 outcome 映射（多块拼接 / isError /
 * 协议异常 / 兜底 / 取消传播）、永不抛异常契约、onInterrupt 语义。
 * 测试断言公开面可观察行为（client 收到参数 / outcome 值），不依赖实现内部结构。
 */
class McpExecutorTest {

    // ── fixtures ───────────────────────────────────────────────────────────

    private class FakeClient : McpClient {
        data class Call(val server: McpServer, val toolName: String, val argumentsJson: String)

        val calls = mutableListOf<Call>()
        var result: McpCallResult = McpCallResult(isError = false, content = emptyList())
        var error: Throwable? = null

        override suspend fun discoverTools(server: McpServer): List<McpDiscoveredTool> = emptyList()

        override suspend fun callTool(
            server: McpServer,
            toolName: String,
            argumentsJson: String
        ): McpCallResult {
            calls += Call(server, toolName, argumentsJson)
            error?.let { throw it }
            return result
        }
    }

    private fun server(name: String, headers: Map<String, String> = emptyMap()) = McpServer(
        name = name,
        transport = McpTransport.Http("http://localhost/$name"),
        headers = headers,
        enabled = true
    )

    // kind 指明服务器；descriptor.name = 原始 MCP 工具名（直接用于调用）
    private fun mcpCall(
        name: String = "search",
        serverName: String = "docs",
        args: String = "{\"q\":\"x\"}"
    ) = ToolCallContext(
        id = "call-1",
        name = "mcp__docs__search",
        descriptor = ToolDescriptor(
            name = name,
            description = "desc",
            inputSchemaJson = "{\"type\":\"object\"}",
            kind = ToolKind.Mcp(serverName)
        ),
        argumentsJson = args
    )

    private fun TextBlock(text: String) = McpContentBlock.Text(text)

    private fun executor(
        client: FakeClient,
        servers: Map<String, McpServer> = mapOf("docs" to server("docs"))
    ) = McpExecutor(client) { servers[it] }

    // ── 路由与工具名还原 ───────────────────────────────────────────────────

    @Test
    fun routesToServerByKindAndUsesRawName() = runTest {
        val client = FakeClient().apply {
            result = McpCallResult(false, listOf(TextBlock("ok")))
        }
        val exec = executor(client)

        // descriptor.name = 原始 MCP 工具名，line 缆线名（call.name）不参与调用
        val outcome = exec.execute(mcpCall(name = "admin.tools.list", serverName = "docs"))

        assertEquals(1, client.calls.size)
        assertEquals("docs", client.calls.single().server.name)
        assertEquals("admin.tools.list", client.calls.single().toolName) // 原始名原样调用
        assertEquals("{\"q\":\"x\"}", client.calls.single().argumentsJson) // 参数原样
        assertEquals(ToolCallOutcome.Success("ok"), outcome)
    }

    @Test
    fun passesServerHeadersThrough() = runTest {
        val client = FakeClient()
        val servers = mapOf("docs" to server("docs", headers = mapOf("Authorization" to "Bearer t")))
        val exec = executor(client, servers)

        exec.execute(mcpCall())

        assertEquals("Bearer t", client.calls.single().server.headers["Authorization"])
    }

    // ── 成功映射 ───────────────────────────────────────────────────────────

    @Test
    fun joinsMultipleTextBlocksWithNewline() = runTest {
        val client = FakeClient().apply {
            result = McpCallResult(false, listOf(TextBlock("first"), TextBlock("second")))
        }
        val outcome = executor(client).execute(mcpCall())
        assertEquals(ToolCallOutcome.Success("first\nsecond"), outcome)
    }

    @Test
    fun singleTextBlockPassesThrough() = runTest {
        val client = FakeClient().apply { result = McpCallResult(false, listOf(TextBlock("only"))) }
        assertEquals(ToolCallOutcome.Success("only"), executor(client).execute(mcpCall()))
    }

    @Test
    fun emptyContentIsEmptySuccess() = runTest {
        val client = FakeClient().apply { result = McpCallResult(false, emptyList()) }
        assertEquals(ToolCallOutcome.Success(""), executor(client).execute(mcpCall()))
    }

    // ── isError 映射 ───────────────────────────────────────────────────────

    @Test
    fun isErrorBecomesFailureWithContent() = runTest {
        val client = FakeClient().apply {
            result = McpCallResult(true, listOf(TextBlock("input missing"), TextBlock("detail")))
        }
        val outcome = executor(client).execute(mcpCall())
        val failure = outcome as ToolCallOutcome.Failure
        assertEquals("input missing\ndetail", failure.content)
    }

    @Test
    fun isErrorWithEmptyContentHasNullContent() = runTest {
        val client = FakeClient().apply { result = McpCallResult(true, emptyList()) }
        val failure = executor(client).execute(mcpCall()) as ToolCallOutcome.Failure
        assertNull(failure.content)
    }

    // ── 异常 → outcome（永不抛异常契约） ──────────────────────────────────

    @Test
    fun protocolExceptionBecomesFailureWithMessage() = runTest {
        val client = FakeClient().apply {
            error = McpProtocolException("MCP JSON-RPC error: boom", jsonRpcCode = -32602)
        }
        val failure = executor(client).execute(mcpCall()) as ToolCallOutcome.Failure
        assertEquals("MCP JSON-RPC error: boom", failure.message)
        assertNull(failure.content)
    }

    @Test
    fun runtimeExceptionBecomesFailure() = runTest {
        val client = FakeClient().apply { error = IllegalStateException("client broken") }
        val failure = executor(client).execute(mcpCall()) as ToolCallOutcome.Failure
        assertTrue(failure.message?.contains("client broken") == true)
    }

    @Test
    fun cancellationIsNotSwallowed() = runTest {
        val client = FakeClient().apply {
            error = CancellationException("cancelled")
        }
        try {
            executor(client).execute(mcpCall())
            throw AssertionError("should have rethrown cancellation")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    // ── 防御路径 ───────────────────────────────────────────────────────────

    @Test
    fun missingServerIsFailureNotThrow() = runTest {
        val client = FakeClient()
        val exec = executor(client, servers = emptyMap())
        exec.execute(mcpCall()) as ToolCallOutcome.Failure
        assertEquals(0, client.calls.size) // 未发请求
    }

    @Test
    fun localKindIsRejected() = runTest {
        val client = FakeClient()
        val exec = executor(client)
        val call = mcpCall().copy(
            descriptor = ToolDescriptor("search", "desc", null, ToolKind.Local)
        )
        exec.execute(call) as ToolCallOutcome.Failure
        assertEquals(0, client.calls.size)
    }

    // ── onInterrupt ────────────────────────────────────────────────────────

    @Test
    fun onInterruptReturnsUnknown() {
        val client = FakeClient()
        val outcome = executor(client).onInterrupt(mcpCall())
        assertTrue(outcome is ToolCallOutcome.Unknown)
    }
}