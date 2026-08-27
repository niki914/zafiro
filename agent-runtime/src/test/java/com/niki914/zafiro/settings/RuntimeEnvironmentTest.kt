package com.niki914.zafiro.settings

import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimePyTool
import com.niki914.zafiro.settings.model.RuntimeToolValidation
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.settings.model.RuntimeLoadedSkill
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata
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
        "com.niki914.zafiro.settings.RuntimeHostGateway"
    )
    val runtimeBridgeClass = Class.forName(
        "com.niki914.zafiro.settings.RuntimeBridge"
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

    override suspend fun listPyTools(): List<RuntimePyTool> = emptyList()

    override suspend fun savePyTool(
        tool: RuntimePyTool,
        overwrite: Boolean,
    ): RuntimeToolValidation? = null

    override suspend fun deletePyTool(name: String) = Unit

    override suspend fun setPyToolEnabled(name: String, enabled: Boolean) = Unit

    override suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> = emptyList()

    override suspend fun setBuiltinToolEnabled(
        name: String,
        enabled: Boolean,
    ): RuntimeToolValidation? = null

    override suspend fun setBuiltinToolGroupEnabled(
        groupId: String,
        enabled: Boolean,
    ): RuntimeToolValidation? = null

    override suspend fun listExecutionRules(): List<RuntimeExecutionRule> = emptyList()
}
