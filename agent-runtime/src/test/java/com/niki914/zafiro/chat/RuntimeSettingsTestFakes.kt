package com.niki914.zafiro.chat

import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.RuntimeBridge
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.RuntimeHostGateway
import com.niki914.zafiro.settings.RuntimeSettingsGateway
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool
import com.niki914.zafiro.settings.model.RuntimeToolValidation
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.settings.model.RuntimeLoadedSkill
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata

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
    customPyTools: List<RuntimeCustomPyTool> = emptyList(),
    builtinTools: List<RuntimeBuiltinToolSetting> = defaultBuiltinToolSettings(),
    memories: List<String> = emptyList(),
    executionRules: List<RuntimeExecutionRule> = emptyList(),
    private val enabledSkills: List<RuntimeSkillMetadata> = emptyList(),
    private val loadedSkills: Map<String, RuntimeLoadedSkill> = emptyMap(),
    private val mcpServers: List<RuntimeMcpServer> = emptyList(),
) : RuntimeSettingsGateway {
    var customPyTools: MutableList<RuntimeCustomPyTool> = customPyTools.toMutableList()
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
    var nextSaveCustomPyToolValidation: RuntimeToolValidation? = null
    var listEnabledSkillsCallCount: Int = 0
        private set
    var loadSkillCallCount: Int = 0
        private set
    var failListEnabledSkills: Throwable? = null
    var failLoadSkill: Throwable? = null

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

    override suspend fun addMemory(value: String) {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            return
        }
        recordWrite()
        memories.add(normalized)
    }

    override suspend fun removeMemory(oldText: String): MemoryMutationResult {
        val matches = memories.mapIndexedNotNull { i, entry ->
            if (oldText in entry) i to entry else null
        }
        return when {
            matches.isEmpty() -> MemoryMutationResult.NotFound
            matches.size > 1 && matches.map { it.second }.distinct().size > 1 ->
                MemoryMutationResult.Ambiguous
            else -> {
                recordWrite()
                memories.removeAt(matches.first().first)
                MemoryMutationResult.Ok
            }
        }
    }

    override suspend fun replaceMemory(oldText: String, content: String): MemoryMutationResult {
        val matches = memories.mapIndexedNotNull { i, entry ->
            if (oldText in entry) i to entry else null
        }
        return when {
            matches.isEmpty() -> MemoryMutationResult.NotFound
            matches.size > 1 && matches.map { it.second }.distinct().size > 1 ->
                MemoryMutationResult.Ambiguous
            else -> {
                recordWrite()
                memories[matches.first().first] = content
                MemoryMutationResult.Ok
            }
        }
    }

    override suspend fun listCustomPyTools(): List<RuntimeCustomPyTool> = customPyTools.toList()

    override suspend fun saveCustomPyTool(
        tool: RuntimeCustomPyTool,
        overwrite: Boolean,
    ): RuntimeToolValidation? {
        nextSaveCustomPyToolValidation?.let { validation ->
            nextSaveCustomPyToolValidation = null
            return validation
        }
        val index = customPyTools.indexOfFirst { it.name == tool.name }
        if (index >= 0 && !overwrite) {
            return RuntimeToolValidation("name", "Already exists in custom_py_tools.")
        }
        recordWrite()
        if (index >= 0) {
            customPyTools[index] = tool
        } else {
            customPyTools.add(tool)
        }
        return null
    }

    override suspend fun deleteCustomPyTool(name: String) {
        recordWrite()
        customPyTools.removeAll { it.name == name }
    }

    override suspend fun setCustomPyToolEnabled(name: String, enabled: Boolean) {
        recordWrite()
        customPyTools = customPyTools
            .map { if (it.name == name) it.copy(enabled = enabled) else it }
            .toMutableList()
    }

    override suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> {
        return builtinTools.toList()
    }

    override suspend fun setBuiltinToolEnabled(
        name: String,
        enabled: Boolean,
    ): RuntimeToolValidation? {
        val index = builtinTools.indexOfFirst { it.name == name }
        if (index < 0) {
            return RuntimeToolValidation("name", "Unknown builtin tool.")
        }
        recordWrite()
        builtinTools[index] = builtinTools[index].copy(enabled = enabled)
        return null
    }

    override suspend fun setBuiltinToolGroupEnabled(
        groupId: String,
        enabled: Boolean,
    ): RuntimeToolValidation? {
        recordWrite()
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

private object FakeRuntimeHostGateway : RuntimeHostGateway {
    override suspend fun postNotification(title: String, content: String, uri: String?): Boolean =
        false
}

private fun defaultBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> {
    return listOf(
        RuntimeBuiltinToolSetting("py_meta_tools", "Manage persistent Python tools.", enabled = true),
        RuntimeBuiltinToolSetting("load_skill", "Load a skill by id.", enabled = true),
        RuntimeBuiltinToolSetting("memory", "Add a memory item.", enabled = true),
        RuntimeBuiltinToolSetting("notify", "Post host notifications.", enabled = true),
        RuntimeBuiltinToolSetting("terminal", "Manage Android terminal sessions.", enabled = true),
    )
}
