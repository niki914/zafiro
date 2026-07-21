package com.niki914.nexus.agentic.repo

import com.niki914.nexus.agentic.mod.LocalSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer as McpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpTool as McpTool

class LocalSettingsCodecTest {
    @Test
    fun parseMcpServers_readsUrlHeadersAndEnabled() {
        val settings = localSettings(
            """
            {
              "mcp_servers": [
                {
                  "name": "direct",
                  "url": "https://mcp.example/direct",
                  "enabled": false,
                  "headers": {"Authorization": "Bearer token"}
                },
                {
                  "name": "transport",
                  "transport": {"url": "https://mcp.example/transport"}
                }
              ]
            }
            """.trimIndent()
        )

        val servers = LocalSettingsCodec.parseMcpServers(settings)

        assertEquals(2, servers.size)
        assertEquals(
            McpServer(
                "direct",
                "https://mcp.example/direct",
                false,
                mapOf("Authorization" to "Bearer token")
            ), servers[0]
        )
        assertEquals(McpServer("transport", "https://mcp.example/transport", true), servers[1])
    }

    @Test
    fun withMcpServers_writesNameUrlEnabledHeaders() {
        val updated = LocalSettingsCodec.withMcpServers(
            settings = localSettings("""{"provider":"openai"}"""),
            servers = listOf(
                McpServer(
                    name = "aslocate",
                    url = "http://127.0.0.1:51338/mcp",
                    enabled = true,
                    headers = mapOf("X-Token" to "abc"),
                )
            ),
        )

        val server = updated.props["mcp_servers"]!!.jsonArray.single().jsonObject
        assertEquals("openai", updated.provider)
        assertEquals("aslocate", server["name"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:51338/mcp", server["url"]!!.jsonPrimitive.content)
        assertTrue(server["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("abc", server["headers"]!!.jsonObject["X-Token"]!!.jsonPrimitive.content)
        assertFalse(server.containsKey("transport"))
    }

    @Test
    fun mcpCache_roundTripByUrlAndHeaders() {
        val server = McpServer(
            name = "aslocate",
            url = "http://127.0.0.1:51338/mcp",
            headers = mapOf("Authorization" to "Bearer token"),
        )
        val tool = McpTool(
            name = "lookupSymbol",
            description = "Lookup symbol definition",
            inputSchemaJson = """{"type":"object"}""",
        )

        val settings = LocalSettingsCodec.withMcpCache(
            settings = LocalSettings(),
            url = server.url,
            headers = server.headers,
            tools = listOf(tool),
        )

        assertEquals(listOf(tool), LocalSettingsCodec.parseMcpCache(settings, server))
        assertTrue(
            LocalSettingsCodec.withoutMcpCache(
                settings,
                listOf(server)
            ).mcpDiscoveredToolsCache!!.isEmpty()
        )
    }

    @Test
    fun parseCustomTools_ignoresBlankEntries() {
        val settings = localSettings(
            """
            {
              "custom_tools": [
                {"name":"battery_status","description":"Battery","command":"dumpsys battery","enabled":true},
                {"name":"missing_command","description":"Broken","command":" "},
                {"name":" ","description":"Broken","command":"date"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(CustomTool("battery_status", "Battery", "dumpsys battery", true)),
            LocalSettingsCodec.parseCustomTools(settings),
        )
    }

    @Test
    fun parseExecutionRules_returnsEmptyListWhenMissing() {
        assertEquals(
            emptyList<ExecutionRule>(),
            LocalSettingsCodec.parseExecutionRules(LocalSettings())
        )
    }

    @Test
    fun executionRules_roundTripWritesTopLevelArray() {
        val rule = ExecutionRule(
            id = "rule-1",
            name = "Rule One",
            enabledMode = ExecutionRuleEnabledMode.LOCKED_ONLY,
            patterns = listOf(" rm -rf ", " ", "mkfs"),
        )

        val updated = LocalSettingsCodec.withExecutionRules(
            settings = localSettings("""{"provider":"openai"}"""),
            rules = listOf(rule),
        )

        assertEquals("openai", updated.provider)
        assertEquals(
            listOf(rule.copy(patterns = listOf("rm -rf", "mkfs"))),
            LocalSettingsCodec.parseExecutionRules(updated),
        )
        val storedRule = updated.shellSafetyPolicies!!.single().jsonObject
        assertEquals("rule-1", storedRule["id"]!!.jsonPrimitive.content)
        assertEquals("LOCKED_ONLY", storedRule["enabled_mode"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("rm -rf", "mkfs"),
            storedRule["patterns"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun parseExecutionRules_fallsBackInvalidModeToDisabled() {
        val settings = localSettings(
            """
            {
              "shell_safety_policies": [
                {
                  "id": "rule-1",
                  "name": "Rule One",
                  "enabled_mode": "BROKEN",
                  "patterns": [" rm -rf ", " "]
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                ExecutionRule(
                    id = "rule-1",
                    name = "Rule One",
                    enabledMode = ExecutionRuleEnabledMode.DISABLED,
                    patterns = listOf("rm -rf"),
                )
            ),
            LocalSettingsCodec.parseExecutionRules(settings),
        )
    }

    @Test
    fun withBuiltinFlag_preservesOtherFlags() {
        val settings = localSettings(
            """
            {
              "endpoint": "https://example.invalid",
              "builtin_tool_flags": {
                "create_custom_tool": {"enabled": false},
                "legacy_builtin": true
              }
            }
            """.trimIndent()
        )

        val updated = LocalSettingsCodec.withBuiltinFlag(settings, "create_custom_tool", true)
        val flags = updated.builtinToolFlags!!

        assertEquals("https://example.invalid", updated.endpoint)
        assertTrue(flags["create_custom_tool"]!!.jsonPrimitive.boolean)
        assertTrue(flags["legacy_builtin"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun withLlmAccess_preservesPromptProxyAndTools() {
        val settings = localSettings(
            """
            {
              "prompt":"base",
              "proxy":"http://proxy",
              "memory_prompt":"memory",
              "takeover_keywords":["nexus"],
              "custom_tools":[{"name":"battery_status","description":"Battery","command":"dumpsys battery"}]
            }
            """.trimIndent()
        )

        val updated = LocalSettingsCodec.withLlmAccess(
            settings = settings,
            provider = "openai",
            endpoint = "https://api.example",
            model = "gpt-test",
            apiKey = "secret",
        )

        assertEquals("openai", updated.provider)
        assertEquals("https://api.example", updated.endpoint)
        assertEquals("gpt-test", updated.model)
        assertEquals("secret", updated.apiKey)
        assertEquals("base", updated.prompt)
        assertEquals("http://proxy", updated.proxy)
        assertEquals("memory", updated.memoryPrompt)
        assertEquals(listOf("nexus"), updated.takeoverKeywords)
        assertEquals(1, updated.customTools!!.size)
    }

    @Test
    fun parseLlm_readsMemoriesArray() {
        val settings = localSettings(
            """
            {
              "memory_prompt": "legacy memory",
              "memories": [" A ", " ", "B"]
            }
            """.trimIndent()
        )

        val config = LocalSettingsCodec.parseLlm(settings)

        assertEquals(listOf("A", "B"), config.memories)
        assertEquals("legacy memory", config.memoryPrompt)
    }

    @Test
    fun withMemories_writesTrimmedArray() {
        val updated = LocalSettingsCodec.withMemories(
            settings = localSettings("""{"provider":"openai"}"""),
            memories = listOf(" A ", " ", "B"),
        )

        assertEquals("openai", updated.provider)
        assertEquals(
            listOf("A", "B"),
            updated.props["memories"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun withLlm_preservesMemoryPromptAndWritesMemories() {
        val settings = localSettings(
            """
            {
              "provider": "openai",
              "memory_prompt": "legacy memory"
            }
            """.trimIndent()
        )
        val config = LocalSettingsCodec.parseLlm(settings).copy(
            memories = listOf(" A ", "", "B "),
        )

        val updated = LocalSettingsCodec.withLlm(settings, config)

        assertEquals("legacy memory", updated.memoryPrompt)
        assertEquals(
            listOf("A", "B"),
            updated.props["memories"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    private fun localSettings(json: String): LocalSettings {
        return LocalSettings(Json.parseToJsonElement(json).jsonObject)
    }
}
