// 保护：AgentManager 容器多会话复用、动态 main 切换、新会话隔离与生命周期回调
package com.niki914.zafiro.business.agent

import com.niki914.logging.Backend
import com.niki914.logging.Level
import com.niki914.logging.Logger
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.service.ServiceRegistry
import com.niki914.zafiro.service.installService
import com.niki914.zafiro.settings.MemoryMutationResult
import com.niki914.zafiro.settings.RuntimeBridge
import com.niki914.zafiro.settings.RuntimeEnvironment
import com.niki914.zafiro.settings.RuntimeHostGateway
import com.niki914.zafiro.settings.RuntimeSettingsGateway
import com.niki914.zafiro.settings.model.RuntimeBuiltinToolSetting
import com.niki914.zafiro.settings.model.RuntimeCustomPyTool
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeLlmConfig
import com.niki914.zafiro.settings.model.RuntimeMcpServer
import com.niki914.zafiro.settings.model.RuntimeToolValidation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentManagerImplTest {

    @Before
    fun setUp() {
        Logger.install(object : Backend {
            override fun emit(level: Level, tag: String, msg: String, throwable: Throwable?) = Unit
        })
        val fakeStore = object : ConversationStore_Tmp {
            val sessions = mutableMapOf<String, StoredConversation_Tmp>()
            override suspend fun load(id: ConversationId): StoredConversation_Tmp? =
                sessions[id.value] ?: StoredConversation_Tmp(
                    snapshot = SessionSnapshot(
                        id = id.value,
                        leafId = null,
                        version = 1,
                        entries = emptyList()
                    ),
                    conversation = Conversation(id = id),
                )

            override suspend fun exists(id: ConversationId): Boolean = true
            override suspend fun create(
                id: ConversationId,
                firstUserInput: String,
                configId: String
            ) {
            }

            override suspend fun setLastOpened(id: ConversationId?) {}
        }
        val fakeGateway = object : RuntimeSettingsGateway {
            override suspend fun readLlmConfig(
                agentId: String,
                configId: String?
            ): RuntimeLlmConfig =
                RuntimeLlmConfig(endpoint = "https://api.openai.com", model = "gpt-4o")

            override suspend fun listMcpServers(): List<RuntimeMcpServer> = emptyList()
            override suspend fun addMemory(value: String) = Unit
            override suspend fun removeMemory(oldText: String): MemoryMutationResult =
                MemoryMutationResult.Ok

            override suspend fun replaceMemory(
                oldText: String,
                content: String
            ): MemoryMutationResult = MemoryMutationResult.Ok

            override suspend fun listCustomPyTools(): List<RuntimeCustomPyTool> = emptyList()
            override suspend fun saveCustomPyTool(
                tool: RuntimeCustomPyTool,
                overwrite: Boolean
            ): RuntimeToolValidation? = null

            override suspend fun deleteCustomPyTool(name: String) = Unit
            override suspend fun setCustomPyToolEnabled(name: String, enabled: Boolean) = Unit
            override suspend fun listBuiltinToolSettings(): List<RuntimeBuiltinToolSetting> =
                emptyList()

            override suspend fun setBuiltinToolEnabled(
                name: String,
                enabled: Boolean
            ): RuntimeToolValidation? = null

            override suspend fun setBuiltinToolGroupEnabled(
                groupId: String,
                enabled: Boolean
            ): RuntimeToolValidation? = null

            override suspend fun listExecutionRules(): List<RuntimeExecutionRule> = emptyList()
        }
        val fakeHost = object : RuntimeHostGateway {
            override suspend fun postNotification(
                title: String,
                content: String,
                uri: String?
            ): Boolean = true
        }
        installService<ConversationStore_Tmp>(fakeStore)
        RuntimeEnvironment.install(RuntimeBridge(settings = fakeGateway, host = fakeHost))
    }

    @After
    fun tearDown() {
        RuntimeEnvironment.clearForTest()
        ServiceRegistry.clearForTest()
    }

    @Test
    fun switchTo_reusesExistingActiveAgentWithoutReloading() = runBlocking {
        val manager = AgentManagerImpl()
        val firstSwitch = manager.switchTo("conv-1")
        assertSame(firstSwitch, manager.main)
        assertEquals("conv-1", manager.main.conversation.value.id?.value)

        val secondSwitch = manager.switchTo("conv-1")
        assertSame(firstSwitch, secondSwitch)
    }

    @Test
    fun startNewConversation_switchesMainWithoutDiscardingActiveAgent() = runBlocking {
        val manager = AgentManagerImpl()
        val agent1 = manager.switchTo("conv-1")
        assertEquals(agent1, manager.main)

        val scratchAgent = manager.startNewConversation()
        assertSame(scratchAgent, manager.main)
        assertNotSame(agent1, scratchAgent)
        // 旧 Agent 依然完整保存在 activeAgents 中，再次 switchTo 仍是原实例
        val switchedBack = manager.switchTo("conv-1")
        assertSame(agent1, switchedBack)
    }

    @Test
    fun startNewConversation_unconditionallyCreatesNewAgent_evenWhenScratchHasNoId() = runBlocking {
        val manager = AgentManagerImpl()
        val firstScratch = manager.main
        assertNull(firstScratch.conversation.value.id)

        val secondScratch = manager.startNewConversation()
        assertNotSame(firstScratch, secondScratch)
        assertSame(secondScratch, manager.main)
    }

    @Test
    fun activeStates_tracksActiveConversationsDynamically() = runBlocking {
        val manager = AgentManagerImpl()
        manager.switchTo("conv-1")
        delay(50)
        assertTrue(manager.activeStates.value.containsKey("conv-1"))
    }

    @Test
    fun destroy_removesAgentFromContainerAndForcesNewInstanceOnNextSwitch() = runBlocking {
        val manager = AgentManagerImpl()
        val agent1 = manager.switchTo("conv-1")
        assertSame(agent1, manager.main)

        manager.destroy("conv-1")
        assertFalse(manager.activeStates.value.containsKey("conv-1"))

        val agentAfterDestroy = manager.switchTo("conv-1")
        assertNotSame(agent1, agentAfterDestroy)
    }

    @Test
    fun createTaskAgent_persistentRegistersIntoActiveStatesAndCanBeSwitchedTo() = runBlocking {
        val manager = AgentManagerImpl()
        val taskAgent = manager.createTaskAgent(isPersistent = true)
        taskAgent.load(ConversationId("conv-task"))
        delay(50)
        assertTrue(manager.activeStates.value.containsKey("conv-task"))

        val switched = manager.switchTo("conv-task")
        assertSame(taskAgent, switched)
        assertSame(taskAgent, manager.main)
    }

    @Test
    fun createTaskAgent_nonPersistentDoesNotRegisterIntoActiveStates() = runBlocking {
        val manager = AgentManagerImpl()
        val taskAgent = manager.createTaskAgent(isPersistent = false)
        taskAgent.load(ConversationId("conv-transient"))
        delay(50)
        assertFalse(manager.activeStates.value.containsKey("conv-transient"))
    }
}
