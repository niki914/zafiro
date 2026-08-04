package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.json.GsonJsonCodec
import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpRefreshTest {
    @Test
    fun `HttpMcpClient tools list 支持普通 JSON response`() = runBlocking {
        val expectedTool = remoteSearchTool()
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf("https://mcp.text" to listOf(expectedTool))
        )
        val client = HttpMcpClient(engine = engine, codec = GsonJsonCodec())

        val tools = client.listTools(mcpServer("https://mcp.text"))

        assertEquals(listOf(expectedTool), tools)
        assertTrue(engine.frameCalls.any { it == "https://mcp.text" to "tools/list" })
    }

    @Test
    fun `HttpMcpClient tools list 支持 SSE data JSON response`() = runBlocking {
        val expectedTool = remoteSearchTool()
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf("https://mcp.sse" to listOf(expectedTool)),
            sseMethodsByUrl = mapOf("https://mcp.sse" to setOf("tools/list"))
        )
        val client = HttpMcpClient(engine = engine, codec = GsonJsonCodec())

        val tools = client.listTools(mcpServer("https://mcp.sse"))

        assertEquals(listOf(expectedTool), tools)
        assertTrue(engine.frameCalls.any { it == "https://mcp.sse" to "tools/list" })
    }

    @Test
    fun `HttpMcpClient tools call 支持 SSE data JSON response`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf("https://mcp.call-sse" to listOf(remoteSearchTool())),
            toolCallResultsByUrl = mapOf("https://mcp.call-sse" to """{"answer":"remote-ok"}"""),
            sseMethodsByUrl = mapOf("https://mcp.call-sse" to setOf("tools/call"))
        )
        val client = HttpMcpClient(engine = engine, codec = GsonJsonCodec())

        val result = client.call(
            server = mcpServer("https://mcp.call-sse"),
            toolName = "remote_search",
            argumentsJson = """{"query":"hi"}"""
        )

        assertEquals("""{"answer":"remote-ok"}""", result)
        assertTrue(engine.frameCalls.any { it == "https://mcp.call-sse" to "tools/call" })
    }

    @Test
    fun `refreshMcpTools 成功后 下一轮可以看到发现的工具`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf(
                "https://mcp.ok" to listOf(
                    McpDiscoveredTool(
                        name = "remote_search",
                        description = "search docs",
                        inputSchema = mapOf("type" to "object")
                    )
                )
            )
        )
        val protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) }
        val session = newChatSession(protocol = protocol, engine = engine) {
            mcp {
                add("docs") {
                    http { url = "https://mcp.ok" }
                }
            }
        }

        try {
            val result = session.refreshMcpTools()

            assertTrue(result.isSuccess)
            assertFalse(result.isPartialSuccess)
            assertEquals(listOf("docs"), result.refreshedServers)
            assertEquals(emptyList<McpServerRefreshFailure>(), result.failedServers)
            assertEquals(1, result.discoveredToolCount)

            session.send("hello") { }

            val tool = protocol.lastSnapshot?.tools?.find("remote_search")
            assertNotNull(tool)
            assertEquals(ToolCallKind.Mcp("docs"), tool?.kind)
        } finally {
            session.close()
        }
    }

    @Test
    fun `refreshMcpTools 在部分 server 失败时返回 partial success`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf(
                "https://mcp.ok" to listOf(
                    McpDiscoveredTool(
                        name = "remote_search",
                        description = "search docs",
                        inputSchema = mapOf("type" to "object")
                    )
                )
            ),
            failuresByUrl = mapOf(
                "https://mcp.bad" to IllegalStateException("boom")
            )
        )
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("ok") {
                    http { url = "https://mcp.ok" }
                }
                add("bad") {
                    http { url = "https://mcp.bad" }
                }
            }
        }

        try {
            val result = session.refreshMcpTools()

            assertFalse(result.isSuccess)
            assertTrue(result.isPartialSuccess)
            assertEquals(listOf("ok"), result.refreshedServers)
            assertEquals(1, result.discoveredToolCount)
            assertEquals(1, result.failedServers.size)
            assertEquals("bad", result.failedServers.single().serverName)
            assertEquals("boom", result.failedServers.single().message)
        } finally {
            session.close()
        }
    }

    @Test
    fun `refreshMcpTools 成功后 discovery snapshot 为 available`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf(
                "https://mcp.ok" to listOf(
                    McpDiscoveredTool(
                        name = "remote_search",
                        description = "search docs",
                        inputSchema = mapOf("type" to "object")
                    )
                )
            )
        )
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("docs") {
                    http { url = "https://mcp.ok" }
                }
            }
        }

        try {
            val result = session.refreshMcpTools()
            val server = session.getMcpDiscoverySnapshot().servers.getValue("docs")

            assertTrue(result.isSuccess)
            assertEquals(McpDiscoveryState.Available, server.state)
            assertEquals(1, server.discoveredToolCount)
            assertNotNull(server.lastSuccessAtMillis)
            assertEquals(null, server.errorMessage)
            assertFalse(server.stale)
        } finally {
            session.close()
        }
    }

    @Test
    fun `refreshMcpTools 失败时沿用 stale cache 中的已发现工具`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf(
                "https://mcp.ok" to listOf(
                    McpDiscoveredTool(
                        name = "remote_search",
                        description = "search docs",
                        inputSchema = mapOf("type" to "object")
                    )
                )
            )
        )
        val protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) }
        val session = newChatSession(protocol = protocol, engine = engine) {
            mcp {
                add("docs") {
                    http { url = "https://mcp.ok" }
                }
            }
        }

        try {
            val firstResult = session.refreshMcpTools()
            val firstServer = session.getMcpDiscoverySnapshot().servers.getValue("docs")

            assertTrue(firstResult.isSuccess)
            assertEquals(McpDiscoveryState.Available, firstServer.state)
            assertEquals(1, firstServer.discoveredToolCount)

            engine.failuresByUrl["https://mcp.ok"] = IllegalStateException("boom")

            val secondResult = session.refreshMcpTools()
            val staleServer = session.getMcpDiscoverySnapshot().servers.getValue("docs")

            assertFalse(secondResult.isSuccess)
            assertEquals("docs", secondResult.failedServers.single().serverName)
            assertEquals(McpDiscoveryState.UsingStaleCache, staleServer.state)
            assertTrue(staleServer.stale)
            assertEquals(1, staleServer.discoveredToolCount)

            session.send("hello") { }

            val tool = protocol.lastSnapshot?.tools?.find("remote_search")
            assertNotNull(tool)
            assertEquals(ToolCallKind.Mcp("docs"), tool?.kind)
        } finally {
            session.close()
        }
    }

    @Test
    fun `refreshMcpTools 失败后 discovery snapshot 记录 error`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = emptyMap(),
            failuresByUrl = mapOf(
                "https://mcp.bad" to IllegalStateException("boom")
            )
        )
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("bad") {
                    http { url = "https://mcp.bad" }
                }
            }
        }

        try {
            val result = session.refreshMcpTools()
            val server = session.getMcpDiscoverySnapshot().servers.getValue("bad")

            assertFalse(result.isSuccess)
            assertFalse(result.isPartialSuccess)
            assertEquals(McpDiscoveryState.Failed, server.state)
            assertEquals("boom", server.errorMessage)
            assertEquals(0, server.discoveredToolCount)
            assertFalse(server.stale)
        } finally {
            session.close()
        }
    }

    @Test
    fun `mcpHooks onToolsDiscovered 收到 server 和 tools`() = runBlocking {
        val tools = listOf(
            McpDiscoveredTool(
                name = "remote_search",
                description = "search docs",
                inputSchema = mapOf("type" to "object")
            )
        )
        var discoveredServer: String? = null
        var discoveredTools: List<McpDiscoveredTool> = emptyList()
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf("https://mcp.ok" to tools)
        )
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("docs") {
                    http { url = "https://mcp.ok" }
                }
            }
            mcpHooks {
                onToolsDiscovered = { server, receivedTools ->
                    discoveredServer = server
                    discoveredTools = receivedTools
                }
            }
        }

        try {
            val result = session.refreshMcpTools()

            assertTrue(result.isSuccess)
            assertEquals("docs", discoveredServer)
            assertEquals(tools, discoveredTools)
        } finally {
            session.close()
        }
    }

    @Test
    fun `mcpHooks 抛异常不影响 refresh result`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf(
                "https://mcp.ok" to listOf(
                    McpDiscoveredTool(
                        name = "remote_search",
                        description = "search docs",
                        inputSchema = mapOf("type" to "object")
                    )
                )
            )
        )
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("docs") {
                    http { url = "https://mcp.ok" }
                }
            }
            mcpHooks {
                onToolsDiscovered = { _, _ -> error("hook boom") }
            }
        }

        try {
            val result = session.refreshMcpTools()

            assertTrue(result.isSuccess)
            assertFalse(result.isPartialSuccess)
            assertEquals(listOf("docs"), result.refreshedServers)
            assertEquals(1, result.discoveredToolCount)
        } finally {
            session.close()
        }
    }

    @Test
    fun `config fingerprint 变化后旧 discovery 结果被 ignored`() = runBlocking {
        val oldToolsListStarted = CompletableDeferred<Unit>()
        val releaseOldToolsList = CompletableDeferred<Unit>()
        val failures = mutableListOf<Triple<String, String, McpCachePolicy>>()
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf(
                "https://mcp.old" to listOf(
                    McpDiscoveredTool(
                        name = "old_search",
                        description = "old search",
                        inputSchema = mapOf("type" to "object")
                    )
                ),
                "https://mcp.new" to listOf(
                    McpDiscoveredTool(
                        name = "new_search",
                        description = "new search",
                        inputSchema = mapOf("type" to "object")
                    )
                )
            )
        )
        engine.beforeToolsListResponse = { url ->
            if (url == "https://mcp.old") {
                oldToolsListStarted.complete(Unit)
                releaseOldToolsList.await()
            }
        }
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("docs") {
                    http { url = "https://mcp.old" }
                }
            }
            mcpHooks {
                onDiscoveryFailed = { server, error, policy ->
                    failures += Triple(server, error.message ?: "", policy)
                }
            }
        }

        try {
            val oldRefresh = async { session.refreshMcpTools() }
            oldToolsListStarted.await()

            session.update {
                mcp {
                    replace("docs") {
                        http { url = "https://mcp.new" }
                    }
                }
            }
            releaseOldToolsList.complete(Unit)

            val oldResult = oldRefresh.await()
            val newResult = session.refreshMcpTools()
            val snapshot = session.getMcpDiscoverySnapshot()

            assertFalse(oldResult.isSuccess)
            assertEquals("docs", oldResult.failedServers.single().serverName)
            assertEquals(
                "MCP discovery ignored because config changed",
                oldResult.failedServers.single().message
            )
            assertTrue(
                failures.any {
                    it.first == "docs" &&
                        it.second == "MCP discovery ignored because config changed" &&
                        it.third == McpCachePolicy.IgnoredBecauseConfigChanged
                }
            )
            assertTrue(newResult.isSuccess)
            assertEquals(McpDiscoveryState.Available, snapshot.servers.getValue("docs").state)
            assertTrue(snapshot.finalToolRegistry.tools.any { it.name == "new_search" })
            assertTrue(snapshot.finalToolRegistry.tools.none { it.name == "old_search" })
        } finally {
            session.close()
        }
    }

    @Test
    fun `disabled server 不刷新且不进入 final tool registry`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf(
                "https://mcp.disabled" to listOf(
                    McpDiscoveredTool(
                        name = "disabled_search",
                        description = "disabled search",
                        inputSchema = mapOf("type" to "object")
                    )
                )
            )
        )
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("disabled") {
                    enabled = false
                    http { url = "https://mcp.disabled" }
                }
            }
        }

        try {
            val result = session.refreshMcpTools()
            val snapshot = session.getMcpDiscoverySnapshot()
            val server = snapshot.servers.getValue("disabled")

            assertTrue(result.isSuccess)
            assertEquals(emptyList<String>(), result.refreshedServers)
            assertEquals(0, result.discoveredToolCount)
            assertEquals(
                0,
                engine.frameCalls.count {
                    it.first == "https://mcp.disabled" && it.second == "tools/list"
                }
            )
            assertFalse(server.enabled)
            assertEquals(McpDiscoveryState.Idle, server.state)
            assertTrue(snapshot.finalToolRegistry.tools.none { it.name == "disabled_search" })
        } finally {
            session.close()
        }
    }

    @Test
    fun `并发 refresh 同一 server 复用 in flight discovery`() = runBlocking {
        val toolsListStarted = CompletableDeferred<Unit>()
        val releaseToolsList = CompletableDeferred<Unit>()
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf(
                "https://mcp.ok" to listOf(
                    McpDiscoveredTool(
                        name = "remote_search",
                        description = "search docs",
                        inputSchema = mapOf("type" to "object")
                    )
                )
            )
        )
        engine.beforeToolsListResponse = { url ->
            if (url == "https://mcp.ok") {
                toolsListStarted.complete(Unit)
                releaseToolsList.await()
            }
        }
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("docs") {
                    http { url = "https://mcp.ok" }
                }
            }
        }

        try {
            toolsListStarted.await()
            val first = async(start = CoroutineStart.UNDISPATCHED) { session.refreshMcpTools() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { session.refreshMcpTools() }
            releaseToolsList.complete(Unit)
            val results = listOf(first, second).awaitAll()

            assertTrue(results.all { it.isSuccess })
            assertEquals(
                1,
                engine.frameCalls.count { it.first == "https://mcp.ok" && it.second == "tools/list" }
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `onDiscoveryFailed 收到 server error 和 cache policy`() = runBlocking {
        val failures = mutableListOf<Triple<String, String, McpCachePolicy>>()
        val engine = FakeMcpHttpEngine(
            toolsByUrl = emptyMap(),
            failuresByUrl = mapOf(
                "https://mcp.bad" to IllegalStateException("boom")
            )
        )
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("bad") {
                    http { url = "https://mcp.bad" }
                }
            }
            mcpHooks {
                onDiscoveryFailed = { server, error, policy ->
                    failures += Triple(server, error.message ?: "", policy)
                }
            }
        }

        try {
            val result = session.refreshMcpTools()

            assertFalse(result.isSuccess)
            assertTrue(
                failures.any {
                    it.first == "bad" &&
                        it.second == "boom" &&
                        it.third == McpCachePolicy.NoUsableCache
                }
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `onDiscoveryStateChanged 顺序为 discovering 到 available`() = runBlocking {
        val states = mutableListOf<McpDiscoveryState>()
        val engine = FakeMcpHttpEngine(
            toolsByUrl = mapOf(
                "https://mcp.ok" to listOf(
                    McpDiscoveredTool(
                        name = "remote_search",
                        description = "search docs",
                        inputSchema = mapOf("type" to "object")
                    )
                )
            )
        )
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("docs") {
                    http { url = "https://mcp.ok" }
                }
            }
            mcpHooks {
                onDiscoveryStateChanged = { server, snapshot ->
                    if (server == "docs") states += snapshot.state
                }
            }
        }

        try {
            val result = session.refreshMcpTools()

            assertTrue(result.isSuccess)
            assertTrue(
                states.windowed(2).any {
                    it == listOf(McpDiscoveryState.Discovering, McpDiscoveryState.Available)
                }
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `onDiscoveryStateChanged 顺序为 discovering 到 failed`() = runBlocking {
        val states = mutableListOf<McpDiscoveryState>()
        val engine = FakeMcpHttpEngine(
            toolsByUrl = emptyMap(),
            failuresByUrl = mapOf(
                "https://mcp.bad" to IllegalStateException("boom")
            )
        )
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("bad") {
                    http { url = "https://mcp.bad" }
                }
            }
            mcpHooks {
                onDiscoveryStateChanged = { server, snapshot ->
                    if (server == "bad") states += snapshot.state
                }
            }
        }

        try {
            val result = session.refreshMcpTools()

            assertFalse(result.isSuccess)
            assertTrue(
                states.windowed(2).any {
                    it == listOf(McpDiscoveryState.Discovering, McpDiscoveryState.Failed)
                }
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `discovery failure hook 抛异常不影响 refresh result`() = runBlocking {
        val engine = FakeMcpHttpEngine(
            toolsByUrl = emptyMap(),
            failuresByUrl = mapOf(
                "https://mcp.bad" to IllegalStateException("boom")
            )
        )
        val session = newChatSession(
            protocol = RecordingChatProtocol { flowOf(ProtocolEvent.Completed) },
            engine = engine
        ) {
            mcp {
                add("bad") {
                    http { url = "https://mcp.bad" }
                }
            }
            mcpHooks {
                onDiscoveryFailed = { _, _, _ -> error("hook boom") }
            }
        }

        try {
            val result = session.refreshMcpTools()

            assertFalse(result.isSuccess)
            assertFalse(result.isPartialSuccess)
            assertEquals("bad", result.failedServers.single().serverName)
            assertEquals("boom", result.failedServers.single().message)
        } finally {
            session.close()
        }
    }

    private fun mcpServer(url: String): McpServerConfig {
        return McpServerConfig(transport = McpTransport.Http(url))
    }

    private fun remoteSearchTool(): McpDiscoveredTool {
        return McpDiscoveredTool(
            name = "remote_search",
            description = "search docs",
            inputSchema = mapOf("type" to "object")
        )
    }
}
