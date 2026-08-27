package com.niki914.zafiro.repo

import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.RuntimeSettingsGateway
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimePyTool
import com.niki914.zafiro.settings.model.RuntimeToolValidation
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.settings.model.RuntimeLoadedSkill
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata

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

    override suspend fun listPyTools(): List<RuntimePyTool> = repo.pyTools.list()

    override suspend fun savePyTool(
        tool: RuntimePyTool,
        overwrite: Boolean,
    ): RuntimeToolValidation? {
        return repo.pyTools.save(tool, overwrite)
    }

    override suspend fun deletePyTool(name: String) {
        repo.pyTools.delete(name)
    }

    override suspend fun setPyToolEnabled(name: String, enabled: Boolean) {
        repo.pyTools.setEnabled(name, enabled)
    }

    override suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> {
        return repo.builtinTools.list()
    }

    override suspend fun setBuiltinToolEnabled(
        name: String,
        enabled: Boolean,
    ): RuntimeToolValidation? {
        return repo.builtinTools.setEnabled(name, enabled)
    }

    override suspend fun setBuiltinToolGroupEnabled(
        groupId: String,
        enabled: Boolean,
    ): RuntimeToolValidation? {
        return repo.builtinTools.setGroupEnabled(groupId, enabled)
    }

    override suspend fun listExecutionRules(): List<RuntimeExecutionRule> {
        return repo.executionRules.list()
    }
}
