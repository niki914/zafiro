package com.niki914.nexus.agentic.runtime.settings

import com.niki914.nexus.agentic.runtime.settings.MemoryMutationResult
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeBuiltinToolSetting
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomTool
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeCustomToolValidation
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRule
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLlmConfig
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLoadedSkill
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeMcpServer
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeSkillMetadata
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.Proxy

class RuntimeEnvironmentTest {
    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
    }

    @Test
    fun awaitSettingsGateway_waitsForDelayedBridgeInstall() = runTest {
        val gateway = FakeRuntimeSettingsGateway()
        val awaiting = async { RuntimeEnvironment.awaitSettingsGateway() }

        installBridge(gateway)

        assertSame(gateway, awaiting.await())
    }

    @Test
    fun requireBridge_returnsInstalledBridge() {
        val gateway = FakeRuntimeSettingsGateway()
        val bridge = createRuntimeBridge(gateway)

        installBridge(bridge)

        val requireBridge = RuntimeEnvironment::class.java.getMethod("requireBridge")
        val requiredBridge = requireBridge.invoke(RuntimeEnvironment)
        assertSame(bridge, requiredBridge)
    }
}

private fun installBridge(settingsGateway: RuntimeSettingsGateway) {
    installBridge(createRuntimeBridge(settingsGateway))
}

private fun installBridge(bridge: Any) {
    val install = RuntimeEnvironment::class.java.getMethod("install", bridge.javaClass)
    install.invoke(RuntimeEnvironment, bridge)
}

private fun createRuntimeBridge(settingsGateway: RuntimeSettingsGateway): Any {
    val hostGatewayClass = Class.forName(
        "com.niki914.nexus.agentic.runtime.settings.RuntimeHostGateway"
    )
    val runtimeBridgeClass = Class.forName(
        "com.niki914.nexus.agentic.runtime.settings.RuntimeBridge"
    )
    val hostGateway = Proxy.newProxyInstance(
        hostGatewayClass.classLoader,
        arrayOf(hostGatewayClass),
    ) { _, _, _ -> false }
    return runtimeBridgeClass
        .getConstructor(RuntimeSettingsGateway::class.java, hostGatewayClass)
        .newInstance(settingsGateway, hostGateway)
}

private class FakeRuntimeSettingsGateway : RuntimeSettingsGateway {
    override suspend fun readLlmConfig(agentId: String): RuntimeLlmConfig = RuntimeLlmConfig()

    override suspend fun listEnabledSkills(): List<RuntimeSkillMetadata> = emptyList()

    override suspend fun loadSkill(id: String): RuntimeLoadedSkill? = null

    override suspend fun listMcpServers(): List<RuntimeMcpServer> = emptyList()

    override suspend fun addMemory(value: String) = Unit

    override suspend fun removeMemory(oldText: String): MemoryMutationResult =
        MemoryMutationResult.Ok

    override suspend fun replaceMemory(oldText: String, content: String): MemoryMutationResult =
        MemoryMutationResult.Ok

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
