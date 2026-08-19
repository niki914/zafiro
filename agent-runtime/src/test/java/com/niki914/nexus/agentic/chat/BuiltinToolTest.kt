package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.CreateCustomToolBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.LaunchAppBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.MemoryBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.OpenUriBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.ReadCustomToolBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.SearchAppsBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.TerminalBuiltin
import com.niki914.okia.tooling.ToolWireName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinToolTest {
    @Test
    fun defaultDescription_usesToolName() {
        val tool = FakeBuiltinTool("known")

        assertEquals("Builtin tool: known", tool.description)
    }

    @Test
    fun createCustomToolDescription_matchesSchemaDescription() {
        val tool = CreateCustomToolBuiltin()

        assertEquals("Create or update a custom tool setting.", tool.description)
        assertSchemaParsable(tool.inputSchemaJson, tool.name)
    }

    @Test
    fun failureResult_serializesRequiredFields() {
        val result = BuiltinToolResult.failure(
            code = "INVALID_NAME",
            message = "Invalid tool name.",
            hint = "Use letters, digits, or underscores.",
            fieldErrors = mapOf("name" to "Invalid format."),
        )

        val json = Json.parseToJsonElement(result.toJsonString()).jsonObject

        assertFalse(json["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("INVALID_NAME", json["code"]!!.jsonPrimitive.content)
        assertEquals("Invalid tool name.", json["message"]!!.jsonPrimitive.content)
        assertEquals("Use letters, digits, or underscores.", json["hint"]!!.jsonPrimitive.content)
        assertEquals(
            "Invalid format.",
            json["field_errors"]!!.jsonObject["name"]!!.jsonPrimitive.content
        )
        assertTrue(json["data"]!!.jsonObject.isEmpty())
    }

    @Test
    fun defaultRegistry_containsExpectedTools() {
        val registry = BuiltinToolRegistry.default()

        assertEquals(
            listOf(
                "create_custom_tool",
                "execute_python",
                "launch_app",
                "load_skill",
                "memory",
                "notify",
                "open_uri",
                "read_custom_tool",
                "screen_operation_accessibility",
                "screen_operation_shell",
                "search_apps",
                "terminal",
            ),
            registry.all().map { it.name }.sorted()
        )
        assertEquals("create_custom_tool", registry.find("create_custom_tool")?.name)
        assertEquals("launch_app", registry.find("launch_app")?.name)
        assertEquals("load_skill", registry.find("load_skill")?.name)
        assertEquals("memory", registry.find("memory")?.name)
        assertEquals("notify", registry.find("notify")?.name)
        assertEquals("open_uri", registry.find("open_uri")?.name)
        assertEquals("read_custom_tool", registry.find("read_custom_tool")?.name)
        assertEquals("screen_operation_accessibility", registry.find("screen_operation_accessibility")?.name)
        assertEquals("screen_operation_shell", registry.find("screen_operation_shell")?.name)
        assertEquals("search_apps", registry.find("search_apps")?.name)
        assertEquals("terminal", registry.find("terminal")?.name)
        assertNull(registry.find("ssh_terminal"))
        assertNull(registry.find("run_command"))
    }

    @Test
    fun toolSchemas_areValidJsonSchema() {
        listOf(
            CreateCustomToolBuiltin(),
            LaunchAppBuiltin(),
            MemoryBuiltin(),
            OpenUriBuiltin(),
            ReadCustomToolBuiltin(),
            SearchAppsBuiltin(),
            TerminalBuiltin(),
        ).forEach { tool ->
            assertEquals(tool.name, tool.name.trim())
            assertTrue("description not blank: ${tool.name}", tool.description.isNotBlank())
            assertSchemaParsable(tool.inputSchemaJson, tool.name)
        }
    }

    /**
     * D25 描述合法性：schema JSON 可解析（至少是合法 JSON；T2b 前所有内置工具
     * 都有 schema）、name 合法、wireName 满足 ToolWireName 约束（长度/字符）。
     */
    @Test
    fun allRegisteredBuiltinTools_meetDescriptionLegality() {
        BuiltinToolRegistry.default().all().forEach { tool ->
            assertEquals("name not blank: ${tool.name}", tool.name, tool.name.trim())
            assertTrue("description not blank: ${tool.name}", tool.description.isNotBlank())
            assertSchemaParsable(tool.inputSchemaJson, tool.name)
            val wireName = ToolWireName.forLocal(tool.name)
            assertTrue(
                "wireName within length for ${tool.name}",
                wireName.length <= ToolWireName.MAX_LENGTH,
            )
            assertEquals(
                "wireName sanitizes to itself for ${tool.name}",
                wireName,
                ToolWireName.forLocal(wireName),
            )
        }
    }

    private class FakeBuiltinTool(
        override val name: String,
        override val defaultEnabled: Boolean = false,
    ) : BuiltinTool() {

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
            return BuiltinToolResult.success(message = "ok")
        }
    }

    private fun assertSchemaParsable(schemaJson: String?, toolName: String) {
        assertNotNull("schema missing for $toolName", schemaJson)
        try {
            Json.parseToJsonElement(schemaJson!!).jsonObject
        } catch (e: SerializationException) {
            throw AssertionError("schema of $toolName is not JSON: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw AssertionError("schema of $toolName is not JSON: ${e.message}", e)
        }
    }
}
