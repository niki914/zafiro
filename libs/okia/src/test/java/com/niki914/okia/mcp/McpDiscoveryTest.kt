package com.niki914.okia.mcp

import com.niki914.okia.tooling.DefaultToolRegistry
import com.niki914.okia.tooling.ToolKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 发现状态机测试（T9b-2）：状态转换（Idle → Discovering → Available /
 * Failed / UsingStaleCache）、注册幂等（覆盖 / 消失移除）、fingerprint 报告、
 * 冲突（DuplicateInServer）、并发刷新、取消传播。
 * 测试断言公开面可观察行为（快照字段 / registry 内容 / 刷新结果），
 * 不依赖实现内部结构。
 */
class McpDiscoveryTest {

    // ── fixtures ───────────────────────────────────────────────────────────

    private class FakeClient : McpClient {
        var tools: (name: String) -> List<McpDiscoveredTool> = { emptyList() }
        var error: (name: String) -> Throwable? = { null }
        var gate: CompletableDeferred<Unit>? = null
        val discovered = mutableListOf<String>()

        override suspend fun discoverTools(server: McpServer): List<McpDiscoveredTool> {
            discovered += server.name
            gate?.await()
            error(server.name)?.let { throw it }
            return tools(server.name)
        }

        override suspend fun callTool(
            server: McpServer,
            toolName: String,
            argumentsJson: String
        ): McpCallResult = McpCallResult(false, emptyList())
    }

    private fun server(name: String, enabled: Boolean = true) = McpServer(
        name = name,
        transport = McpTransport.Http("http://localhost/$name"),
        headers = emptyMap(),
        enabled = enabled
    )

    private fun tool(name: String, description: String = "desc", schema: String = "{}") =
        McpDiscoveredTool(name, description, schema)

    private fun discovery(
        client: FakeClient,
        servers: List<McpServer>,
        registry: DefaultToolRegistry = DefaultToolRegistry()
    ) = McpDiscovery(client, servers = { servers }, registry = { registry })

    // ── 初始状态 ───────────────────────────────────────────────────────────

    @Test
    fun initialSnapshotListsAllServersAsIdle() {
        val d = discovery(FakeClient(), listOf(server("a"), server("b", enabled = false)))
        val snap = d.current()
        assertEquals(setOf("a", "b"), snap.servers.keys)
        assertEquals(McpDiscoveryState.Idle, snap.servers.getValue("a").state)
        assertTrue(snap.servers.getValue("a").enabled)
        assertTrue(!snap.servers.getValue("b").enabled)
        assertNull(snap.servers.getValue("a").fingerprint)
        assertEquals(0, snap.servers.getValue("a").discoveredToolCount)
        assertTrue(snap.servers.getValue("a").tools.isEmpty())
    }

    // ── 刷新成功 ───────────────────────────────────────────────────────────

    @Test
    fun refreshSuccessRegistersToolsAndUpdatesState() = runTest {
        val registry = DefaultToolRegistry()
        val client = FakeClient().apply {
            tools = { listOf(tool("search", "find things", "{\"type\":\"object\"}")) }
        }
        val d = discovery(client, listOf(server("docs")), registry)

        val result = d.refresh()

        assertEquals(listOf("docs"), result.refreshedServers)
        assertTrue(result.failedServers.isEmpty())
        val state = d.current().servers.getValue("docs")
        assertEquals(McpDiscoveryState.Available, state.state)
        assertNull(state.errorMessage)
        assertNotNull(state.lastSuccessAtMillis)
        assertNotNull(state.fingerprint)
        assertEquals(1, state.discoveredToolCount)
        assertEquals("search", state.tools.single().name)
        // 注册进 registry：线缆名 = mcp__server__tool，name = 原始工具名，kind = Mcp(server)，元数据透传
        val registered = registry.find("mcp__docs__search")
        assertNotNull(registered)
        assertEquals("search", registered!!.descriptor.name)
        assertEquals("mcp__docs__search", registered.descriptor.wireName)
        assertEquals("find things", registered.descriptor.description)
        assertEquals("{\"type\":\"object\"}", registered.descriptor.inputSchemaJson)
        assertEquals(ToolKind.Mcp("docs"), registered.descriptor.kind)
    }

