package com.niki914.nexus.agentic.repo

import com.niki914.nexus.agentic.runtime.settings.MemoryMutationResult
import com.niki914.nexus.agentic.runtime.settings.RuntimeSettingsGateway
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeBuiltinToolSetting
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomToolValidation
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLoadedSkill
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeSkillMetadata

class XRepoRuntimeGateway(
    private val repo: XRepo = XRepo,
) : RuntimeSettingsGateway {
    override suspend fun readLlmConfig(agentId: String): RuntimeLlmConfig {
        val llm = repo.agents.llm(agentId)
        val memories = repo.agents.memoriesFor(agentId)
        return llm.copy(memories = memories)
    }

    override suspend fun listMcpServers(): List<RuntimeMcpServer> = repo.mcp.list()

    override suspend fun listEnabledSkills(): List<RuntimeSkillMetadata> {
        return repo.skills.listEnabled()
    }

    override suspend fun loadSkill(id: String): RuntimeLoadedSkill? {
        return repo.skills.getDetail(id)
    }

    override suspend fun addMemory(value: String) {
        repo.memory.add(value)
    }

    override suspend fun removeMemory(oldText: String): MemoryMutationResult {
        return repo.memory.removeByText(oldText)
    }

    override suspend fun replaceMemory(oldText: String, content: String): MemoryMutationResult {
        return repo.memory.replaceByText(oldText, content)
    }

    override suspend fun listCustomTools(): List<RuntimeCustomTool> = repo.customTools.list()

    override suspend fun saveCustomTool(
        tool: RuntimeCustomTool,
        overwrite: Boolean,
    ): RuntimeCustomToolValidation? {
        return repo.customTools.save(tool, overwrite)
    }

    override suspend fun replaceAllCustomTools(
        tools: List<RuntimeCustomTool>,
    ): RuntimeCustomToolValidation? {
        return repo.customTools.replaceAll(tools)
    }

    override suspend fun deleteCustomTool(name: String) {
        repo.customTools.delete(name)
    }

    override suspend fun setCustomToolEnabled(name: String, enabled: Boolean) {
        repo.customTools.setEnabled(name, enabled)
    }

    override suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> {
        return repo.builtinTools.list()
    }

    override suspend fun setBuiltinToolEnabled(
        name: String,
        enabled: Boolean,
    ): RuntimeCustomToolValidation? {
        return repo.builtinTools.setEnabled(name, enabled)
    }

    override suspend fun listExecutionRules(): List<RuntimeExecutionRule> {
        return repo.executionRules.list()
    }
}
