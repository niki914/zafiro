package com.niki914.zafiro.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.niki914.zafiro.settings.model.RuntimePyTool as PyTool
import com.niki914.zafiro.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode
import com.niki914.zafiro.settings.model.RuntimeMcpServer as McpServer
import com.niki914.zafiro.settings.model.RuntimeTakeoverRule as TakeoverRule
import com.niki914.zafiro.settings.model.RuntimeTakeoverTarget as TakeoverTarget

class SettingsDomainCodecsTest {

    @Test
    fun llmConfigsMissingDocumentParsesToEmpty() {
        assertEquals(LlmConfigsDocument(), LlmConfigsSettingsCodec.parse("""{}"""))
    }

    @Test
    fun llmConfigsDocumentRoundTripKeepsAllFields() {
        val document = LlmConfigsDocument(
            activeId = "cfg-a",
            prompt = "global prompt",
            configs = listOf(
                SavedLlmConfig(
                    id = "cfg-a",
                    name = "Primary",
                    provider = "openai",
                    endpoint = "https://api.example",
                    apiKey = "secret",
                    model = "gpt-test",
                    protocol = "openai-responses",
                    proxy = "http://proxy",
                    createdAt = 1L,
                    updatedAt = 2L,
                ),
                SavedLlmConfig(id = "cfg-b", name = "Backup", provider = "anthropic", endpoint = "", apiKey = "", model = "", protocol = "", proxy = ""),
            ),
        )

        val parsed = LlmConfigsSettingsCodec.encode(document).let(LlmConfigsSettingsCodec::parse)

        assertEquals(document, parsed)
    }

    @Test
    fun builtinV2ParsesBooleansAndIgnoresNonBooleans() {
        val flags = ToolSettingsCodec.parseBuiltinEnabled(
            """
            {
              "version": 2,
              "enabled": {
                "launch_app": false,
                "terminal": true,
                "open_uri": "yes",
                "notify": null
              }
            }
            """.trimIndent()
        )

        assertEquals(false, flags["launch_app"])
        assertEquals(true, flags["terminal"])
        assertNull(flags["open_uri"])
        assertNull(flags["notify"])
    }

    @Test
    fun builtinV2LegacyKeyYieldsEmptyConfig() {
        val flags = ToolSettingsCodec.parseBuiltinEnabled(
            """{"enabled_for_agents":{"terminal":["main"]}}"""
        )

        assertTrue(flags.isEmpty())
    }

    @Test
    fun builtinV2EncodeRoundTripWritesVersionAuditKey() {
        val json = ToolSettingsCodec.encodeBuiltinEnabled(
            mapOf("launch_app" to true, "terminal" to false)
        )

        assertEquals(2, jsonObject(json)["version"]!!.jsonPrimitive.int)
        val flags = ToolSettingsCodec.parseBuiltinEnabled(json)
        assertEquals(true, flags["launch_app"])
        assertEquals(false, flags["terminal"])
    }

    @Test
    fun pyToolsParseFieldsAndSkipInvalidEntries() {
        val tools = ToolSettingsCodec.parsePyTools(
            """
            {
              "tools": [
                {"name":"py_battery","description":"Battery","code":"def main():\n    pass","schema":"{\"type\":\"object\"}","enabled":true,"timeout_ms":45000},
                {"name":"py_disabled","code":"def main():\n    pass","enabled":false},
                {"name":"py_missing_code","description":"Broken"}
              ]
            }
            """.trimIndent()
        )

        assertEquals(
            listOf(
                PyTool(name = "py_battery", code = "def main():\n    pass", description = "Battery",
                    schemaJson = "{\"type\":\"object\"}", enabled = true, timeoutMs = 45000),
                PyTool(name = "py_disabled", code = "def main():\n    pass", enabled = false),
            ),
            tools,
        )
    }

    @Test
    fun pyToolsEncodeRoundTrips() {
        val tools = listOf(
            PyTool(name = "py_battery", code = "def main():\n    pass", description = "Battery"),
        )
        assertEquals(tools, ToolSettingsCodec.parsePyTools(ToolSettingsCodec.encodePyTools(tools)))
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
            listOf(TakeoverRule("default", "Default", TakeoverTarget.ZAFIRO, false, listOf(".*")))
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

    @Test
    fun appStateRoundTripKeepsLanguageAndLoadLastConversation() {
        val state = AppStateSettings(
            onboardingCompleted = true,
            languageTag = "zh-CN",
            loadLastConversationOnStartup = true,
        )

        val parsed = AppStateSettingsCodec.encode(state).let(AppStateSettingsCodec::parse)

        assertEquals(state, parsed)
        val root = jsonObject(AppStateSettingsCodec.encode(state))
        assertEquals("zh-CN", root["language_tag"]!!.jsonPrimitive.content)
        assertEquals(true, root["load_last_conversation_on_startup"]!!.jsonPrimitive.boolean)
    }

    private fun jsonObject(json: String) = Json.parseToJsonElement(json).jsonObject
}
