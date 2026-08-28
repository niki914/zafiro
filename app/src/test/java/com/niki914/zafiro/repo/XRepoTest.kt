package com.niki914.zafiro.repo

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool as CustomPyTool
import com.niki914.zafiro.settings.model.RuntimeExecutionRule as ExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode as ExecutionRuleEnabledMode
import com.niki914.zafiro.settings.model.RuntimeMcpServer as McpServer

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class XRepoTest {
    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

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
                StoreDescriptorRegistry.LLM_CONFIGS_ID,
                StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID,
                StoreDescriptorRegistry.AGENT_REGISTRY_ID,
                StoreDescriptorRegistry.TOOLS_PY_ID,
                StoreDescriptorRegistry.RULES_EXECUTION_ID,
            ),
            store.writeIds,
        )
        assertEquals(
            LlmConfigsDocument(prompt = LocalSettingsDefaults.DEFAULT_SYSTEM_PROMPT.trimIndent()),
            LlmConfigsSettingsCodec.parse(store.jsonFor(StoreDescriptorRegistry.LLM_CONFIGS_ID)),
        )
        assertEquals(
            LocalSettingsDefaults.defaultMemories(context),
            MemorySettingsCodec.parseMemories(store.jsonFor(StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID)),
        )
        assertEquals(
            XRepo.seedPyToolDefaults(context).map { it.name },
            ToolSettingsCodec.parseCustomPyTools(store.jsonFor(StoreDescriptorRegistry.TOOLS_PY_ID))
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
    fun seedPyTools_addsMissingSeedToolsAndIsIdempotent() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        XRepo.seedPyTools()
        assertEquals(
            XRepo.seedPyToolDefaults(context).map { it.name },
            ToolSettingsCodec.parseCustomPyTools(store.jsonFor(StoreDescriptorRegistry.TOOLS_PY_ID))
                .map { it.name },
        )

        // 幂等：已补齐后不再写
        val writesAfterFirst = store.writeCount
        XRepo.seedPyTools()
        assertEquals(writesAfterFirst, store.writeCount)
    }

    @Test
    fun seedPyTools_keepsUserToolsAndDoesNotOverwriteUserEdits() = runTest {
        val seedNames = XRepo.seedPyToolDefaults(context).map { it.name }
        val userEditedSeed = seedNames.first()
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.TOOLS_PY_ID to ToolSettingsCodec.encodeCustomPyTools(
                    listOf(
                        CustomPyTool(name = userEditedSeed, code = "user-modified"),
                        CustomPyTool(name = "py_custom_user", code = "print(1)"),
                    )
                ),
            )
        )

        XRepo.seedPyTools()

        val tools = ToolSettingsCodec.parseCustomPyTools(store.jsonFor(StoreDescriptorRegistry.TOOLS_PY_ID))
        // 用户改过的同名 seed 不被覆盖
        assertEquals("user-modified", tools.first { it.name == userEditedSeed }.code)
        // 用户自定义工具保留，缺失 seed 全部补上
        assertEquals(seedNames.toSet() + setOf("py_custom_user"), tools.map { it.name }.toSet())
    }

    @Test
    fun allSeedTools_areValid() = runTest {
        val seeds = XRepo.seedPyToolDefaults(context)
        val namePattern = Regex("^py_[a-z][a-z0-9_]{0,63}$")
        assertTrue(seeds.isNotEmpty())
        for (seed in seeds) {
            assertTrue("bad name: ${seed.name}", namePattern.matches(seed.name))
            assertTrue("empty code: ${seed.name}", seed.code.isNotBlank())
            assertTrue("missing main entry: ${seed.name}", "def main(" in seed.code)
            assertTrue(
                "invalid schema: ${seed.name}",
                runCatching { JSONObject(seed.schemaJson) }.isSuccess,
            )
            assertTrue(
                "timeout out of range: ${seed.name}",
                seed.timeoutMs in 1_000L..CustomPyTool.MAX_CUSTOM_PY_TOOL_TIMEOUT_MS,
            )
        }
        // 名字唯一
        assertEquals(seeds.size, seeds.map { it.name }.toSet().size)
    }

    @Test
    fun executionRulesList_fallsBackToDefaultsWhenFieldIsMissing() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        val rules = XRepo.executionRules.list()

        assertEquals(LocalSettingsDefaults.defaultExecutionRules, rules)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun llmConfigs_upsertNewBecomesActiveEditKeepsActive() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        val firstId = "cfg-first"
        assertNull(XRepo.llmConfigs.upsert(savedConfig(id = firstId)))
        var document = LlmConfigsSettingsCodec.parse(store.jsonFor(StoreDescriptorRegistry.LLM_CONFIGS_ID))
        assertEquals(firstId, document.activeId)

        // 编辑非 active 的第二份，active 不变
        val secondId = "cfg-second"
        XRepo.llmConfigs.upsert(savedConfig(id = secondId))
        document = LlmConfigsSettingsCodec.parse(store.jsonFor(StoreDescriptorRegistry.LLM_CONFIGS_ID))
        assertEquals(secondId, document.activeId)
        document = LlmConfigsSettingsCodec.parse(store.jsonFor(StoreDescriptorRegistry.LLM_CONFIGS_ID))
        XRepo.llmConfigs.setActive(firstId)
        document = LlmConfigsSettingsCodec.parse(store.jsonFor(StoreDescriptorRegistry.LLM_CONFIGS_ID))
        assertEquals(firstId, document.activeId)
        XRepo.llmConfigs.upsert(
            savedConfig(id = secondId).copy(model = "edited-model")
        )
        document = LlmConfigsSettingsCodec.parse(store.jsonFor(StoreDescriptorRegistry.LLM_CONFIGS_ID))
        assertEquals(firstId, document.activeId)
        assertEquals("edited-model", document.configs.first { it.id == secondId }.model)
    }

    @Test
    fun llmConfigs_upsertEditPreservesCreatedAt() = runTest {
        val store = installStore(FakeDomainSettingsStore())
        val storeJson = { LlmConfigsSettingsCodec.parse(store.jsonFor(StoreDescriptorRegistry.LLM_CONFIGS_ID)) }

        XRepo.llmConfigs.upsert(savedConfig(id = "cfg-a"))
        val createdAt = storeJson().configs.first { it.id == "cfg-a" }.createdAt
        assertTrue(createdAt > 0L)

        // 编辑保存不得重置 createdAt，否则按 createdAt 排序的列表会重排
        XRepo.llmConfigs.upsert(savedConfig(id = "cfg-a").copy(model = "edited-model"))
        assertEquals(createdAt, storeJson().configs.first { it.id == "cfg-a" }.createdAt)
    }

    @Test
    fun llmConfigs_deleteFallsBackAndResetsOnboardingWhenEmpty() = runTest {
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.APP_STATE_ID to AppStateSettingsCodec.encode(
                    AppStateSettings(onboardingCompleted = true)
                )
            )
        )

        XRepo.llmConfigs.upsert(savedConfig(id = "cfg-a"))
        XRepo.llmConfigs.upsert(savedConfig(id = "cfg-b"))
        // active = 最后新建的 cfg-b；删除 cfg-b 回落 cfg-a
        XRepo.llmConfigs.delete("cfg-b")
        assertEquals("cfg-a", XRepo.llmConfigs.document().activeId)
        assertTrue(XRepo.onboardingCompleted())

        // 清空后回 onboarding
        XRepo.llmConfigs.delete("cfg-a")
        assertTrue(XRepo.llmConfigs.document().configs.isEmpty())
        assertFalse(XRepo.onboardingCompleted())
    }

    @Test
    fun llmConfigs_promptIsGlobal() = runTest {
        installStore(FakeDomainSettingsStore())

        XRepo.llmConfigs.savePrompt("global prompt")
        XRepo.llmConfigs.upsert(savedConfig(id = "cfg-x"))
        assertEquals("global prompt", XRepo.llmConfigs.prompt())
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
    fun customPyToolSave_rejectsUnsafeCode() = runTest {
        val store = installStore(
            FakeDomainSettingsStore(StoreDescriptorRegistry.RULES_EXECUTION_ID to unsafeRuleSettings())
        )

        val validation = XRepo.customPyTools.save(
            CustomPyTool(
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
    fun customPyToolSave_rejectsInvalidName() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        val validation = XRepo.customPyTools.save(
            CustomPyTool(name = "not_py_prefix", code = "def main():\n    pass")
        )

        assertNotNull(validation)
        assertEquals("name", validation!!.field)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun customPyToolSave_rejectsReservedName() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        val validation = XRepo.customPyTools.save(
            CustomPyTool(name = "py_terminal", code = "def main():\n    pass")
        )

        assertNotNull(validation)
        assertEquals("name", validation!!.field)
        assertEquals(0, store.writeCount)
    }

    @Test
    fun customPyToolSave_rejectsDuplicateNameWhenNotOverwriting() = runTest {
        val initialTools = listOf(CustomPyTool(name = "py_existing", code = "def main():\n    pass"))
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.TOOLS_PY_ID to ToolSettingsCodec.encodeCustomPyTools(initialTools)
            )
        )

        val validation = XRepo.customPyTools.save(
            CustomPyTool(name = "py_existing", code = "def main():\n    pass"),
            overwrite = false,
        )

        assertNotNull(validation)
        assertEquals("name", validation!!.field)
        assertEquals(0, store.writeCount)
        assertEquals(initialTools, XRepo.customPyTools.list())
    }

    @Test
    fun customPyToolSave_overwriteReplacesEntry() = runTest {
        val initialTools = listOf(
            CustomPyTool(name = "py_a", code = "def main():\n    print('a')"),
            CustomPyTool(name = "py_b", code = "def main():\n    print('b')"),
        )
        val store = installStore(
            FakeDomainSettingsStore(
                StoreDescriptorRegistry.TOOLS_PY_ID to ToolSettingsCodec.encodeCustomPyTools(initialTools)
            )
        )

        val validation = XRepo.customPyTools.save(
            CustomPyTool(name = "py_a", code = "def main():\n    print('a2')", enabled = false),
        )

        assertNull(validation)
        assertEquals(1, store.writeCount)
        assertEquals(
            listOf("py_a", "py_b"),
            XRepo.customPyTools.list().map { it.name },
        )
        assertFalse(XRepo.customPyTools.list().single { it.name == "py_a" }.enabled)
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
    fun customPyToolSave_acceptsValidTool() = runTest {
        val store = installStore(FakeDomainSettingsStore())

        val validation = XRepo.customPyTools.save(
            CustomPyTool(
                name = "py_battery",
                description = "Battery status",
                code = "def main():\n    print('ok')",
            )
        )

        assertNull(validation)
        assertEquals(1, store.writeCount)
        assertEquals(
            listOf("py_battery"),
            XRepo.customPyTools.list().map { it.name },
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

private fun savedConfig(
    id: String,
    model: String = "test-model",
): SavedLlmConfig {
    return SavedLlmConfig(
        id = id,
        name = id,
        provider = "deepseek",
        endpoint = "https://api.deepseek.com/chat/completions",
        apiKey = "secret",
        model = model,
        protocol = "openai-responses",
        proxy = "",
    )
}
