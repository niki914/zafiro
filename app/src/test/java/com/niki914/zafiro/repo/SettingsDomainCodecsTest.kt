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
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool as CustomPyTool
import com.niki914.zafiro.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode
import com.niki914.zafiro.settings.model.RuntimeMcpServer as McpServer
import com.niki914.zafiro.settings.model.RuntimeMcpServiceConfig
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
                    thinkingLevel = "high",
                    proxy = "http://proxy",
                    maxTokensText = "32k",
                    contextWindowText = "1M",
                    createdAt = 1L,
                    updatedAt = 2L,
                ),
                SavedLlmConfig(
                    id = "cfg-b",
                    name = "Backup",
                    provider = "anthropic",
                    endpoint = "",
                    apiKey = "",
                    model = "",
                    protocol = "",
                    proxy = ""
                ),
            ),
        )

        val parsed = LlmConfigsSettingsCodec.encode(document).let(LlmConfigsSettingsCodec::parse)

        assertEquals(document, parsed)
    }

    @Test
    fun llmConfigsDocumentLegacyConfigWithoutMaxTokensParsesAsUnset() {
        // 老数据（无 max_tokens 键）：空串 = 未设置，由 gateway 落云端/默认值
        val json = """
            {"active_id":"cfg-a","configs":[{"id":"cfg-a","name":"Primary","provider":"openai","endpoint":"https://api.example","api_key":"k","model":"m","protocol":"openai-responses","supports_images":true,"proxy":"","thinking_level":"high","created_at":1,"updated_at":2}]}
        """.trimIndent()

        val config = LlmConfigsSettingsCodec.parse(json).configs.single()

        assertEquals("", config.maxTokensText)
        assertEquals("", config.contextWindowText)
    }

    @Test
    fun llmConfigsDocumentLegacyNumericValuesReadAsText() {
        // 存量兼容：旧格式存数字，新格式存文本。string() 把数字读成字面文本，无损。
        // 数字读成字面文本；0 归一为未设置（空串）。
        val json = """
            {"active_id":"cfg-a","configs":[{"id":"cfg-a","name":"Primary","provider":"openai","endpoint":"https://api.example","api_key":"k","model":"m","protocol":"openai-responses","proxy":"","max_tokens":128000,"context_window_tokens":0,"created_at":1,"updated_at":2}]}
        """.trimIndent()

        val config = LlmConfigsSettingsCodec.parse(json).configs.single()

        assertEquals("128000", config.maxTokensText)
        assertEquals("", config.contextWindowText)
    }

    @Test
    fun llmConfigsDocumentCorruptValueFieldsFallBackToUnset() {
        // 类型不合法（对象/数组）：as? JsonPrimitive 落空 → 空串，不崩
        val json = """
            {"active_id":"cfg-a","configs":[{"id":"cfg-a","name":"Primary","provider":"openai","endpoint":"https://api.example","api_key":"k","model":"m","protocol":"openai-responses","proxy":"","max_tokens":{"bad":1},"context_window_tokens":[1],"created_at":1,"updated_at":2}]}
        """.trimIndent()

        val config = LlmConfigsSettingsCodec.parse(json).configs.single()

        assertEquals("", config.maxTokensText)
        assertEquals("", config.contextWindowText)
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
    fun customPyToolsParseFieldsAndSkipInvalidEntries() {
        val tools = ToolSettingsCodec.parseCustomPyTools(
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
                CustomPyTool(
                    name = "py_battery", code = "def main():\n    pass", description = "Battery",
                    schemaJson = "{\"type\":\"object\"}", enabled = true, timeoutMs = 45000
                ),
                CustomPyTool(name = "py_disabled", code = "def main():\n    pass", enabled = false),
            ),
            tools,
        )
    }

    // 保护：手改 tools/custom_py_tools.json 的 timeout_ms 绕过写入口校验。
    // 负值会走到 PyRuntime 的 withTimeout(negative) 抛 IllegalArgumentException，
    // 极大值则让工具永不超时，所以读盘侧必须把值夹回 1000..120000。
    @Test
    fun customPyToolTimeoutMsIsClampedToWritableRange() {
        fun timeoutOf(raw: String) = ToolSettingsCodec.parseCustomPyTools(
            """{"tools":[{"name":"py_x","code":"def main(): pass","timeout_ms":$raw}]}"""
        ).single().timeoutMs

        assertEquals(CustomPyTool.MIN_CUSTOM_PY_TOOL_TIMEOUT_MS, timeoutOf("-1"))
        assertEquals(CustomPyTool.MIN_CUSTOM_PY_TOOL_TIMEOUT_MS, timeoutOf("500"))
        assertEquals(CustomPyTool.MAX_CUSTOM_PY_TOOL_TIMEOUT_MS, timeoutOf("999999999"))
        assertEquals(45_000L, timeoutOf("45000"))
    }

    @Test
    fun customPyToolsEncodeRoundTrips() {
        val tools = listOf(
            CustomPyTool(
                name = "py_battery",
                code = "def main():\n    pass",
                description = "Battery"
            ),
        )
        assertEquals(
            tools,
            ToolSettingsCodec.parseCustomPyTools(ToolSettingsCodec.encodeCustomPyTools(tools))
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
    fun appStateFloatingBallDefaultsDisabledAndRoundTrips() {
        assertFalse(AppStateSettingsCodec.parse("""{}""").floatingBallEnabled)

        val json = AppStateSettingsCodec.encode(AppStateSettings(floatingBallEnabled = true))
        assertTrue(jsonObject(json)["floating_ball_enabled"]!!.jsonPrimitive.boolean)
        assertTrue(AppStateSettingsCodec.parse(json).floatingBallEnabled)
    }

    @Test
    fun appStateResidentNotificationDefaultsDisabledAndRoundTrips() {
        assertFalse(AppStateSettingsCodec.parse("""{}""").residentNotificationEnabled)

        val json =
            AppStateSettingsCodec.encode(AppStateSettings(residentNotificationEnabled = true))
        assertTrue(jsonObject(json)["resident_notification_enabled"]!!.jsonPrimitive.boolean)
        assertTrue(AppStateSettingsCodec.parse(json).residentNotificationEnabled)
    }

    @Test
    fun appStateFloatingBallAutoExpandDefaultsEnabledAndRoundTrips() {
        assertTrue(AppStateSettingsCodec.parse("""{}""").floatingBallAutoExpand)

        val json = AppStateSettingsCodec.encode(AppStateSettings(floatingBallAutoExpand = false))
        assertFalse(jsonObject(json)["floating_ball_auto_expand"]!!.jsonPrimitive.boolean)
        assertFalse(AppStateSettingsCodec.parse(json).floatingBallAutoExpand)
    }

    @Test
    fun appStateVoiceAssistantIsolatedSessionDefaultsEnabledAndRoundTrips() {
        assertTrue(AppStateSettingsCodec.parse("""{}""").voiceAssistantIsolatedSession)

        val json =
            AppStateSettingsCodec.encode(AppStateSettings(voiceAssistantIsolatedSession = false))
        assertFalse(jsonObject(json)["voice_assistant_isolated_session"]!!.jsonPrimitive.boolean)
        assertFalse(AppStateSettingsCodec.parse(json).voiceAssistantIsolatedSession)
    }

    @Test
    fun appStateRoundTripUsesSnakeCaseKeys() {
        val state = AppStateSettings(
            onboardingCompleted = true,
            languageTag = "zh-CN",
        )
        val json = AppStateSettingsCodec.encode(state)
        val root = jsonObject(json)

        assertEquals(state, AppStateSettingsCodec.parse(json))
        assertTrue(root["onboarding_completed"]!!.jsonPrimitive.boolean)
        assertEquals("zh-CN", root["language_tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun appStateMalformedFallsBackToDefaults() {
        assertEquals(AppStateSettings(), AppStateSettingsCodec.parse("not json{"))
        assertEquals(AppStateSettings(), AppStateSettingsCodec.parse(""))
        assertEquals(AppStateSettings(), AppStateSettingsCodec.parse("null"))
    }

    @Test
    fun appStateBlankThemeModeFallsBackToLight() {
        assertEquals("light", AppStateSettingsCodec.parse("""{"theme_mode":""}""").themeMode)
    }

    @Test
    fun appStateBlankSeedColorMeansFollowWallpaper() {
        assertEquals("", AppStateSettingsCodec.parse("""{"theme_seed_color":""}""").themeSeedColor)
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

    @Test
    fun appStateCompactionThresholdIsClampedOrPreservesZero() {
        fun thresholdOf(raw: String) =
            AppStateSettingsCodec.parse("""{"context_compaction_threshold_percent":$raw}""").contextCompactionThresholdPercent

        assertEquals(0, thresholdOf("0"))
        assertEquals(30, thresholdOf("10"))
        assertEquals(30, thresholdOf("-50"))
        assertEquals(95, thresholdOf("120"))
        assertEquals(70, thresholdOf("70"))
        assertEquals(70, thresholdOf("\"invalid\""))
    }

    // 保护：手改 tools/mcp/service.json 的 port 绕过设置页校验（1024..65535）。
    // 越界端口不会崩，但会让 MCP 服务静默起不来，所以读盘侧必须夹回可用区间。
    @Test
    fun mcpServicePortIsClampedToWritableRange() {
        fun portOf(raw: String) = McpServiceSettingsCodec.parse("""{"port":$raw}""").port

        assertEquals(RuntimeMcpServiceConfig.MIN_PORT, portOf("0"))
        assertEquals(RuntimeMcpServiceConfig.MIN_PORT, portOf("80"))
        assertEquals(RuntimeMcpServiceConfig.MAX_PORT, portOf("99999"))
        assertEquals(8791, portOf("8791"))
    }

    private fun jsonObject(json: String) = Json.parseToJsonElement(json).jsonObject
}