    @Test
    fun refreshMissingServerRemovesVanishTool() = runTest {
        val registry = DefaultToolRegistry()
        val client = FakeClient().apply {
            tools = { listOf(tool("keep"), tool("gone")) }
        }
        val d = discovery(client, listOf(server("a")), registry)
        d.refresh()
        assertNotNull(registry.find("mcp__a__keep"))
        assertNotNull(registry.find("mcp__a__gone"))

        client.tools = { listOf(tool("keep")) }
        d.refresh()

        assertNotNull(registry.find("mcp__a__keep"))
        assertNull(registry.find("mcp__a__gone")) // 消失的工具被移除
    }

    @Test
    fun refreshOverwritesUpdatedToolDescription() = runTest {
        val registry = DefaultToolRegistry()
        val client = FakeClient().apply { tools = { listOf(tool("t", "old")) } }
        val d = discovery(client, listOf(server("a")), registry)
        d.refresh()

        client.tools = { listOf(tool("t", "new")) }
        d.refresh()

        assertEquals("new", registry.find("mcp__a__t")!!.descriptor.description)
        assertEquals(1, registry.snapshot().size)
    }

    @Test
    fun refreshTwiceKeepsRegistryStable() = runTest {
        val registry = DefaultToolRegistry()
        val client = FakeClient().apply { tools = { listOf(tool("t")) } }
        val d = discovery(client, listOf(server("a")), registry)
        d.refresh()
        d.refresh()
        assertEquals(1, registry.snapshot().size) // 幂等：不重复注册
    }

    // ── fingerprint ────────────────────────────────────────────────────────

    @Test
    fun fingerprintIsStableForSameToolSetAndChangesWithTools() = runTest {
        val client = FakeClient()
        val d = discovery(client, listOf(server("a")))
        client.tools = { listOf(tool("x", "d1"), tool("y")) }
        d.refresh()
        val first = d.current().servers.getValue("a").fingerprint
        // 相同工具集（顺序无关）
        client.tools = { listOf(tool("y"), tool("x", "d1")) }
        d.refresh()
        assertEquals(first, d.current().servers.getValue("a").fingerprint)
        // 工具集变化
        client.tools = { listOf(tool("x", "d2")) }
        d.refresh()
        assertTrue(first != d.current().servers.getValue("a").fingerprint)
    }

    // ── 刷新失败 ───────────────────────────────────────────────────────────

    @Test
    fun refreshFailureWithoutCacheIsFailed() = runTest {
        val client = FakeClient().apply { error = { McpProtocolException("server down") } }
        val d = discovery(client, listOf(server("a")))

        val result = d.refresh()

        assertEquals(listOf("a"), result.failedServers)
        assertTrue(result.refreshedServers.isEmpty())
        val state = d.current().servers.getValue("a")
        assertEquals(McpDiscoveryState.Failed, state.state)
        assertEquals("server down", state.errorMessage)
        assertNull(state.lastSuccessAtMillis)
    }

    @Test
    fun refreshFailureWithCacheIsUsingStaleCacheAndKeepsTools() = runTest {
        val registry = DefaultToolRegistry()
        val client = FakeClient().apply { tools = { listOf(tool("t")) } }
        val d = discovery(client, listOf(server("a")), registry)
        d.refresh() // 成功：有缓存

        client.error = { McpProtocolException("down again") }
        val result = d.refresh()

        assertEquals(listOf("a"), result.failedServers)
        val state = d.current().servers.getValue("a")
        assertEquals(McpDiscoveryState.UsingStaleCache, state.state)
        assertEquals("down again", state.errorMessage)
        assertNotNull(state.lastSuccessAtMillis) // 保留上次成功时间
        assertEquals(listOf("t"), state.tools.map { it.name }) // 旧工具快照保留
        assertNotNull(registry.find("mcp__a__t")) // 旧注册保留可用
    }

    @Test
    fun refreshFailureKeepsFingerprintForComparison() = runTest {
        val client = FakeClient().apply { tools = { listOf(tool("t")) } }
        val d = discovery(client, listOf(server("a")))
        d.refresh()
        val fp = d.current().servers.getValue("a").fingerprint

        client.error = { McpProtocolException("down") }
        d.refresh()
        assertEquals(fp, d.current().servers.getValue("a").fingerprint)
    }

