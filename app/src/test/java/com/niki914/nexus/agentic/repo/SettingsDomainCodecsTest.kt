package com.niki914.nexus.agentic.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig as LlmConfig
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer as McpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpTool as McpTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverRule as TakeoverRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeTakeoverTarget as TakeoverTarget

class SettingsDomainCodecsTest {

    @Test
    fun agentMissingLlmReturnsEmptyConfig() {
        assertEquals(LlmConfig(), AgentSettingsCodec.parseMainConfig("""{}"""))
    }

    @Test
    fun agentConfigRoundTripKeepsCurrentLlmFields() {
        val config = LlmConfig(
            provider = "openai",
            endpoint = "https://api.example",
            apiKey = "secret",
            model = "gpt-test",
            prompt = "base prompt",
            proxy = "http://proxy",
            memoryPrompt = "memory prompt",
            takeoverKeywords = listOf("nexus", "chat"),
        )

        val json = AgentSettingsCodec.encodeMainConfig(config)

        assertEquals(config, AgentSettingsCodec.parseMainConfig(json))
        val root = jsonObject(json)
        assertEquals("main", root["id"]!!.jsonPrimitive.content)
        assertFalse(root["llm"]!!.jsonObject.containsKey("memories"))
    }

    @Test
    fun builtinGhostAgentReturnsFalseAndNonStringAgentsAreIgnored() {
        val flags = ToolSettingsCodec.parseBuiltinEnabledForAgents(
            """
            {
              "enabled_for_agents": {
                "launch_app": ["ghost"],
                "search_apps": ["main", 1, true, {"bad": "agent"}, ["nested"]],
                "open_uri": "main"
              }
            }
            """.trimIndent()
        )

        assertEquals(false, flags["launch_app"])
        assertEquals(true, flags["search_apps"])
        assertNull(flags["open_uri"])
    }

