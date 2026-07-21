package com.niki914.nexus.agentic.runtime.settings

import com.niki914.nexus.agentic.runtime.settings.model.RuntimeBuiltinToolSetting
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomToolValidation
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeSkillMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeSettingsGatewaySkillContractTest {
    @Test
    fun defaultSkillContract_returnsNoEnabledSkillsAndNoLoadedSkill() = runTest {
        val gateway = MinimalRuntimeSettingsGateway()

        assertEquals(emptyList<RuntimeSkillMetadata>(), gateway.listEnabledSkills())
        assertNull(gateway.loadSkill("missing"))
    }
}

private class MinimalRuntimeSettingsGateway : RuntimeSettingsGateway {
    override suspend fun readLlmConfig(agentId: String): RuntimeLlmConfig = RuntimeLlmConfig()

    override suspend fun listMcpServers(): List<RuntimeMcpServer> = emptyList()

    override suspend fun listCachedTools(server: RuntimeMcpServer): List<RuntimeMcpTool> =
        emptyList()

    override suspend fun saveDiscoveredTools(
        url: String,
        headers: Map<String, String>,
        tools: List<RuntimeMcpTool>,
    ) = Unit

    override suspend fun clearMcpCacheByServerNames(names: Set<String>) = Unit

    override suspend fun fingerprintMcpServers(): String = ""

    override suspend fun addMemory(value: String) = Unit

    override suspend fun listCustomTools(): List<RuntimeCustomTool> = emptyList()

    override suspend fun saveCustomTool(
        tool: RuntimeCustomTool,
        overwrite: Boolean,
    ): RuntimeCustomToolValidation? = null

    override suspend fun replaceAllCustomTools(
        tools: List<RuntimeCustomTool>,
    ): RuntimeCustomToolValidation? = null

    override suspend fun deleteCustomTool(name: String) = Unit

    override suspend fun setCustomToolEnabled(name: String, enabled: Boolean) = Unit

    override suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> = emptyList()

    override suspend fun setBuiltinToolEnabled(
        name: String,
        enabled: Boolean,
    ): RuntimeCustomToolValidation? = null

    override suspend fun listExecutionRules(): List<RuntimeExecutionRule> = emptyList()
}
