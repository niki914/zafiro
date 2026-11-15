package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 保护：默认注册表只露出一套屏幕工具，旧的无障碍/启动名字不再进模型的工具列表。
class BuiltinToolTest {
    @Test
    fun defaultRegistry_offersOneScreenSurface() {
        assertEquals(
            listOf(
                "agent",
                "clipboard",
                "compose_message",
                "device_info",
                "execute_python",
                "find_installed_apps",
                "http_request",
                "launch_app",
                "load_skill",
                "lock_screen",
                "memory",
                "notify",
                "open",
                "open_uri",
                "py_meta_tools",
                "screen_operation",
                "screens",
                "screenshot",
                "shell",
                "ssh",
                "take_over",
                "view_image",
                "wait",
            ),
            BuiltinToolRegistry.default().all().map { it.name }.sorted(),
        )
    }

    @Test
    fun screenTools_areDefaultEnabled() {
        val registry = BuiltinToolRegistry.default()
        listOf("screen_operation", "screenshot", "open", "screens").forEach { name ->
            assertEquals(true, registry.find(name)?.defaultEnabled)
        }
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