    // ── enabled=false ──────────────────────────────────────────────────────

    @Test
    fun disabledServerIsNotDiscovered() = runTest {
        val registry = DefaultToolRegistry()
        val client = FakeClient()
        val d = discovery(client, listOf(server("a"), server("b", enabled = false)), registry)
        val result = d.refresh()

        assertEquals(listOf("a"), result.refreshedServers)
        assertTrue(client.discovered.none { it == "b" }) // 跳过，不连接
        assertEquals(McpDiscoveryState.Idle, d.current().servers.getValue("b").state)
    }

    @Test
    fun disabledServerToolsAreUnregisteredAndServerNotConnected() = runTest {
        // 状态转换：enabled → disabled → refresh。工具注销（disabled = 不暴露、
        // 不可调用），服务器不被连接；discovery snapshot / fingerprint 保留。
        val registry = DefaultToolRegistry()
        val client = FakeClient().apply { tools = { listOf(tool("t")) } }
        val servers = mutableListOf(server("a"), server("b"))
        val d = McpDiscovery(client, servers = { servers }, registry = { registry })
        d.refresh()
        assertNotNull(registry.find("mcp__a__t"))
        assertNotNull(registry.find("mcp__b__t"))

        servers[1] = server("b", enabled = false) // 禁用 b
        client.discovered.clear()
        val result = d.refresh()

        // b 的工具从 registry 注销，a 不受影响；b 不被连接
        assertNull(registry.find("mcp__b__t"))
        assertNotNull(registry.find("mcp__a__t"))
        assertTrue(client.discovered.none { it == "b" })
        assertEquals(listOf("a"), result.refreshedServers)
        // 诊断信息保留：snapshot 仍显示 b（含 fingerprint / 上次工具集）
        val snap = d.current().servers.getValue("b")
        assertFalse(snap.enabled)
        assertNotNull(snap.fingerprint)
        assertEquals(McpDiscoveryState.Available, snap.state)
    }

    @Test
    fun reEnabledServerToolsAreReRegisteredOnRefresh() = runTest {
        // 状态转换：disabled → enabled → refresh。工具重新注册，状态回 Available。
        val registry = DefaultToolRegistry()
        val client = FakeClient().apply { tools = { listOf(tool("t")) } }
        val servers = mutableListOf(server("a"), server("b"))
        val d = McpDiscovery(client, servers = { servers }, registry = { registry })
        d.refresh()
        assertNotNull(registry.find("mcp__b__t"))

        servers[1] = server("b", enabled = false)
        d.refresh()
        assertNull(registry.find("mcp__b__t"))

        servers[1] = server("b") // 重新启用
        d.refresh()
        assertNotNull(registry.find("mcp__b__t"))
        assertEquals(McpDiscoveryState.Available, d.current().servers.getValue("b").state)
    }

    // ── 冲突（DuplicateInServer） ──────────────────────────────────────────

    @Test
    fun duplicateToolInServerKeepsFirstAndReportsConflict() = runTest {
        val registry = DefaultToolRegistry()
        val client = FakeClient().apply {
            tools = { listOf(tool("search", "first"), tool("other"), tool("search", "second")) }
        }
        val d = discovery(client, listOf(server("docs")), registry)

        d.refresh()

        // 保留第一个
        assertEquals("first", registry.find("mcp__docs__search")!!.descriptor.description)
        assertEquals(2, registry.snapshot().size)
        // 冲突报告
        val conflict = d.current().conflicts.single()
        assertEquals("mcp__docs__search", conflict.name)
        assertEquals(ToolConflictReason.DuplicateInServer, conflict.reason)
        assertEquals(listOf("mcp__docs__search"), conflict.candidates)
    }

    @Test
    fun noConflictOnDistinctToolNames() = runTest {
        val client = FakeClient().apply { tools = { listOf(tool("a"), tool("b")) } }
        val d = discovery(client, listOf(server("s")))
        d.refresh()
        assertTrue(d.current().conflicts.isEmpty())
    }

    // ── 并发与取消 ─────────────────────────────────────────────────────────

