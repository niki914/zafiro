package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.ToolManager
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.util.SilentLoggerRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting as BuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool as CustomPyTool
import com.niki914.zafiro.settings.model.RuntimeMcpServer as McpServer

class ToolManagerTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @Test
    fun resolveFromTypedConfig_buildsBuiltinCustomAndMcpDefinitions() {
        val resolved = ToolManager(
            builtinToolRegistry = BuiltinToolRegistry(
                listOf(FakeBuiltinTool(name = "time", description = "Read current time."))
            )
        ).resolve(
            customPyTools = listOf(
                CustomPyTool(
                    name = "py_current_time",
                    description = "Get current timestamp",
                    code = "def main():\n    import time\n    print(int(time.time()))",
                    schemaJson = "{\"type\":\"object\"}",
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
        )

        assertEquals(listOf("time"), resolved.builtinTools.map { it.name })
        assertTrue(resolved.builtinTools.single() is LocalTool.Builtin)
        assertEquals("Read current time.", resolved.builtinTools.single().description)

        val customPyTool = resolved.customPyTools.filterIsInstance<LocalTool.Py>().single()
        assertEquals("py_current_time", customPyTool.name)
        assertEquals("Get current timestamp", customPyTool.description)
        assertEquals("{\"type\":\"object\"}", customPyTool.inputSchemaJson)
        assertEquals(listOf("aslocate"), resolved.mcpServers.map { it.name })
        val mcpServer = resolved.mcpServers.single() as McpServerDefinition.Http
        assertEquals("http://127.0.0.1:51338/mcp", mcpServer.url)
        assertEquals(listOf("time", "py_current_time"), resolved.allLocalToolNames())
    }

    @Test
    fun resolveFromTypedConfig_preservesMcpHeaders() {
        val resolved = ToolManager(
            builtinToolRegistry = BuiltinToolRegistry(
                listOf(FakeBuiltinTool(name = "time", description = "Read current time."))
            )
        ).resolve(
            customPyTools = listOf(
                CustomPyTool(
                    name = "py_current_time",
                    description = "Get current timestamp",
                    code = "def main():\n    import time\n    print(int(time.time()))",
                    schemaJson = "{\"type\":\"object\"}",
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
        )

        assertEquals(listOf("time"), resolved.builtinTools.map { it.name })
        assertEquals(listOf("py_current_time"), resolved.customPyTools.map { it.name })
        val mcpServer = resolved.mcpServers.single() as McpServerDefinition.Http
        assertEquals(mapOf("Authorization" to "Bearer token"), mcpServer.headers)
        assertTrue(resolved.allLocalTools().all { it.name in setOf("time", "py_current_time") })
    }

    private class FakeBuiltinTool(
        override val name: String,
        override val description: String = "Builtin tool: $name",
    ) : BuiltinTool() {

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
            return BuiltinToolResult.success(message = "ok")
        }
    }
}
