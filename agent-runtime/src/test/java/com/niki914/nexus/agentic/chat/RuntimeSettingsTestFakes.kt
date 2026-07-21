package com.niki914.nexus.agentic.chat

import com.niki914.nexus.agentic.runtime.settings.RuntimeBridge
import com.niki914.nexus.agentic.runtime.settings.RuntimeEnvironment
import com.niki914.nexus.agentic.runtime.settings.RuntimeHostGateway
import com.niki914.nexus.agentic.runtime.settings.RuntimeSettingsGateway
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeBuiltinToolSetting
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomToolValidation
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLoadedSkill
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeSkillMetadata

internal fun installRuntimeSettingsGatewayForTest(
    gateway: FakeRuntimeSettingsGateway = FakeRuntimeSettingsGateway(),
): FakeRuntimeSettingsGateway {
    RuntimeEnvironment.install(
        RuntimeBridge(
            settings = gateway,
            host = FakeRuntimeHostGateway,
        )
    )
    return gateway
}

internal class FakeRuntimeSettingsGateway(
    private val llmConfig: RuntimeLlmConfig = RuntimeLlmConfig(),
    customTools: List<RuntimeCustomTool> = emptyList(),
    builtinTools: List<RuntimeBuiltinToolSetting> = defaultBuiltinToolSettings(),
    memories: List<String> = emptyList(),
    executionRules: List<RuntimeExecutionRule> = emptyList(),
    private val enabledSkills: List<RuntimeSkillMetadata> = emptyList(),
    private val loadedSkills: Map<String, RuntimeLoadedSkill> = emptyMap(),
    private val mcpServers: List<RuntimeMcpServer> = emptyList(),
) : RuntimeSettingsGateway {
    var customTools: MutableList<RuntimeCustomTool> = customTools.toMutableList()
        private set
    var builtinTools: MutableList<RuntimeBuiltinToolSetting> = builtinTools.toMutableList()
        private set
    var memories: MutableList<String> = memories.toMutableList()
        private set
    var executionRules: MutableList<RuntimeExecutionRule> = executionRules.toMutableList()
        private set
    var writeCount: Int = 0
        private set
    var failOnWriteNumber: Int? = null
    var nextSaveCustomToolValidation: RuntimeCustomToolValidation? = null
    var listEnabledSkillsCallCount: Int = 0
        private set
    var loadSkillCallCount: Int = 0
        private set
    var failListEnabledSkills: Throwable? = null
    var failLoadSkill: Throwable? = null
    val discoveredToolWrites: MutableList<RuntimeMcpCacheWrite> = mutableListOf()

    override suspend fun readLlmConfig(agentId: String): RuntimeLlmConfig = llmConfig

    override suspend fun listEnabledSkills(): List<RuntimeSkillMetadata> {
        listEnabledSkillsCallCount++
        failListEnabledSkills?.let { throw it }
        return enabledSkills
    }

    override suspend fun loadSkill(id: String): RuntimeLoadedSkill? {
        loadSkillCallCount++
        failLoadSkill?.let { throw it }
        return loadedSkills[id]
    }

    override suspend fun listMcpServers(): List<RuntimeMcpServer> = mcpServers

    override suspend fun listCachedTools(server: RuntimeMcpServer): List<RuntimeMcpTool> {
        return discoveredToolWrites
            .lastOrNull { write -> write.url == server.url && write.headers == server.headers }
            ?.tools
            .orEmpty()
    }

    override suspend fun saveDiscoveredTools(
        url: String,
        headers: Map<String, String>,
        tools: List<RuntimeMcpTool>,
    ) {
        recordWrite()
        discoveredToolWrites.add(RuntimeMcpCacheWrite(url, headers, tools))
    }

    override suspend fun clearMcpCacheByServerNames(names: Set<String>) = Unit

    override suspend fun fingerprintMcpServers(): String = ""

    override suspend fun addMemory(value: String) {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            return
        }
        recordWrite()
        memories.add(normalized)
    }

    override suspend fun listCustomTools(): List<RuntimeCustomTool> = customTools.toList()

    override suspend fun saveCustomTool(
        tool: RuntimeCustomTool,
        overwrite: Boolean,
    ): RuntimeCustomToolValidation? {
        nextSaveCustomToolValidation?.let { validation ->
            nextSaveCustomToolValidation = null
            return validation
        }
        val index = customTools.indexOfFirst { it.name == tool.name }
        if (index >= 0 && !overwrite) {
            return RuntimeCustomToolValidation("name", "Already exists in custom_tools.")
        }
        recordWrite()
        if (index >= 0) {
            customTools[index] = tool
        } else {
            customTools.add(tool)
        }
        return null
    }

    override suspend fun replaceAllCustomTools(
        tools: List<RuntimeCustomTool>,
    ): RuntimeCustomToolValidation? {
        recordWrite()
        customTools = tools.toMutableList()
        return null
    }

    override suspend fun deleteCustomTool(name: String) {
        recordWrite()
        customTools.removeAll { it.name == name }
    }

    override suspend fun setCustomToolEnabled(name: String, enabled: Boolean) {
        recordWrite()
        customTools = customTools
            .map { if (it.name == name) it.copy(enabled = enabled) else it }
            .toMutableList()
    }

    override suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> {
        return builtinTools.toList()
    }

    override suspend fun setBuiltinToolEnabled(
        name: String,
        enabled: Boolean,
    ): RuntimeCustomToolValidation? {
        val index = builtinTools.indexOfFirst { it.name == name }
        if (index < 0) {
            return RuntimeCustomToolValidation("name", "Unknown builtin tool.")
        }
        recordWrite()
        builtinTools[index] = builtinTools[index].copy(enabled = enabled)
        return null
    }

    override suspend fun listExecutionRules(): List<RuntimeExecutionRule> {
        return executionRules.toList()
    }

    private fun recordWrite() {
        if (failOnWriteNumber == writeCount + 1) {
            throw IllegalStateException("write failed")
        }
        writeCount++
    }
}

internal data class RuntimeMcpCacheWrite(
    val url: String,
    val headers: Map<String, String>,
    val tools: List<RuntimeMcpTool>,
)

private object FakeRuntimeHostGateway : RuntimeHostGateway {
    override suspend fun postNotification(title: String, content: String, uri: String?): Boolean =
        false
}

private fun defaultBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> {
    return listOf(
        RuntimeBuiltinToolSetting("create_custom_tool", "Create custom tools.", enabled = true),
        RuntimeBuiltinToolSetting("memorize", "Add a memory item.", enabled = true),
        RuntimeBuiltinToolSetting("notify", "Post host notifications.", enabled = true),
        RuntimeBuiltinToolSetting(
            "read_custom_tool",
            "Read custom tool implementations.",
            enabled = true
        ),
        RuntimeBuiltinToolSetting("terminal", "Manage Android terminal sessions.", enabled = true),
    )
}