    @Test
    fun concurrentServersAllRefreshedAndRegistered() = runTest {
        val registry = DefaultToolRegistry()
        val client = FakeClient().apply {
            tools = { n -> listOf(tool("t_$n")) }
        }
        val d = discovery(client, listOf(server("a"), server("b"), server("c")), registry)

        val result = d.refresh()

        assertEquals(setOf("a", "b", "c"), result.refreshedServers.toSet())
        assertEquals(setOf("a", "b", "c"), client.discovered.toSet())
        assertEquals(McpDiscoveryState.Available, d.current().servers.getValue("b").state)
        assertNotNull(registry.find("mcp__a__t_a"))
        assertNotNull(registry.find("mcp__b__t_b"))
        assertNotNull(registry.find("mcp__c__t_c"))
    }

    @Test
    fun mixedSuccessAndFailureReportedSeparately() = runTest {
        val client = FakeClient().apply {
            tools = { n -> listOf(tool("t")) }
            error = { n -> if (n == "bad") McpProtocolException("nope") else null }
        }
        val d = discovery(client, listOf(server("good"), server("bad")))

        val result = d.refresh()

        assertEquals(listOf("good"), result.refreshedServers)
        assertEquals(listOf("bad"), result.failedServers)
        assertEquals(McpDiscoveryState.Available, d.current().servers.getValue("good").state)
        assertEquals(McpDiscoveryState.Failed, d.current().servers.getValue("bad").state)
    }

    @Test
    fun cancellationPropagates() = runTest {
        val client = FakeClient().apply { error = { CancellationException("cancelled") } }
        val d = discovery(client, listOf(server("a")))
        try {
            d.refresh()
            throw AssertionError("should have rethrown cancellation")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    @Test
    fun refreshingStateIsObservableDuringRefresh() = runTest {
        val gate = CompletableDeferred<Unit>()
        val client = FakeClient().apply {
            tools = { listOf(tool("t")) }
            this.gate = gate
        }
        val d = discovery(client, listOf(server("a")))
        val job = launch { d.refresh() }
        runCurrent()

        assertEquals(McpDiscoveryState.Discovering, d.current().servers.getValue("a").state)

        gate.complete(Unit)
        job.join()
        assertEquals(McpDiscoveryState.Available, d.current().servers.getValue("a").state)
    }

    // ── 快照一致性 ─────────────────────────────────────────────────────────

    @Test
    fun removedServerDisappearsFromSnapshot() {
        val client = FakeClient()
        val servers = mutableListOf(server("a"), server("b"))
        val d = McpDiscovery(client, servers = { servers }, registry = { DefaultToolRegistry() })
        assertEquals(setOf("a", "b"), d.current().servers.keys)

        servers.removeAt(1)
        assertEquals(setOf("a"), d.current().servers.keys) // config 删除的服务器不出现
    }

    @Test
    fun removedServerToolsAreCleanedFromRegistry() = runTest {
        // 评审发现（P2）：服务器从配置删除后，其已注册工具不应残留——否则模型仍
        // 被暴露旧工具，执行时却找不到服务器（McpExecutor 返回 Failure）。
        // 当前 refresh 只处理 enabled 服务器，清理只针对「本次仍被刷新」的服务器
        // （registerAll 按 registeredNames 差异移除），被删服务器的旧注册永久残留。
        val registry = DefaultToolRegistry()
        val client = FakeClient().apply { tools = { listOf(tool("t")) } }
        val servers = mutableListOf(server("a"))
        val d = McpDiscovery(client, servers = { servers }, registry = { registry })

        d.refresh()
        assertNotNull(registry.find("mcp__a__t"))

        servers.removeAt(0) // 配置删除服务器 a
        d.refresh()

        assertNull("被删服务器的旧工具应从 registry 清理", registry.find("mcp__a__t"))
    }

    @Test
    fun emptyServedListReturnsEmptyRefreshResult() = runTest {
        val client = FakeClient()
        val d = discovery(client, emptyList())
        val result = d.refresh()
        assertTrue(result.refreshedServers.isEmpty())
        assertTrue(result.failedServers.isEmpty())
        assertTrue(client.discovered.isEmpty())
    }
}