package com.niki914.nexus.agentic.repo

import android.content.Context
import android.content.ContextWrapper
import com.niki914.nexus.store.StoreDescriptorRegistry
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeAgentMemoryMode as AgentMemoryMode
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeAgentProfile as AgentProfile
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig as LlmConfig
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer as McpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpTool as McpTool

class XRepoDomainSettingsTest {
    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName(): String = "com.niki914.nexus.agentic"
    }

    @After
    fun tearDown() {
        XRepo.resetForTest()
    }

    @Test
    fun llmReadsAndWritesOnlyAgentMainConfigStore() = runTest {
        val store = FakeDomainSettingsStore(
            StoreDescriptorRegistry.AGENT_MAIN_CONFIG_ID to AgentSettingsCodec.encodeMainConfig(
                LlmConfig(provider = "old", model = "old-model")
            )
        )
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        assertEquals("old-model", XRepo.llm().model)
        XRepo.saveLlm(LlmConfig(provider = "openai", model = "gpt-test"))

        assertEquals(listOf(StoreDescriptorRegistry.AGENT_MAIN_CONFIG_ID), store.readIds)
        assertEquals(listOf(StoreDescriptorRegistry.AGENT_MAIN_CONFIG_ID), store.writeIds)
        assertEquals(
            "gpt-test", AgentSettingsCodec.parseMainConfig(
                store.jsonFor(
                    StoreDescriptorRegistry.AGENT_MAIN_CONFIG_ID
                )
            ).model
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

        assertEquals(null, XRepo.agents.saveLlm("agent_a", LlmConfig(model = "model-a")))
        assertEquals("model-a", XRepo.agents.llm("agent_a").model)
        assertEquals(listOf("Fact"), XRepo.agents.memoriesFor("agent_a"))

        val missingValidation = XRepo.agents.saveLlm("ghost_agent", LlmConfig(model = "ghost"))
        assertEquals("id", missingValidation?.field)
        assertEquals("", XRepo.agents.llm("ghost_agent").model)

        val disabledValidation = XRepo.agents.saveLlm("agent_b", LlmConfig(model = "model-b"))
        assertEquals("enabled", disabledValidation?.field)
        assertEquals("", XRepo.agents.llm("agent_b").model)
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
    fun mcpCacheWritesOnlyMatchingServerCacheStore() = runTest {
        val filesystem = McpServer("FileSystem", "http://127.0.0.1:3000/mcp")
        val weather = McpServer("Weather", "http://127.0.0.1:3001/mcp")
        val store = FakeDomainSettingsStore(
            StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID to McpSettingsCodec.encodeServers(
                listOf(filesystem, weather)
            )
        )
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        XRepo.mcp.saveDiscoveredTools(
            url = filesystem.url,
            headers = filesystem.headers,
            tools = listOf(McpTool("read_file", "Read", """{"type":"object"}""")),
        )

        val cacheStoreId = StoreDescriptorRegistry.mcpCacheStoreId("filesystem")!!
        assertEquals(listOf(cacheStoreId), store.writeIds)
        assertEquals(
            listOf(McpTool("read_file", "Read", """{"type":"object"}""")),
            McpSettingsCodec.parseCache(store.jsonFor(cacheStoreId)),
        )
        assertFalse(store.writeIds.contains(StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID))
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
    fun builtinGhostAgentReferenceDoesNotCrashAndReturnsDisabledForMain() = runTest {
        val store = FakeDomainSettingsStore(
            StoreDescriptorRegistry.TOOLS_BUILTIN_ID to """
                {
                  "enabled_for_agents": {
                    "launch_app": ["ghost"]
                  }
                }
            """.trimIndent()
        )
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        val launchApp = XRepo.builtinTools.list().first { it.name == "launch_app" }

        assertFalse(launchApp.enabled)
        assertTrue(store.writeIds.isEmpty())
    }

    @Test
    fun builtinTerminalInheritsLegacyRunCommandDisabledFlag() = runTest {
        val store = FakeDomainSettingsStore(
            StoreDescriptorRegistry.TOOLS_BUILTIN_ID to """
                {
                  "enabled_for_agents": {
                    "run_command": []
                  }
                }
            """.trimIndent()
        )
        XRepo.installStoreForTest(store)
        XRepo.init(context)

        val terminal = XRepo.builtinTools.list().first { it.name == "terminal" }

        assertFalse(terminal.enabled)
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
