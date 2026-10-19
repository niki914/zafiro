// 保护：清洗 Tool JSON Schema 中的非法正则/NUL字符，并为缺少或为 null 的 required 补齐空数组，防止模型端或中继校验失败
package com.niki914.okia.tooling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaSanitizerTest {

    @Test
    fun stripsPatternWithNulCharacterEscape() {
        val raw = """
            {
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "pattern": "^[^\\0]*${'$'}",
                        "description": "Local filesystem path"
                    },
                    "safe": {
                        "type": "string",
                        "pattern": "^[a-zA-Z0-9_-]+${'$'}"
                    }
                },
                "required": ["path"]
            }
        """.trimIndent()

        val json = Json.parseToJsonElement(raw)
        val sanitized = ToolSchemaSanitizer.sanitize(json).jsonObject

        val properties = sanitized["properties"]!!.jsonObject
        val pathProp = properties["path"]!!.jsonObject
        val safeProp = properties["safe"]!!.jsonObject

        // 包含 \0 的 pattern 必须被剔除，避免 OpenAI 报错 is not a regex
        assertFalse(pathProp.containsKey("pattern"))

        // 合法的普通 pattern 必须保留
        assertTrue(safeProp.containsKey("pattern"))
        assertEquals("^[a-zA-Z0-9_-]+$", safeProp["pattern"]!!.jsonPrimitive.content)
    }

    @Test
    fun stripsInvalidRegexPattern() {
        val raw = """
            {
                "type": "object",
                "properties": {
                    "broken": {
                        "type": "string",
                        "pattern": "([a-z"
                    }
                }
            }
        """.trimIndent()

        val sanitized = ToolSchemaSanitizer.sanitize(Json.parseToJsonElement(raw)).jsonObject
        val brokenProp = sanitized["properties"]!!.jsonObject["broken"]!!.jsonObject
        assertFalse(brokenProp.containsKey("pattern"))
    }

    @Test
    fun addsEmptyRequiredToObjectSchemaMissingRequired() {
        val raw = """{"type": "object", "properties": {"query": {"type": "string"}}}"""
        val sanitized = ToolSchemaSanitizer.sanitize(Json.parseToJsonElement(raw)).jsonObject

        assertTrue(sanitized.containsKey("required"))
        assertEquals(0, sanitized["required"]!!.jsonArray.size)
    }

    @Test
    fun recursivelyAddsEmptyRequiredToNestedObjectSchemas() {
        val raw = """
            {
                "type": "object",
                "properties": {
                    "action": {"type": "string"},
                    "args": {"type": "object", "description": "Dynamic kwargs"}
                },
                "required": ["action"]
            }
        """.trimIndent()

        val sanitized = ToolSchemaSanitizer.sanitize(Json.parseToJsonElement(raw)).jsonObject
        assertEquals(listOf("action"), sanitized["required"]!!.jsonArray.map { it.jsonPrimitive.content })

        val argsProp = sanitized["properties"]!!.jsonObject["args"]!!.jsonObject
        assertTrue(argsProp.containsKey("required"))
        assertEquals(0, argsProp["required"]!!.jsonArray.size)
    }

    @Test
    fun replacesNullRequiredWithEmptyArray() {
        val raw = """{"type": "object", "properties": {}, "required": null}"""
        val sanitized = ToolSchemaSanitizer.sanitize(Json.parseToJsonElement(raw)).jsonObject

        assertTrue(sanitized.containsKey("required"))
        assertEquals(0, sanitized["required"]!!.jsonArray.size)
    }
}
