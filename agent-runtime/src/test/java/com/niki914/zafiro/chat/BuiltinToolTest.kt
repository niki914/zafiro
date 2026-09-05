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
