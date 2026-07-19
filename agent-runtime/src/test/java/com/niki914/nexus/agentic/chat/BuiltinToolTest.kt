package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinTool
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.nexus.agentic.chat.agentic.buildin.BuiltinToolResult
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.CreateCustomToolBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.LaunchAppBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.MemorizeBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.OpenUriBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.ReadCustomToolBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.SearchAppsBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.SshTerminalBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.TerminalBuiltin
import com.niki914.s3ss10n.LocalToolConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun createCustomToolDescription_matchesConfigureDescription() {
        val tool = CreateCustomToolBuiltin()
        val config = LocalToolConfig()

        tool.configure(config)

        assertEquals("Create or update a custom tool setting.", tool.description)
        assertEquals(tool.description, config.description)
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
                "gesture",
                "key_event",
                "launch_app",
                "load_skill",
                "memorize",
                "node_action",
                "notify",
                "open_uri",
                "read_custom_tool",
                "screen_content",
                "search_apps",
                "ssh_terminal",
                "terminal",
            ),
            registry.all().map { it.name }.sorted()
        )
        assertEquals("create_custom_tool", registry.find("create_custom_tool")?.name)
        assertEquals("gesture", registry.find("gesture")?.name)
        assertEquals("key_event", registry.find("key_event")?.name)
        assertEquals("launch_app", registry.find("launch_app")?.name)
        assertEquals("load_skill", registry.find("load_skill")?.name)
        assertEquals("memorize", registry.find("memorize")?.name)
        assertEquals("node_action", registry.find("node_action")?.name)
        assertEquals("notify", registry.find("notify")?.name)
        assertEquals("open_uri", registry.find("open_uri")?.name)
        assertEquals("read_custom_tool", registry.find("read_custom_tool")?.name)
        assertEquals("screen_content", registry.find("screen_content")?.name)
        assertEquals("search_apps", registry.find("search_apps")?.name)
        assertEquals("ssh_terminal", registry.find("ssh_terminal")?.name)
        assertEquals("terminal", registry.find("terminal")?.name)
        assertNull(registry.find("run_command"))
    }

    @Test
    fun memorizeAndReadCustomToolDescription_matchesConfigureDescription() {
        listOf(
            LaunchAppBuiltin(),
            MemorizeBuiltin(),
            OpenUriBuiltin(),
            ReadCustomToolBuiltin(),
            SearchAppsBuiltin(),
        ).forEach { tool ->
            val config = LocalToolConfig()

            tool.configure(config)

            assertEquals(tool.description, config.description)
        }
    }

    @Test
    fun terminalBuiltinDescription_matchesConfigureDescription() {
        val tool = TerminalBuiltin()
        val config = LocalToolConfig()

        tool.configure(config)

        assertEquals(tool.description, config.description)
        assertEquals("terminal", tool.name)
        assertTrue(tool.defaultEnabled)
        assertTrue(tool.description.contains("open_and_exec"))
        assertTrue(tool.description.contains("one-shot"))
        assertTrue(tool.description.contains("opaque session handle returned"))
        assertTrue(tool.description.contains("read_async_result"))
        assertTrue(tool.description.contains("SESSION_NOT_FOUND"))
        assertTrue(tool.description.contains("SESSION_BUSY"))
    }

    @Test
    fun sshTerminalBuiltinDescription_matchesConfigureDescription() {
        val tool = SshTerminalBuiltin()
        val config = LocalToolConfig()

        tool.configure(config)

        assertEquals(tool.description, config.description)
        assertEquals("ssh_terminal", tool.name)
        assertTrue(tool.defaultEnabled)
        assertTrue(tool.description.contains("send_line"))
        assertTrue(tool.description.contains("exec/open_and_exec"))
        assertTrue(tool.description.contains("password"))
        assertTrue(tool.description.contains("should not be echoed"))
        assertTrue(tool.description.contains("should not be stored"))
    }

    private class FakeBuiltinTool(
        override val name: String,
        override val defaultEnabled: Boolean = false,
    ) : BuiltinTool() {
        override fun configure(config: LocalToolConfig) = Unit

        override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
            return BuiltinToolResult.success(message = "ok")
        }
    }
}
