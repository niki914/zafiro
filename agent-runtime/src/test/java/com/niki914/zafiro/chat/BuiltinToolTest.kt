package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinToolTest {
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
                "execute_python",
                "find_installed_apps",
                "launch_app",
                "load_skill",
                "memory",
                "notify",
                "open_uri",
                "py_meta_tools",
                "screen_operation_accessibility",
                "screen_operation_shell",
                "terminal",
                "view_image",
            ),
            registry.all().map { it.name }.sorted()
        )
        assertEquals("launch_app", registry.find("launch_app")?.name)
        assertEquals("load_skill", registry.find("load_skill")?.name)
        assertEquals("memory", registry.find("memory")?.name)
        assertEquals("notify", registry.find("notify")?.name)
        assertEquals("open_uri", registry.find("open_uri")?.name)
        assertEquals("py_meta_tools", registry.find("py_meta_tools")?.name)
        assertEquals(
            "screen_operation_accessibility",
            registry.find("screen_operation_accessibility")?.name
        )
        assertEquals("screen_operation_shell", registry.find("screen_operation_shell")?.name)
        assertEquals("find_installed_apps", registry.find("find_installed_apps")?.name)
        assertEquals("terminal", registry.find("terminal")?.name)
    }

    fun toolSchemas_areValidJsonSchema() {
        BuiltinToolRegistry.default().all().forEach { tool ->
            assertTrue("description not blank: ${tool.name}", tool.description.isNotBlank())
            assertSchemaParsable(tool.inputSchemaJson, tool.name)
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
