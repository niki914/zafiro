package com.niki914.zafiro.repo

import android.content.Context
import android.content.ContextWrapper
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.store.StoreDescriptorRegistry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.model.RuntimePyTool as PyTool
import com.niki914.zafiro.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode
import com.niki914.zafiro.settings.model.RuntimeLlmConfig as LlmConfig
import com.niki914.zafiro.settings.model.RuntimeMcpServer as McpServer

class XRepoTest {
    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

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
                StoreDescriptorRegistry.TOOLS_PY_ID,
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
            listOf("py_web_search", "py_launch_wechat"),
            ToolSettingsCodec.parsePyTools(store.jsonFor(StoreDescriptorRegistry.TOOLS_PY_ID))
                .map { it.name },
        )
        assertEquals(
            LocalSettingsDefaults.defaultExecutionRules,
            RuleSettingsCodec.parseExecutionRules(store.jsonFor(StoreDescriptorRegistry.RULES_EXECUTION_ID)),
        )
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
    fun memoryApi_addDedupesIdenticalContent() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        XRepo.memory.add("unique")
        val writeCountAfterFirst = store.writeCount
        XRepo.memory.add("unique")

        assertEquals(writeCountAfterFirst, store.writeCount)
        assertEquals(listOf("unique"), XRepo.memory.list())
    }

    @Test
    fun memoryApi_removeByTextReturnsNotFoundForZeroMatches() = runTest {
        val store = installStore(FakeDomainSettingsStore())
        XRepo.memory.add("only")

        val writeCountBefore = store.writeCount
        val result = XRepo.memory.removeByText("nonexistent")

        assertEquals(MemoryMutationResult.NotFound, result)
        assertEquals(writeCountBefore, store.writeCount)
        assertEquals(listOf("only"), XRepo.memory.list())
    }

    @Test
    fun memoryApi_removeByTextReturnsAmbiguousForMultipleDistinctMatches() = runTest {
        val store = installStore(FakeDomainSettingsStore())
        XRepo.memory.replaceAll(listOf("User prefers dark mode", "User prefers concise answers"))

        val writeCountBefore = store.writeCount
        val result = XRepo.memory.removeByText("User prefers")

        assertEquals(MemoryMutationResult.Ambiguous, result)
        assertEquals(writeCountBefore, store.writeCount)
        assertEquals(2, XRepo.memory.list().size)
    }

    @Test
    fun memoryApi_removeByTextAllowsIdenticalDuplicates() = runTest {
        val store = installStore(FakeDomainSettingsStore())
        XRepo.memory.replaceAll(listOf("dup", "dup", "unique"))

        val result = XRepo.memory.removeByText("dup")

        assertEquals(MemoryMutationResult.Ok, result)
        assertEquals(2, XRepo.memory.list().size)
        assertEquals(listOf("dup", "unique"), XRepo.memory.list())
    }

    @Test
    fun memoryApi_replaceByTextUpdatesInPlace() = runTest {
        val store = installStore(FakeDomainSettingsStore())
        XRepo.memory.replaceAll(listOf("keep", "old", "also-keep"))

        val writeCountBefore = store.writeCount
        val result = XRepo.memory.replaceByText("old", "new")

        assertEquals(MemoryMutationResult.Ok, result)
        assertEquals(writeCountBefore + 1, store.writeCount)
        assertEquals(listOf("keep", "new", "also-keep"), XRepo.memory.list())
    }

    @Test
    fun memoryApi_replaceByTextReturnsNotFoundForZeroMatches() = runTest {
        val store = installStore(FakeDomainSettingsStore())
        XRepo.memory.add("only")

        val writeCountBefore = store.writeCount
        val result = XRepo.memory.replaceByText("nonexistent", "new")

        assertEquals(MemoryMutationResult.NotFound, result)
        assertEquals(writeCountBefore, store.writeCount)
        assertEquals(listOf("only"), XRepo.memory.list())
    }

    @Test
    fun memoryApi_replaceByTextReturnsAmbiguousForMultipleDistinctMatches() = runTest {
        val store = installStore(FakeDomainSettingsStore())
        XRepo.memory.replaceAll(listOf("User prefers dark mode", "User prefers concise answers"))

        val writeCountBefore = store.writeCount
        val result = XRepo.memory.replaceByText("User prefers", "new")

        assertEquals(MemoryMutationResult.Ambiguous, result)
        assertEquals(writeCountBefore, store.writeCount)
    }

    @Test
    fun memoryApi_writeFailureThrowsNotReturnsOk() = runTest {
        installStore(FakeDomainSettingsStore(ownerWriteSucceeds = false))

        var threw = false
        try {
            XRepo.memory.add("value")
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue(threw)
        assertEquals(emptyList<String>(), XRepo.memory.list())
    }

    @Test
    fun memoryApi_replaceWriteFailureThrowsAndPreservesOldEntry() = runTest {
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID to
                    MemorySettingsCodec.encodeMemories(listOf("old"), 0L),
                ownerWriteSucceeds = false,
            )
        )

        var threw = false
        try {
            XRepo.memory.replaceByText("old", "new")
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue(threw)
        assertEquals(listOf("old"), XRepo.memory.list())
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
    fun pyToolSave_rejectsUnsafeCode() = runTest {
        val store = installStore(
            FakeDomainSettingsStore(StoreDescriptorRegistry.RULES_EXECUTION_ID to unsafeRuleSettings())
        )

        val validation = XRepo.pyTools.save(
            PyTool(
                name = "py_wipe_data",
                description = "Dangerous",
                code = "import os\nos.popen('rm -rf /data/local/tmp/cache')",
            )
        )

        assertNotNull(validation)
        assertEquals("code", validation!!.field)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun pyToolSave_rejectsInvalidName() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        val validation = XRepo.pyTools.save(
            PyTool(name = "not_py_prefix", code = "def main():\n    pass")
        )

        assertNotNull(validation)
        assertEquals("name", validation!!.field)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun pyToolSave_rejectsReservedName() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        val validation = XRepo.pyTools.save(
            PyTool(name = "py_terminal", code = "def main():\n    pass")
        )

        assertNotNull(validation)
        assertEquals("name", validation!!.field)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun pyToolSave_rejectsDuplicateNameWhenNotOverwriting() = runTest {
        val initialTools = listOf(PyTool(name = "py_existing", code = "def main():\n    pass"))
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.TOOLS_PY_ID to ToolSettingsCodec.encodePyTools(initialTools)
            )
        )

        val validation = XRepo.pyTools.save(
            PyTool(name = "py_existing", code = "def main():\n    pass"),
            overwrite = false,
        )

        assertNotNull(validation)
        assertEquals("name", validation!!.field)
        assertEquals(0, store.writeCount)
        assertEquals(initialTools, XRepo.pyTools.list())
    }

    @Test
    fun pyToolSave_overwriteReplacesEntry() = runTest {
        val initialTools = listOf(
            PyTool(name = "py_a", code = "def main():\n    print('a')"),
            PyTool(name = "py_b", code = "def main():\n    print('b')"),
        )
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.TOOLS_PY_ID to ToolSettingsCodec.encodePyTools(initialTools)
            )
        )

        val validation = XRepo.pyTools.save(
            PyTool(name = "py_a", code = "def main():\n    print('a2')", enabled = false),
        )

        assertNull(validation)
        assertEquals(1, store.writeCount)
        assertEquals(
            listOf("py_a", "py_b"),
            XRepo.pyTools.list().map { it.name },
        )
        assertFalse(XRepo.pyTools.list().single { it.name == "py_a" }.enabled)
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
    fun builtinTerminalIgnoresLegacyRunCommandFlag() = runTest {
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.TOOLS_BUILTIN_ID to ToolSettingsCodec.encodeBuiltinEnabled(
                    mapOf("run_command" to false)
                )
            )
        )

        val terminal = XRepo.builtinTools.list().single { it.name == "terminal" }

        assertTrue(terminal.enabled)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun pyToolSave_acceptsValidTool() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        val validation = XRepo.pyTools.save(
            PyTool(
                name = "py_battery",
                description = "Battery status",
                code = "def main():\n    print('ok')",
            )
        )

        assertNull(validation)
        assertEquals(1, store.writeCount)
        assertEquals(
            listOf("py_battery"),
            XRepo.pyTools.list().map { it.name },
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
