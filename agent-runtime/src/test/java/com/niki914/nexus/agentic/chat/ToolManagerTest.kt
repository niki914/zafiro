package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.ToolManager
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.s3ss10n.LocalToolConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeBuiltinToolSetting as BuiltinToolSetting
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer as McpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpTool as McpTool

class ToolManagerTest {

    @Test
    fun resolveFromTypedConfig_buildsBuiltinCustomAndMcpDefinitions() {
        val resolved = ToolManager(
            builtinToolRegistry = BuiltinToolRegistry(
                listOf(FakeBuiltinTool(name = "time", description = "Read current time."))
            )
        ).resolve(
            customTools = listOf(
                CustomTool(
                    name = "current_time",
                    description = "Get current timestamp",
                    command = "date +%s",
                    enabled = true,
                )
            ),
            mcpServers = listOf(
                McpServer(name = "aslocate", url = "http://127.0.0.1:51338/mcp")
            ),
            builtinSettings = listOf(
                BuiltinToolSetting(
                    name = "time",
                    description = "Read current time.",
                    enabled = true
                )
            ),
            mcpCachedTools = mapOf(
                "aslocate" to listOf(
                    McpTool(
                        name = "lookupSymbol",
                        description = "Lookup symbol definition",
                        inputSchemaJson = """{"type":"object"}""",
                    )
                )
            ),
        )

        assertEquals(listOf("time"), resolved.builtinTools.map { it.name })
        assertTrue(resolved.builtinTools.single() is LocalTool.Builtin)
        assertEquals("Read current time.", resolved.builtinTools.single().description)

        val customTool = resolved.customTools.filterIsInstance<LocalTool.Custom>().single()
        assertEquals("current_time", customTool.name)
        assertTrue(customTool.description.contains("Runs in an unprivileged Android shell"))
        assertTrue(customTool.description.contains("terminal builtin tool"))
        assertTrue(customTool.description.contains("cd /path && cmd"))
        assertEquals("date +%s", customTool.command)
        assertEquals(listOf("aslocate"), resolved.mcpServers.map { it.name })
        val mcpServer = resolved.mcpServers.single()
        val cachedTool = (mcpServer as McpServerDefinition.Http)
            .cachedTools
            .single()
        assertEquals("lookupSymbol", cachedTool.name)
        assertEquals("Lookup symbol definition", cachedTool.description)
        assertEquals("""{"type":"object"}""", cachedTool.inputSchema.toString())
        assertEquals(listOf("time", "current_time"), resolved.allLocalToolNames())
    }

    @Test
    fun resolveFromTypedConfig_preservesMcpHeadersAndCache() {
        val resolved = ToolManager(
            builtinToolRegistry = BuiltinToolRegistry(
                listOf(FakeBuiltinTool(name = "time", description = "Read current time."))
            )
        ).resolve(
            customTools = listOf(
                CustomTool(
                    name = "current_time",
                    description = "Get current timestamp",
                    command = "date +%s",
                    enabled = true,
                )
            ),
            mcpServers = listOf(
                McpServer(
                    name = "aslocate",
                    url = "http://127.0.0.1:51338/mcp",
                    enabled = true,
                    headers = mapOf("Authorization" to "Bearer token"),
                )
            ),
            builtinSettings = listOf(
                BuiltinToolSetting(
                    name = "time",
                    description = "Read current time.",
                    enabled = true,
                )
            ),
            mcpCachedTools = mapOf(
                "aslocate" to listOf(
                    McpTool(
                        name = "lookupSymbol",
                        description = "Lookup symbol definition",
                        inputSchemaJson = """{"type":"object"}""",
                    )
                )
            ),
        )

        assertEquals(listOf("time"), resolved.builtinTools.map { it.name })
        assertEquals(listOf("current_time"), resolved.customTools.map { it.name })
        val mcpServer = resolved.mcpServers.single() as McpServerDefinition.Http
        assertEquals(mapOf("Authorization" to "Bearer token"), mcpServer.headers)
        assertEquals("lookupSymbol", mcpServer.cachedTools.single().name)
        assertEquals("""{"type":"object"}""", mcpServer.cachedTools.single().inputSchema.toString())
        assertTrue(resolved.allLocalTools().all { it.name in setOf("time", "current_time") })
    }

    private class FakeBuiltinTool(
        override val name: String,
        override val description: String = "Builtin tool: $name",
    ) : BuiltinTool() {
        override fun configure(config: LocalToolConfig) = Unit

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
            return BuiltinToolResult.success(message = "ok")
        }
    }
}
