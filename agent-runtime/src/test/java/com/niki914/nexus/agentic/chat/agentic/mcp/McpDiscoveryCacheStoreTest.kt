package com.niki914.nexus.agentic.chat.agentic.mcp

import com.niki914.nexus.agentic.chat.FakeRuntimeSettingsGateway
import com.niki914.nexus.agentic.chat.installRuntimeSettingsGatewayForTest
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.kai.McpDiscoveredTool
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer as McpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpTool as McpTool

class McpDiscoveryCacheStoreTest {
    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
    }

    @Test
    fun onToolsDiscovered_persistsHookToolsIntoSettingsGateway() = runTest {
        val server = McpServer(
            name = "aslocate",
            url = "http://127.0.0.1:51338/mcp",
            headers = mapOf("Authorization" to "Bearer token"),
        )
        val gateway = installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(mcpServers = listOf(server))
        )

        McpDiscoveryCacheStore().onToolsDiscovered(
            serverName = "aslocate",
            tools = listOf(
                McpDiscoveredTool(
                    name = "lookupSymbol",
                    description = "Lookup symbol definition",
                    inputSchema = mapOf("type" to "object"),
                )
            ),
        )

        assertEquals(1, gateway.writeCount)
        assertEquals(
            listOf(
                McpTool(
                    name = "lookupSymbol",
                    description = "Lookup symbol definition",
                    inputSchemaJson = """{"type":"object"}""",
                )
            ),
            gateway.listCachedTools(
                server
            ),
        )
    }

    @Test
    fun onToolsDiscovered_ignoresUnknownServerName() = runTest {
        val gateway = installRuntimeSettingsGatewayForTest(
            FakeRuntimeSettingsGateway(
                mcpServers = listOf(
                    McpServer(
                        name = "aslocate",
                        url = "http://127.0.0.1:51338/mcp",
                    )
                )
            )
        )

        McpDiscoveryCacheStore().onToolsDiscovered(
            serverName = "missing",
            tools = listOf(
                McpDiscoveredTool(
                    name = "lookupSymbol",
                    description = "Lookup symbol definition",
                    inputSchema = mapOf("type" to "object"),
                )
            ),
        )

        assertEquals(0, gateway.writeCount)
    }
}
