package com.niki914.okia

import com.niki914.okia.fake.FakeAgentLoop
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.mcp.McpCallResult
import com.niki914.okia.mcp.McpClient
import com.niki914.okia.mcp.McpDiscoveredTool
import com.niki914.okia.mcp.McpDiscoveryState
import com.niki914.okia.mcp.McpServer
import com.niki914.okia.mcp.McpTransport
import com.niki914.okia.tooling.DefaultToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 门面 MCP 集成测试（T9b-3）：refreshMcpTools / getMcpDiscoverySnapshot 的
 * 门面契约（closed 检查、回合内刷新允许（issue #125））、默认 registry 装配（config 未
 * 注入时 MCP 工具注册进门面默认实例）、注入 registry 时注册进注入实例、
 * update 热更新后刷新对全部配置生效。
 */
class RealOkiaMcpTest {

    // ── fixtures ───────────────────────────────────────────────────────────

    private class FakeClient : McpClient {
        var tools: List<McpDiscoveredTool> = listOf(McpDiscoveredTool("search", "find", "{}"))
        var error: Throwable? = null
        override suspend fun discoverTools(server: McpServer): List<McpDiscoveredTool> {
            error?.let { throw it }
            return tools
        }

        override suspend fun callTool(
            server: McpServer,
            toolName: String,
            argumentsJson: String
        ): McpCallResult = McpCallResult(false, emptyList())
    }

    private fun deps(client: McpClient, agentLoop: FakeAgentLoop): OkiaDependencies =
        object : OkiaDependencies {
            override val agentLoop = agentLoop
            override val protocolMapper = FakeProtocolMapper(emptyList<com.niki914.okia.protocol.ProtocolEvent>())
            override val mcpClient = client
        }

    private fun server(name: String, enabled: Boolean = true) = McpServer(
        name = name,
        transport = McpTransport.Http("http://localhost/$name"),
        headers = emptyMap(),
        enabled = enabled
    )

    // ── 默认 registry 装配 ─────────────────────────────────────────────────

    @Test
    fun refreshRegistersToolsIntoDefaultRegistryVisibleToLoop() = runTest {
        val client = FakeClient()
        var seenRegistry: com.niki914.okia.tooling.ToolRegistry? = null
        val agentLoop = FakeAgentLoop { request, _ ->
            seenRegistry = request.toolRegistry
            TurnResult.Completed(com.niki914.okia.loop.CompletionReason.Stop)
        }
        val okia = Okia.open(
            dependencies = deps(client, agentLoop),
            builder = { mcpServers = listOf(server("docs")) }
        )

        val result = okia.refreshMcpTools()

        assertEquals(listOf("docs"), result.refreshedServers)
        // config 未注入 toolRegistry → 门面默认实例持有 MCP 工具
        assertTrue(okia.config().toolRegistry == null)
        okia.send("hi") {} // 触发 loop，检查传给 loop 的 registry
        assertNotNull(seenRegistry!!.find("mcp__docs__search"))
        assertEquals(
            com.niki914.okia.tooling.ToolKind.Mcp("docs"),
            seenRegistry!!.find("mcp__docs__search")!!.descriptor.kind
        )
        okia.close()
    }

    @Test
    fun injectedRegistryReceivesMcpTools() = runTest {
        val client = FakeClient()
        val injected = DefaultToolRegistry()
        val agentLoop = FakeAgentLoop()
        val okia = Okia.open(
            dependencies = deps(client, agentLoop),
            builder = {
                mcpServers = listOf(server("docs"))
                toolRegistry = injected
            }
        )

        okia.refreshMcpTools()

        assertNotNull(injected.find("mcp__docs__search")) // 注册进注入实例
        okia.close()
    }

    // ── 并发契约 ───────────────────────────────────────────────────────────

    @Test
    fun refreshDuringActiveTurnSucceeds() = runTest {
        val gate = CompletableDeferred<Unit>()
        val agentLoop = FakeAgentLoop { _, _ ->
            gate.await()
            TurnResult.Completed(com.niki914.okia.loop.CompletionReason.Stop)
        }
        val okia = Okia.open(
            dependencies = deps(FakeClient(), agentLoop),
            builder = { mcpServers = listOf(server("docs")) }
        )
        val sendJob = launch { okia.send("hi") {} }
        runCurrent()

        // 回合内刷新允许（issue #125）：发现状态与回合独立，不抛异常
        okia.refreshMcpTools()

        gate.complete(Unit)
        sendJob.join()
        okia.close()
    }

    @Test
    fun snapshotIsReadableDuringActiveTurn() = runTest {
        val gate = CompletableDeferred<Unit>()
        val agentLoop = FakeAgentLoop { _, _ ->
            gate.await()
            TurnResult.Completed(com.niki914.okia.loop.CompletionReason.Stop)
        }
        val okia = Okia.open(
            dependencies = deps(FakeClient(), agentLoop),
            builder = { mcpServers = listOf(server("docs")) }
        )
        val sendJob = launch { okia.send("hi") {} }
        runCurrent()

        // 只读快照：活跃回合允许（§8.7 #5 列表不含本方法）
        okia.getMcpDiscoverySnapshot()

        gate.complete(Unit)
        sendJob.join()
        okia.close()
    }

    // ── 状态与更新 ─────────────────────────────────────────────────────────

    @Test
    fun refreshFailureReflectsInSnapshot() = runTest {
        val client = FakeClient().apply { error = RuntimeException("down") }
        val okia = Okia.open(
            dependencies = deps(client, FakeAgentLoop()),
            builder = { mcpServers = listOf(server("docs")) }
        )

        val result = okia.refreshMcpTools()

        assertTrue(result.failedServers == listOf("docs"))
        val state = okia.getMcpDiscoverySnapshot().servers.getValue("docs")
        assertEquals(McpDiscoveryState.Failed, state.state)
        assertNotNull(state.errorMessage)
        okia.close()
    }

    @Test
    fun updateWithNewServersTakesEffectOnNextRefresh() = runTest {
        val client = FakeClient()
        val okia = Okia.open(
            dependencies = deps(client, FakeAgentLoop()),
            builder = { mcpServers = listOf(server("a")) }
        )

        okia.refreshMcpTools()
        assertEquals(setOf("a"), okia.getMcpDiscoverySnapshot().servers.keys)

        okia.update { mcpServers = listOf(server("a"), server("b", enabled = false)) }
        okia.refreshMcpTools()

        assertEquals(setOf("a", "b"), okia.getMcpDiscoverySnapshot().servers.keys) // 新配置可见
        okia.close()
    }

    @Test
    fun refreshAfterCloseThrows() = runTest {
        val okia = Okia.open(
            dependencies = deps(FakeClient(), FakeAgentLoop()),
            builder = { mcpServers = listOf(server("docs")) }
        )
        okia.close()
        try {
            okia.refreshMcpTools()
            throw AssertionError("should have thrown after close")
        } catch (e: IllegalStateException) {
            // expected
        }
    }
}