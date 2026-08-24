package com.niki914.nexus.agentic.runtime.settings

import com.niki914.nexus.agentic.runtime.settings.model.RuntimeBuiltinToolSetting
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomToolValidation
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLoadedSkill
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeSkillMetadata

interface RuntimeSettingsGateway {
    suspend fun readLlmConfig(agentId: String = "main"): RuntimeLlmConfig

    suspend fun listEnabledSkills(): List<RuntimeSkillMetadata> = emptyList()

    suspend fun loadSkill(id: String): RuntimeLoadedSkill? = null

    suspend fun listMcpServers(): List<RuntimeMcpServer>

    suspend fun addMemory(value: String)

    suspend fun removeMemory(oldText: String): MemoryMutationResult

    suspend fun replaceMemory(oldText: String, content: String): MemoryMutationResult

    suspend fun listCustomTools(): List<RuntimeCustomTool>

    suspend fun saveCustomTool(
        tool: RuntimeCustomTool,
        overwrite: Boolean = true,
    ): RuntimeCustomToolValidation?

    suspend fun replaceAllCustomTools(
        tools: List<RuntimeCustomTool>,
    ): RuntimeCustomToolValidation?

    suspend fun deleteCustomTool(name: String)

    suspend fun setCustomToolEnabled(name: String, enabled: Boolean)

    suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting>

    suspend fun setBuiltinToolEnabled(
        name: String,
        enabled: Boolean,
    ): RuntimeCustomToolValidation?

    suspend fun listExecutionRules(): List<RuntimeExecutionRule>
}
