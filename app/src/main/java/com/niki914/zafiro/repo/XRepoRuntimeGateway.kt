package com.niki914.zafiro.repo

import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.RuntimeSettingsGateway
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool
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
        val doc = repo.llmConfigs.document()
        val active = doc.activeConfig()
        val memories = repo.agents.memoriesFor(agentId)
        return RuntimeLlmConfig(
            provider = active?.provider.orEmpty(),
            endpoint = active?.endpoint.orEmpty(),
            apiKey = active?.apiKey.orEmpty(),
            model = active?.model.orEmpty(),
            protocol = active?.protocol.orEmpty(),
            proxy = active?.proxy.orEmpty(),
            prompt = doc.prompt,
            memories = memories,
        )
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

    override suspend fun listCustomPyTools(): List<RuntimeCustomPyTool> = repo.customPyTools.list()

    override suspend fun saveCustomPyTool(
        tool: RuntimeCustomPyTool,
        overwrite: Boolean,
    ): RuntimeToolValidation? {
        return repo.customPyTools.save(tool, overwrite)
    }

    override suspend fun deleteCustomPyTool(name: String) {
        repo.customPyTools.delete(name)
    }

    override suspend fun setCustomPyToolEnabled(name: String, enabled: Boolean) {
        repo.customPyTools.setEnabled(name, enabled)
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
