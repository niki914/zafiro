package com.niki914.nexus.agentic.repo

import android.content.Context
import android.content.ContextWrapper
import com.niki914.nexus.store.StoreDescriptorRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool as CustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig as LlmConfig
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer as McpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpTool as McpTool

class XRepoTest {
    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    @After
    fun tearDown() {
        XRepo.resetForTest()
    }

    @Test
    fun tryPutDefaultSettings_writesDomainStoresWhenOnboardingIsNotCompleted() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        val updated = XRepo.tryPutDefaultSettings()

        assertTrue(updated)
        assertEquals(
            listOf(
                StoreDescriptorRegistry.AGENT_MAIN_CONFIG_ID,
                StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID,
                StoreDescriptorRegistry.AGENT_REGISTRY_ID,
                StoreDescriptorRegistry.TOOLS_CUSTOM_ID,
                StoreDescriptorRegistry.RULES_EXECUTION_ID,
            ),
            store.writeIds,
        )
        assertEquals(
            LlmConfig(prompt = LocalSettingsDefaults.DEFAULT_SYSTEM_PROMPT.trimIndent()),
            AgentSettingsCodec.parseMainConfig(store.jsonFor(StoreDescriptorRegistry.AGENT_MAIN_CONFIG_ID)),
        )
        assertEquals(
            LocalSettingsDefaults.defaultMemories,
            MemorySettingsCodec.parseMemories(store.jsonFor(StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID)),
        )
        assertEquals(
            listOf(
                CustomTool(
                    name = "launch_wechat",
                    description = "启动微信",
                    command = "am start -n com.tencent.mm/com.tencent.mm.ui.LauncherUI",
                )
            ),
            ToolSettingsCodec.parseCustomTools(store.jsonFor(StoreDescriptorRegistry.TOOLS_CUSTOM_ID)),
        )
        assertEquals(
            LocalSettingsDefaults.defaultExecutionRules,
            RuleSettingsCodec.parseExecutionRules(store.jsonFor(StoreDescriptorRegistry.RULES_EXECUTION_ID)),
        )
    }

    @Test
    fun defaultMemoriesDescribeDomainSettingsTree() {
        val text = LocalSettingsDefaults.defaultMemories.joinToString(separator = "\n")

        assertFalse(text.contains("local_settings.json"))
        assertTrue(text.contains("files/settings"))
        assertTrue(text.contains("com.niki914.nexus.agentic"))
    }

    @Test
    fun tryPutDefaultSettings_skipsWhenOnboardingIsCompleted() = runTest {
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.APP_STATE_ID to AppStateSettingsCodec.encode(
                    AppStateSettings(onboardingCompleted = true)
                )
            )
        )

        val updated = XRepo.tryPutDefaultSettings()

        assertFalse(updated)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun executionRulesList_fallsBackToDefaultsWhenFieldIsMissing() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        val rules = XRepo.executionRules.list()

        assertEquals(LocalSettingsDefaults.defaultExecutionRules, rules)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun saveLlmAccess_updatesOnlyAccessFields() = runTest {
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.AGENT_MAIN_CONFIG_ID to AgentSettingsCodec.encodeMainConfig(
                    LlmConfig(
                        provider = "old",
                        endpoint = "https://old.example",
                        apiKey = "old-key",
                        model = "old-model",
                        prompt = "base",
                        proxy = "http://proxy",
                        memoryPrompt = "memory",
                    )
                )
            )
        )

        XRepo.saveLlmAccess(
            provider = "openai",
            endpoint = "https://api.example",
            model = "gpt-test",
            apiKey = "secret",
        )

        assertEquals(1, store.writeCount)
        assertEquals(
            LlmConfig(
                provider = "openai",
                endpoint = "https://api.example",
                apiKey = "secret",
                model = "gpt-test",
                prompt = "base",
                proxy = "http://proxy",
                memoryPrompt = "memory",
            ),
            AgentSettingsCodec.parseMainConfig(store.jsonFor(StoreDescriptorRegistry.AGENT_MAIN_CONFIG_ID)),
        )
    }

    @Test
    fun memoryApi_replacesAndMutatesMemories() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        XRepo.memory.replaceAll(listOf(" A ", " ", "B"))
        XRepo.memory.add(" C ")
        XRepo.memory.update(1, " B2 ")
        XRepo.memory.delete(0)
        val writeCountBeforeOutOfBoundsUpdate = store.writeCount
        XRepo.memory.update(99, "ignored")
        assertEquals(writeCountBeforeOutOfBoundsUpdate, store.writeCount)
        val writeCountBeforeOutOfBoundsDelete = store.writeCount
        XRepo.memory.delete(-1)
        assertEquals(writeCountBeforeOutOfBoundsDelete, store.writeCount)
        val writeCountBeforeBlankAdd = store.writeCount
        XRepo.memory.add(" ")

        assertEquals(writeCountBeforeBlankAdd, store.writeCount)
        assertEquals(listOf("B2", "C"), XRepo.memory.list())
        assertEquals(
            listOf("B2", "C"),
            MemorySettingsCodec.parseMemories(store.jsonFor(StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID)),
        )
    }

    @Test
    fun mcpSave_replacesByNameAndPreservesOtherServers() = runTest {
        installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID to McpSettingsCodec.encodeServers(
                    listOf(
                        McpServer("aslocate", "http://old.example/mcp"),
                        McpServer("weather", "http://weather.example/mcp"),
                    )
                )
            )
        )

        XRepo.mcp.save(McpServer("aslocate", "http://new.example/mcp", enabled = false))

        assertEquals(
            listOf(
                McpServer("aslocate", "http://new.example/mcp", enabled = false),
                McpServer("weather", "http://weather.example/mcp"),
            ),
            XRepo.mcp.list(),
        )
    }

    @Test
    fun mcpClearCache_removesOnlyTargetCacheKey() = runTest {
        val first = McpServer("aslocate", "http://127.0.0.1:51338/mcp")
        val second = McpServer("weather", "http://127.0.0.1:51339/mcp")
        installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID to McpSettingsCodec.encodeServers(
                    listOf(first, second)
                ),
                StoreDescriptorRegistry.mcpCacheStoreId("aslocate")!! to McpSettingsCodec.encodeCache(
                    serverId = "aslocate",
                    fingerprint = "",
                    tools = listOf(McpTool("lookupSymbol", "Lookup", """{"type":"object"}""")),
                    updatedAt = 1L,
                ),
                StoreDescriptorRegistry.mcpCacheStoreId("weather")!! to McpSettingsCodec.encodeCache(
                    serverId = "weather",
                    fingerprint = "",
                    tools = listOf(McpTool("getWeather", "Weather", """{"type":"object"}""")),
                    updatedAt = 1L,
                ),
            )
        )

        XRepo.mcp.clearCache(first)

        assertEquals(emptyList<McpTool>(), XRepo.mcp.cachedTools(first))
        assertEquals(
            listOf(McpTool("getWeather", "Weather", """{"type":"object"}""")),
            XRepo.mcp.cachedTools(second)
        )
    }

    @Test
    fun executionRulesApi_savesReplacesDeletesAndUpdatesEnabledMode() = runTest {
        val initialRule = ExecutionRule(
            id = "rule-1",
            name = "Rule One",
            enabledMode = ExecutionRuleEnabledMode.ALWAYS,
            patterns = listOf("rm -rf"),
        )
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.RULES_EXECUTION_ID to RuleSettingsCodec.encodeExecutionRules(
                    listOf(initialRule)
                )
            )
        )

        XRepo.executionRules.save(
            ExecutionRule(
                id = "rule-2",
                name = "Rule Two",
                enabledMode = ExecutionRuleEnabledMode.DISABLED,
                patterns = listOf("mkfs"),
            )
        )
        XRepo.executionRules.replace(
            previousId = "rule-1",
            rule = ExecutionRule(
                id = "rule-3",
                name = "Rule Three",
                enabledMode = ExecutionRuleEnabledMode.ALWAYS,
                patterns = listOf("su"),
            )
        )
        XRepo.executionRules.setEnabledMode("rule-2", ExecutionRuleEnabledMode.LOCKED_ONLY)
        XRepo.executionRules.delete("missing")
        XRepo.executionRules.delete("rule-3")

        assertEquals(
            listOf(
                ExecutionRule(
                    id = "rule-2",
                    name = "Rule Two",
                    enabledMode = ExecutionRuleEnabledMode.LOCKED_ONLY,
                    patterns = listOf("mkfs"),
                )
            ),
            XRepo.executionRules.list(),
        )
        assertEquals(5, store.writeCount)
    }

    @Test
    fun customToolSave_rejectsUnsafeCommand() = runTest {
        val store = installStore(
            FakeDomainSettingsStore(StoreDescriptorRegistry.RULES_EXECUTION_ID to unsafeRuleSettings())
        )

        val validation = XRepo.customTools.save(
            CustomTool(
                name = "wipe_data",
                description = "Dangerous",
                command = "rm -rf /data/local/tmp/cache",
            )
        )

        assertNotNull(validation)
        assertEquals("command", validation!!.field)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun customToolReplace_renamesAndPreservesOtherTools() = runTest {
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.TOOLS_CUSTOM_ID to ToolSettingsCodec.encodeCustomTools(
                    listOf(
                        CustomTool("old_name", "Old description", "dumpsys battery"),
                        CustomTool("other_tool", "Other description", "settings list system"),
                    )
                )
            )
        )

        val validation = XRepo.customTools.replace(
            previousName = "old_name",
            tool = CustomTool("new_name", "New description", "pm list packages", enabled = false),
        )

        assertNull(validation)
        assertEquals(1, store.writeCount)
        assertEquals(
            listOf(
                CustomTool("other_tool", "Other description", "settings list system"),
                CustomTool("new_name", "New description", "pm list packages", enabled = false),
            ),
            XRepo.customTools.list(),
        )
    }

    @Test
    fun customToolReplace_rejectsDuplicateName() = runTest {
        val initialTools = listOf(
            CustomTool("old_name", "Old description", "dumpsys battery"),
            CustomTool("existing_tool", "Existing description", "settings list system"),
        )
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.TOOLS_CUSTOM_ID to ToolSettingsCodec.encodeCustomTools(
                    initialTools
                )
            )
        )

        val validation = XRepo.customTools.replace(
            previousName = "old_name",
            tool = CustomTool("existing_tool", "New description", "pm list packages"),
        )

        assertNotNull(validation)
        assertEquals("name", validation!!.field)
        assertEquals("Already exists in custom_tools.", validation.message)
        assertEquals(0, store.writeCount)
        assertEquals(initialTools, XRepo.customTools.list())
    }

    @Test
    fun customToolReplace_rejectsUnsafeCommand() = runTest {
        val initialTools = listOf(
            CustomTool("old_name", "Old description", "dumpsys battery"),
        )
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.RULES_EXECUTION_ID to unsafeRuleSettings(),
                StoreDescriptorRegistry.TOOLS_CUSTOM_ID to ToolSettingsCodec.encodeCustomTools(
                    initialTools
                ),
            )
        )

        val validation = XRepo.customTools.replace(
            previousName = "old_name",
            tool = CustomTool("new_name", "New description", "rm -rf /data/local/tmp/cache"),
        )

        assertNotNull(validation)
        assertEquals("command", validation!!.field)
        assertEquals(0, store.writeCount)
        assertEquals(initialTools, XRepo.customTools.list())
    }

    @Test
    fun builtinSetEnabled_rejectsUnknownTool() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        val validation = XRepo.builtinTools.setEnabled("unknown_tool", true)

        assertNotNull(validation)
        assertEquals("name", validation!!.field)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun builtinListContainsTerminalAndNotRunCommand() = runTest {
        installStore(FakeDomainSettingsStore())

        val names = XRepo.builtinTools.list().map { it.name }

        assertTrue(names.contains("terminal"))
        assertFalse(names.contains("run_command"))
    }

    @Test
    fun builtinTerminalInheritsLegacyRunCommandDisabledFlag() = runTest {
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.TOOLS_BUILTIN_ID to ToolSettingsCodec.encodeBuiltinEnabledForAgents(
                    mapOf("run_command" to false)
                )
            )
        )

        val terminal = XRepo.builtinTools.list().single { it.name == "terminal" }

        assertFalse(terminal.enabled)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun builtinSetEnabled_acceptsTerminalAndRejectsRunCommand() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        val terminalValidation = XRepo.builtinTools.setEnabled("terminal", false)
        val runCommandValidation = XRepo.builtinTools.setEnabled("run_command", false)
        val flags = ToolSettingsCodec.parseBuiltinEnabledForAgents(
            store.jsonFor(StoreDescriptorRegistry.TOOLS_BUILTIN_ID)
        )

        assertNull(terminalValidation)
        assertEquals(false, flags["terminal"])
        assertNotNull(runCommandValidation)
        assertEquals("name", runCommandValidation!!.field)
        assertEquals(1, store.writeCount)
    }

    @Test
    fun customToolSave_acceptsSafeCommand() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        val validation = XRepo.customTools.save(
            CustomTool(
                name = "battery_status",
                description = "Battery status",
                command = "dumpsys battery",
            )
        )

        assertNull(validation)
        assertEquals(1, store.writeCount)
        assertEquals(
            listOf(CustomTool("battery_status", "Battery status", "dumpsys battery")),
            XRepo.customTools.list(),
        )
    }

    private fun installStore(store: FakeDomainSettingsStore): FakeDomainSettingsStore {
        XRepo.installStoreForTest(store)
        XRepo.init(context)
        return store
    }

    private fun unsafeRuleSettings(): String {
        return RuleSettingsCodec.encodeExecutionRules(
            listOf(
                ExecutionRule(
                    id = "dangerous-delete",
                    name = "危险删改",
                    enabledMode = ExecutionRuleEnabledMode.ALWAYS,
                    patterns = listOf(
                        "\\brm\\s+-rf\\b",
                        "\\brm\\s+(?=[^\\n]*--recursive\\b)(?=[^\\n]*--force\\b)[^\\n]*",
                    ),
                )
            ),
        )
    }
}
