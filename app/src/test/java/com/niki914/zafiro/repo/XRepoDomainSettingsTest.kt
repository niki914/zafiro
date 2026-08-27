package com.niki914.zafiro.repo

import android.content.Context
import android.content.ContextWrapper
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.store.StoreDescriptorRegistry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import com.niki914.zafiro.settings.model.RuntimeAgentMemoryMode as AgentMemoryMode
import com.niki914.zafiro.settings.model.RuntimeAgentProfile as AgentProfile
import com.niki914.zafiro.settings.model.RuntimeMcpServer as McpServer

class XRepoDomainSettingsTest {
    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.niki914.zafiro"
    }

    @After
    fun tearDown() {
        XRepo.resetForTest()
    }

    @Test
    fun appStateTogglePersistsAndKeepsOtherFields() = runTest {
        val store = FakeDomainSettingsStore()
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        XRepo.setOnboardingCompleted(true)
        XRepo.setLoadLastConversationOnStartup(true)
        XRepo.setLanguageTag("zh-CN")

        assertTrue(XRepo.loadLastConversationOnStartup())
        assertEquals("zh-CN", XRepo.languageTag())
        assertTrue(XRepo.onboardingCompleted())
    }

    @Test
    fun llmConfigs_upsertBlankNameFallsBackToProvider() = runTest {
        val store = FakeDomainSettingsStore()
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        XRepo.llmConfigs.upsert(savedConfig(id = "cfg-1", model = "deepseek-chat").copy(name = " "))

        assertEquals("deepseek", XRepo.llmConfigs.active()?.name)
    }

    @Test
    fun activeSavedConfigReadsAndWritesOnlyLlmConfigsStore() = runTest {
        val store = FakeDomainSettingsStore()
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        assertNull(XRepo.llmConfigs.active())
        XRepo.llmConfigs.upsert(savedConfig(id = "cfg-1", model = "gpt-test"))

        assertEquals(
            listOf(StoreDescriptorRegistry.LLM_CONFIGS_ID),
            store.writeIds.distinct(),
        )
        assertEquals("cfg-1", XRepo.llmConfigs.active()?.id)
        assertEquals("gpt-test", XRepo.llmConfigs.active()?.model)
    }

    private fun savedConfig(
        id: String,
        model: String,
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

    @Test
    fun agentConfigReadsAndWritesOnlyExistingEnabledAgents() = runTest {
        val enabledAgent = AgentProfile(
            id = "agent_a",
            name = "Agent A",
            alias = "agent_a",
            enabled = true,
            memoryMode = AgentMemoryMode.SharedMain,
        )
        val disabledAgent = AgentProfile(
            id = "agent_b",
            name = "Agent B",
            alias = "agent_b",
            enabled = false,
            memoryMode = AgentMemoryMode.SharedMain,
        )
        val store = FakeDomainSettingsStore(
            StoreDescriptorRegistry.AGENT_REGISTRY_ID to AgentSettingsCodec.encodeRegistry(
                listOf(enabledAgent, disabledAgent)
            ),
            StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID to MemorySettingsCodec.encodeMemories(
                listOf("Fact"),
                nowMillis = 1L,
            ),
        )
        XRepo.installStoreForTest(store)
        XRepo.init(context)
        assertEquals(listOf("Fact"), XRepo.agents.memoriesFor("agent_a"))
        assertEquals(emptyList<String>(), XRepo.agents.memoriesFor("agent_b"))
    }

    @Test
    fun memoryWritesOnlyAgentMainMemoryStore() = runTest {
        val store = FakeDomainSettingsStore()
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        XRepo.memory.replaceAll(listOf(" A ", " ", "B"))

        assertEquals(listOf(StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID), store.writeIds)
        assertEquals(
            listOf("A", "B"), MemorySettingsCodec.parseMemories(
                store.jsonFor(
                    StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID
                )
            )
        )
    }

    @Test
    fun mcpSaveRecoversBrokenServersJsonAndKeepsDottedName() = runTest {
        val store = FakeDomainSettingsStore(
            StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID to """{"servers":"""
        )
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        XRepo.mcp.save(McpServer("mcp.example.com", "https://mcp.example.com/mcp"))

        assertEquals(listOf(StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID), store.writeIds)
        assertEquals(
            listOf(McpServer("mcp.example.com", "https://mcp.example.com/mcp")),
            McpSettingsCodec.parseServers(store.jsonFor(StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID)),
        )
    }

    @Test
    fun builtinGroupWriteThroughTogglesAllMembersAtomically() = runTest {
        val store = FakeDomainSettingsStore()
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        val validation = XRepo.builtinTools.setGroupEnabled("screen_operation", false)

        assertNull(validation)
        assertEquals(1, store.writeIds.count { it == StoreDescriptorRegistry.TOOLS_BUILTIN_ID })
        val settings = XRepo.builtinTools.list().associateBy { it.name }
        assertFalse(settings.getValue("screen_operation_accessibility").enabled)
        assertFalse(settings.getValue("screen_operation_shell").enabled)
        assertTrue(settings.getValue("terminal").enabled)

        // 未知的组返回校验错误且不写盘
        val unknown = XRepo.builtinTools.setGroupEnabled("no_such_group", true)
        assertEquals(1, store.writeIds.count { it == StoreDescriptorRegistry.TOOLS_BUILTIN_ID })
        assertEquals("groupId", unknown!!.field)
    }

    @Test
    fun builtinGroupsReferenceRegisteredToolsWithoutOverlap() {
        val registryNames = com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry.default()
            .all().map { it.name }.toSet()
        val groupedNames = BuiltinToolGroups.all.flatMap { it.members }

        assertEquals(groupedNames.size, groupedNames.toSet().size)
        assertTrue(groupedNames.all { it in registryNames })
    }

    @Test
    fun builtinV2UnknownToolFlagDoesNotLeakToRegisteredTools() = runTest {
        val store = FakeDomainSettingsStore(
            StoreDescriptorRegistry.TOOLS_BUILTIN_ID to ToolSettingsCodec.encodeBuiltinEnabled(
                mapOf("ghost_tool" to false)
            )
        )
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        val launchApp = XRepo.builtinTools.list().first { it.name == "launch_app" }

        assertTrue(launchApp.enabled)
        assertTrue(store.writeIds.isEmpty())
    }

    @Test
    fun builtinV2ExplicitDisabledFlagIsHonored() = runTest {
        val store = FakeDomainSettingsStore(
            StoreDescriptorRegistry.TOOLS_BUILTIN_ID to ToolSettingsCodec.encodeBuiltinEnabled(
                mapOf("launch_app" to false)
            )
        )
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        val launchApp = XRepo.builtinTools.list().first { it.name == "launch_app" }

        assertFalse(launchApp.enabled)
        assertTrue(store.writeIds.isEmpty())
    }

    @Test
    fun onboardingCompletedWritesOnlyAppStateStore() = runTest {
        val store = FakeDomainSettingsStore()
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        XRepo.setOnboardingCompleted(true)

        assertEquals(listOf(StoreDescriptorRegistry.APP_STATE_ID), store.writeIds)
        val root =
            Json.parseToJsonElement(store.jsonFor(StoreDescriptorRegistry.APP_STATE_ID)).jsonObject
        assertEquals("true", root["onboarding_completed"]!!.jsonPrimitive.content)
    }

    @Test
    fun executionRulesCanBeExplicitlyClearedWithoutDefaultRulesReturning() = runTest {
        val store = FakeDomainSettingsStore(
            StoreDescriptorRegistry.RULES_EXECUTION_ID to RuleSettingsCodec.encodeExecutionRules(
                LocalSettingsDefaults.defaultExecutionRules
            )
        )
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        LocalSettingsDefaults.defaultExecutionRules.forEach { rule ->
            XRepo.executionRules.delete(rule.id)
        }

        assertEquals(emptyList<Any>(), XRepo.executionRules.list())
    }

    private class FakeDomainSettingsStore(
        vararg initialJson: Pair<String, String>,
        private val ownerWriteSucceeds: Boolean = true,
    ) : DomainSettingsStore {
        private val jsonByStoreId = initialJson.toMap().toMutableMap()
        val readIds = mutableListOf<String>()
        val writeIds = mutableListOf<String>()
        val mutateCalls = mutableListOf<Pair<String, String>>()

        override suspend fun readJson(context: Context, storeId: String): String {
            readIds += storeId
            return jsonByStoreId[storeId]
                ?: StoreDescriptorRegistry.resolveDynamic(storeId)?.defaultJson
                ?: "{}"
        }

        override suspend fun writeJsonFromOwner(
            context: Context,
            storeId: String,
            json: String
        ): Boolean {
            if (!ownerWriteSucceeds) {
                return false
            }
            writeIds += storeId
            jsonByStoreId[storeId] = json
            return true
        }

        override suspend fun mutateJson(
            context: Context,
            storeId: String,
            path: String,
            value: Any?
        ): Boolean {
            mutateCalls += storeId to path
            val current =
                Json.parseToJsonElement(jsonByStoreId[storeId] ?: "{}").jsonObject.toMutableMap()
            current[path] = value.toJsonElement()
            jsonByStoreId[storeId] = JsonObject(current).toString()
            return true
        }

        fun jsonFor(storeId: String): String {
            return jsonByStoreId[storeId] ?: error("Missing json for $storeId")
        }

        private fun Any?.toJsonElement(): JsonElement {
            return when (this) {
                null -> JsonNull
                is Boolean -> JsonPrimitive(this)
                is Int -> JsonPrimitive(this)
                is Long -> JsonPrimitive(this)
                is Float -> JsonPrimitive(this)
                is Double -> JsonPrimitive(this)
                is String -> JsonPrimitive(this)
                is Map<*, *> -> JsonObject(mapNotNull { (key, value) ->
                    key?.toString()?.let { it to value.toJsonElement() }
                }.toMap())

                is Iterable<*> -> JsonArray(map { item -> item.toJsonElement() })
                is Array<*> -> JsonArray(map { item -> item.toJsonElement() })
                else -> JsonPrimitive(toString())
            }
        }
    }
}