    @Test
    fun builtinFlagsEncodeEnabledForMainAgent() {
        val json = ToolSettingsCodec.encodeBuiltinEnabledForAgents(
            mapOf("launch_app" to true, "terminal" to false)
        )
        val agents = jsonObject(json)["enabled_for_agents"]!!.jsonObject

        assertEquals(
            listOf("main"),
            agents["launch_app"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(agents["terminal"]!!.jsonArray.isEmpty())
    }

    @Test
    fun builtinFlagsDecodeLegacyRunCommandWithoutRewritingKey() {
        val json = """
            {
              "enabled_for_agents": {
                "run_command": []
              }
            }
        """.trimIndent()

        val flags = ToolSettingsCodec.parseBuiltinEnabledForAgents(json)
        val encoded = ToolSettingsCodec.encodeBuiltinEnabledForAgents(flags)
        val agents = jsonObject(encoded)["enabled_for_agents"]!!.jsonObject

        assertEquals(false, flags["run_command"])
        assertTrue(agents["run_command"]!!.jsonArray.isEmpty())
        assertFalse(agents.containsKey("terminal"))
    }

    @Test
    fun customToolsUseEnabledForAgentsAndSkipInvalidTools() {
        val tools = ToolSettingsCodec.parseCustomTools(
            """
            {
              "tools": [
                {"name":"battery","description":"Battery","command":"dumpsys battery","enabled_for_agents":["main"]},
                {"name":"ghost","command":"date","enabled_for_agents":["ghost"]},
                {"name":"missing_command","description":"Broken"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                CustomTool("battery", "Battery", "dumpsys battery", true),
                CustomTool("ghost", "", "date", false),
            ),
            tools,
        )
    }

    @Test
    fun mcpServerMissingRequiredFieldsIsSkippedAndHeadersKeepStringsOnly() {
        val servers = McpSettingsCodec.parseServers(
            """
            {
              "servers": [
                {
                  "id": "filesystem",
                  "name": "filesystem",
                  "url": "http://127.0.0.1:3000/mcp",
                  "headers": {"Authorization":"Bearer token", "Retry": 3},
                  "enabled_for_agents": ["main"]
                },
                {"id":"broken","name":"broken"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                McpServer(
                    name = "filesystem",
                    url = "http://127.0.0.1:3000/mcp",
                    enabled = true,
                    headers = mapOf("Authorization" to "Bearer token"),
                )
            ),
            servers,
        )
    }

    @Test
    fun mcpCacheToolMissingInputSchemaIsSkipped() {
        val tools = McpSettingsCodec.parseCache(
            """
            {
              "server_id": "filesystem",
              "tools": [
                {"name":"read_file","description":"Read","input_schema":{"type":"object"}},
                {"name":"broken","description":"Broken"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(listOf(McpTool("read_file", "Read", """{"type":"object"}""")), tools)
    }

    @Test
    fun normalizeServerIdBuildsSafeIdsFromDisplayNames() {
        assertEquals("filesystem_1", McpSettingsCodec.normalizeServerId("FileSystem 1"))
        assertEquals("mcp_example_com", McpSettingsCodec.normalizeServerId("mcp.example.com"))
        assertEquals("bad", McpSettingsCodec.normalizeServerId("../bad"))
        assertEquals(
            "https_mcp_example_com_mcp",
            McpSettingsCodec.normalizeServerId("https://mcp.example.com/mcp")
        )
        assertNull(McpSettingsCodec.normalizeServerId(" "))
    }

    @Test
    fun mcpServerEncodeKeepsDottedNamesWithSafeGeneratedId() {
        val json = McpSettingsCodec.encodeServers(
            listOf(McpServer("mcp.example.com", "https://mcp.example.com/mcp"))
        )
        val server = jsonObject(json)["servers"]!!.jsonArray.single().jsonObject

        assertEquals("mcp_example_com", server["id"]!!.jsonPrimitive.content)
        assertEquals("mcp.example.com", server["name"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(McpServer("mcp.example.com", "https://mcp.example.com/mcp")),
            McpSettingsCodec.parseServers(json),
        )
    }

    @Test
    fun memoryParsesStringArrayAndSkipsObjectMissingContent() {
        assertEquals(
            listOf("a"),
            MemorySettingsCodec.parseMemories("""{"memories":[" a ", " "]}""")
        )
        assertEquals(
            emptyList<String>(),
            MemorySettingsCodec.parseMemories("""{"memories":[{"id":"x"}]}""")
        )
    }

    @Test
    fun memoryEncodeWritesObjectEntries() {
        val json = MemorySettingsCodec.encodeMemories(listOf(" A ", "", "B"), nowMillis = 42L)
        val memories = jsonObject(json)["memories"]!!.jsonArray.map { it.jsonObject }

        assertEquals(listOf("A", "B"), memories.map { it["content"]!!.jsonPrimitive.content })
        assertEquals("mem_42_0", memories[0]["id"]!!.jsonPrimitive.content)
        assertEquals(42L, memories[0]["created_at"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun rulesSkipMissingIdAndTakeoverEnabledDefaultsTrue() {
        val executionRules = RuleSettingsCodec.parseExecutionRules(
            """{"rules":[{"name":"Broken"},{"id":"deny_rm","name":"Deny rm","enabled_mode":"ALWAYS","patterns":[" rm ", " "]}]}"""
        )
        val takeoverRules = RuleSettingsCodec.parseTakeoverRules(
            """{"rules":[{"id":"default","name":"Default","target":"NATIVE_ASSISTANT","patterns":[".*"]}]}"""
        )

        assertEquals(
            listOf(
                ExecutionRule(
                    "deny_rm",
                    "Deny rm",
                    ExecutionRuleEnabledMode.ALWAYS,
                    listOf("rm")
                )
            ),
            executionRules,
        )
        assertEquals(
            listOf(
                TakeoverRule(
                    "default",
                    "Default",
                    TakeoverTarget.NATIVE_ASSISTANT,
                    true,
                    listOf(".*")
                )
            ),
            takeoverRules,
        )
    }

    @Test
    fun ruleEncodingWritesRulesArray() {
        val json = RuleSettingsCodec.encodeTakeoverRules(
            listOf(TakeoverRule("default", "Default", TakeoverTarget.NEXUS, false, listOf(".*")))
        )
        val rule = jsonObject(json)["rules"]!!.jsonArray.single().jsonObject

        assertEquals("default", rule["id"]!!.jsonPrimitive.content)
        assertFalse(rule["enabled"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun appStateMissingFieldsReturnDefaults() {
        assertEquals(AppStateSettings(), AppStateSettingsCodec.parse("""{}"""))
    }

    @Test
    fun appStateRoundTripUsesSnakeCaseKeys() {
        val state = AppStateSettings(
            onboardingCompleted = true,
            startupAssistantUi = "chat_only",
            lastOpenedAgentId = "main",
        )
        val json = AppStateSettingsCodec.encode(state)
        val root = jsonObject(json)

        assertEquals(state, AppStateSettingsCodec.parse(json))
        assertTrue(root["onboarding_completed"]!!.jsonPrimitive.boolean)
        assertEquals("chat_only", root["startup_assistant_ui"]!!.jsonPrimitive.content)
    }

    private fun jsonObject(json: String) = Json.parseToJsonElement(json).jsonObject
}
