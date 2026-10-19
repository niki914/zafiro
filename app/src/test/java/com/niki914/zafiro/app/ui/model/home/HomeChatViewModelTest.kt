package com.niki914.zafiro.app.ui.model.home

import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.ContextUsage
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.ConversationTurn
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.AgentManager
import com.niki914.zafiro.app.conversation.ForkKind
import com.niki914.zafiro.app.ui.model.MainDispatcherRule
import com.niki914.zafiro.app.ui.model.TextPacer
import com.niki914.zafiro.business.agent.turnIdAt
import com.niki914.zafiro.repo.LlmConfigsDocument
import com.niki914.zafiro.repo.SavedLlmConfig
import com.niki914.zafiro.service.ServiceRegistry
import com.niki914.zafiro.service.installService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class HomeChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        ServiceRegistry.clearForTest()
    }

    @Test
    fun sendMirrorsTextToAgentAndRendersItsConversation() = runTest {
        val fixture = fixture()
        fixture.vm.sendIntent(HomeChatIntent.InputChanged("  hello  "))
        runCurrent()
        fixture.vm.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        assertEquals("hello", fixture.agent.sentDrafts.single().text)
        assertEquals("", fixture.vm.uiStateFlow.value.input)
        assertEquals("hello", fixture.vm.uiStateFlow.value.turns.single().userText)
        assertEquals(
            listOf(HomeChatBlock.Text("answer")),
            fixture.vm.uiStateFlow.value.turns.single().blocks
        )
        assertFalse(fixture.vm.uiStateFlow.value.isGenerating)
    }

    @Test
    fun sendWithEmptyInputAndNoAttachmentsEmitsEmptyInputEffect() = runTest {
        val fixture = fixture()
        val effectDeferred = async { fixture.vm.uiEffect.first() }
        fixture.vm.sendIntent(HomeChatIntent.InputChanged("   "))
        runCurrent()
        fixture.vm.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        assertEquals(HomeChatEffect.EmptyInputAttempted, effectDeferred.await())
        assertTrue(fixture.agent.sentDrafts.isEmpty())
        assertFalse(fixture.vm.uiStateFlow.value.isGenerating)
    }

    @Test
    fun imageDraftComesFromAgentAndImageOnlySendKeepsAttachment() = runTest {
        val fixture = fixture()
        fixture.vm.sendIntent(HomeChatIntent.ImageAttached("content://image/1"))
        runCurrent()
        val image = fixture.vm.uiStateFlow.value.pendingImages.single()
        assertTrue(image.path.endsWith("image-1.jpg"))
        fixture.vm.sendIntent(HomeChatIntent.Send)
        advanceUntilIdle()

        assertEquals("", fixture.agent.sentDrafts.single().text)
        assertEquals(listOf(image.path), fixture.agent.sentImages.single().map { it.path })
        assertEquals(
            listOf(image.path),
            fixture.vm.uiStateFlow.value.turns.single().images.map { it.path })
    }

    @Test
    fun stopKeepsPartialConversationAndCallsAgentStop() = runTest {
        val fixture = fixture()
        fixture.agent.streamResult = TurnStart.Started
        fixture.vm.sendIntent(HomeChatIntent.InputChanged("question"))
        runCurrent()
        fixture.vm.sendIntent(HomeChatIntent.Send)
        runCurrent()
        fixture.agent.publishConversation(
            Conversation(
                id = ConversationId("session-1"),
                turns = listOf(
                    ConversationTurn(
                        id = turnIdAt(0),
                        userText = "question",
                        blocks = listOf(TurnBlock.Text("t0:0", "partial"))
                    )
                ),
            ),
        )
        fixture.agent.publishStatus(AgentState.Generating(text = null))
        runCurrent()
        assertTrue(fixture.vm.uiStateFlow.value.isGenerating)
        fixture.vm.sendIntent(HomeChatIntent.StopGenerating)
        advanceUntilIdle()

        assertEquals(1, fixture.agent.stopCount)
        assertEquals(
            listOf(HomeChatBlock.Text("partial")),
            fixture.vm.uiStateFlow.value.turns.single().blocks
        )
        assertFalse(fixture.vm.uiStateFlow.value.isGenerating)
    }

    @Test
    fun startupRestoreUsesAgentStateAndRestoresUiDraft() = runTest {
        val store = FakeHomeConversationStore(restoreOnStartup = true)
        store.createRecord("saved", "hello")
        store.setSnapshot(
            "saved", snapshotOf(
                Message.User(listOf(ContentBlock.Text("hello"))),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("answer")))),
            )
        )
        store.setDraftText("unfinished")
        store.setLastOpenedConversationId("saved")
        val fixture = fixture(store = store)
        advanceUntilIdle()

        val state = fixture.vm.uiStateFlow.value
        assertEquals("saved", fixture.agent.lastLoadedId?.value)
        assertEquals("saved", state.currentConversationId)
        assertEquals("unfinished", state.input)
        assertEquals("unfinished", fixture.agent.draft.value.text)
        assertEquals("hello", state.turns.single().userText)
        assertEquals(listOf(HomeChatBlock.Text("answer")), state.turns.single().blocks)
    }

    @Test
    fun startupRestoresGlobalDraftEvenWithoutHistoryRestore() = runTest {
        val store = FakeHomeConversationStore(restoreOnStartup = false)
        store.setDraftText("pending idea")
        val fixture = fixture(store = store)
        advanceUntilIdle()

        val state = fixture.vm.uiStateFlow.value
        assertNull(state.currentConversationId)
        assertEquals("pending idea", state.input)
        assertEquals("pending idea", fixture.agent.draft.value.text)
    }

    @Test
    fun switchingConversationPreservesCurrentDraft() = runTest {
        val store = FakeHomeConversationStore()
        store.createRecord("first", "one")
        store.setSnapshot("first", snapshotOf(Message.User(listOf(ContentBlock.Text("one")))))
        store.createRecord("second", "two")
        store.setSnapshot(
            "second", snapshotOf(
                Message.User(listOf(ContentBlock.Text("two"))),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("reply")))),
            )
        )
        val fixture = fixture(store = store)
        fixture.vm.sendIntent(HomeChatIntent.InputChanged("draft to keep"))
        runCurrent()

        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("second"))
        advanceUntilIdle()

        val state = fixture.vm.uiStateFlow.value
        assertEquals("second", fixture.agent.lastLoadedId?.value)
        assertEquals("second", state.currentConversationId)
        assertEquals("draft to keep", state.input)
        assertEquals("draft to keep", fixture.agent.draft.value.text)
        assertEquals("two", state.turns.single().userText)
        assertEquals("second", store.lastOpenedConversationId())
    }

    @Test
    fun newConversationPreservesCurrentDraftWithoutDiscardingAgent() = runTest {
        val store = FakeHomeConversationStore()
        store.createRecord("saved", "old")
        val fixture = fixture(store = store)
        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("saved"))
        advanceUntilIdle()

        fixture.vm.sendIntent(HomeChatIntent.InputChanged("keep this in new chat"))
        runCurrent()
        fixture.vm.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()

        assertEquals(0, fixture.agent.discardCount)
        assertNull(fixture.vm.uiStateFlow.value.currentConversationId)
        assertEquals("keep this in new chat", fixture.vm.uiStateFlow.value.input)
        assertEquals("keep this in new chat", fixture.agent.draft.value.text)
    }

    @Test
    fun newConversationSwitchesToBlankStateWithoutKillingSavedHistory() = runTest {
        val fixture = fixture()
        fixture.store.createRecord("saved", "old")
        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("saved"))
        advanceUntilIdle()
        fixture.vm.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()

        assertEquals(0, fixture.agent.discardCount)
        assertEquals(null, fixture.vm.uiStateFlow.value.currentConversationId)
        assertEquals("", fixture.store.lastOpenedConversationId())
        assertTrue(fixture.store.getConversation("saved") != null)
    }

    @Test
    fun newConversation_detachesPreviousAgent_preventingTrailingEmissions() = runTest {
        val store = FakeHomeConversationStore()
        store.createRecord("conv-1", "hello")
        val oldAgent = FakeHomeAgent(store)
        val newAgent = FakeHomeAgent(store)
        val agentFlow = MutableStateFlow<Agent>(oldAgent)
        installService<AgentManager>(object : AgentManager {
            override val main: Agent get() = agentFlow.value
            override val mainFlow: StateFlow<Agent> = agentFlow.asStateFlow()
            override suspend fun switchTo(conversationId: String): Agent {
                oldAgent.load(ConversationId(conversationId))
                agentFlow.value = oldAgent
                return oldAgent
            }

            override fun startNewConversation(): Agent {
                agentFlow.value = newAgent
                return newAgent
            }

            override val activeStates: StateFlow<Map<String, AgentState>> = MutableStateFlow(emptyMap())
            override fun createTaskAgent(isPersistent: Boolean): Agent = newAgent
            override fun destroy(conversationId: String) {}
            override val control: AgentControl = newAgent
        })
        val vm = HomeChatViewModel(
            conversations = store,
            textPacer = TextPacer(delayFn = {}),
            thinkingPacer = TextPacer(delayFn = {}),
        )
        vm.sendIntent(HomeChatIntent.LoadConversation("conv-1"))
        advanceUntilIdle()
        assertEquals("conv-1", vm.uiStateFlow.value.currentConversationId)

        // Switch to new conversation
        vm.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()
        assertNull(vm.uiStateFlow.value.currentConversationId)
        assertTrue(vm.uiStateFlow.value.turns.isEmpty())

        // Simulate old agent emitting trailing content in background
        oldAgent.mutableConversation.value = Conversation(
            id = ConversationId("conv-1"),
            turns = listOf(
                ConversationTurn(
                    id = turnIdAt(0),
                    userText = "hello",
                    blocks = listOf(TurnBlock.Text("t0:0", "leak")),
                )
            )
        )
        advanceUntilIdle()

        // UI state must remain clean, unaffected by oldAgent
        assertNull(vm.uiStateFlow.value.currentConversationId)
        assertTrue(vm.uiStateFlow.value.turns.isEmpty())
    }

    @Test
    fun switchingConversationBindsActiveConfigToLeavingConversation() = runTest {
        val store = FakeHomeConversationStore()
        store.currentActiveConfigId = "active-m1"
        store.createRecord("first", "one")
        store.createRecord("second", "two")
        val fixture = fixture(store = store)

        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("first"))
        advanceUntilIdle()
        assertEquals(emptyMap<String, String>(), store.boundConfigs)

        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("second"))
        advanceUntilIdle()

        assertEquals("active-m1", store.boundConfigs["first"])
        assertEquals("active-m1", store.getConversation("first")?.configId)
    }

    @Test
    fun newConversationBindsActiveConfigToLeavingConversation() = runTest {
        val store = FakeHomeConversationStore()
        store.currentActiveConfigId = "active-m2"
        store.createRecord("active-chat", "hello")
        val fixture = fixture(store = store)

        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("active-chat"))
        advanceUntilIdle()
        assertEquals(emptyMap<String, String>(), store.boundConfigs)

        fixture.vm.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()

        assertEquals("active-m2", store.boundConfigs["active-chat"])
        assertEquals("active-m2", store.getConversation("active-chat")?.configId)
    }

    @Test
    fun blankConversationLeavingDoesNotBindConfig() = runTest {
        val store = FakeHomeConversationStore()
        store.createRecord("other", "other")
        val fixture = fixture(store = store)
        assertNull(fixture.agent.conversation.value.id)

        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("other"))
        advanceUntilIdle()
        assertEquals(emptyMap<String, String>(), store.boundConfigs)
    }

    @Test
    fun deleteActiveConversationDeletesHistoryAndStartsNewConversation() = runTest {
        val fixture = fixture()
        fixture.store.createRecord("saved", "old")
        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("saved"))
        advanceUntilIdle()
        fixture.vm.sendIntent(HomeChatIntent.DeleteConversation("saved"))
        advanceUntilIdle()

        assertNull(fixture.store.getConversation("saved"))
        assertEquals(null, fixture.vm.uiStateFlow.value.currentConversationId)
    }

    @Test
    fun forkRetainsExistingAppOwnedHistoryPath() = runTest {
        val fixture = fixture()
        fixture.store.createRecord("source", "first")
        fixture.store.setSnapshot(
            "source", snapshotOf(
                Message.User(listOf(ContentBlock.Text("first"))),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("answer")))),
                Message.User(listOf(ContentBlock.Text("second"))),
            )
        )
        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("source"))
        advanceUntilIdle()
        fixture.vm.sendIntent(HomeChatIntent.ForkAt(0))
        advanceUntilIdle()

        val fork = fixture.store.getConversation(fixture.store.lastOpenedConversationId())!!
        assertTrue(fork.summary.title.startsWith("Fork ·"))
        assertEquals(2, fork.snapshot.entries.size)
        assertEquals(fork.summary.id, fixture.agent.lastLoadedId?.value)
    }

    @Test
    fun regenerateKeepsSelectedQueryAndImages() = runTest {
        val fixture = fixture()
        fixture.store.createRecord("source", "first")
        fixture.store.setSnapshot(
            "source", snapshotOf(
                Message.User(
                    listOf(
                        ContentBlock.Text("first"),
                        ContentBlock.Image("/image.jpg", "image/jpeg")
                    )
                ),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("answer")))),
                Message.User(listOf(ContentBlock.Text("second"))),
            )
        )
        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("source"))
        advanceUntilIdle()

        fixture.vm.sendIntent(HomeChatIntent.ReGenerateAt(0))
        advanceUntilIdle()
        assertEquals("first", fixture.agent.sentDrafts.last().text)
        assertEquals(listOf("/image.jpg"), fixture.agent.sentImages.last().map { it.path })
    }

    @Test
    fun rewindKeepsSelectedQueryAndImages() = runTest {
        val fixture = fixture()
        fixture.store.createRecord("source", "first")
        fixture.store.setSnapshot(
            "source", snapshotOf(
                Message.User(
                    listOf(
                        ContentBlock.Text("first"),
                        ContentBlock.Image("/image.jpg", "image/jpeg")
                    )
                ),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("answer")))),
                Message.User(listOf(ContentBlock.Text("second"))),
            )
        )
        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("source"))
        advanceUntilIdle()

        fixture.vm.sendIntent(HomeChatIntent.RewindAt(0))
        advanceUntilIdle()
        assertEquals("first", fixture.vm.uiStateFlow.value.input)
        assertEquals("/image.jpg", fixture.vm.uiStateFlow.value.pendingImages.single().path)
    }

    @Test
    fun approvalRequestSurfacesInUiStateAndSettlesThroughIntent() = runTest {
        val fixture = fixture()
        val approver = requireNotNull(fixture.agent.registeredApprover)
        val request = ApprovalRequest.ToolExecution("terminal", "rm -rf /tmp", "dangerous_rm", "rm -rf")

        val decision = async { approver.decide(request) }
        advanceUntilIdle()
        assertEquals(request, fixture.vm.uiStateFlow.value.pendingApproval)

        fixture.vm.sendIntent(HomeChatIntent.ResolveApproval(ApprovalDecision.Allow))
        advanceUntilIdle()

        assertEquals(ApprovalDecision.Allow, decision.await())
        assertNull(fixture.vm.uiStateFlow.value.pendingApproval)
    }

    @Test
    fun cancelledApprovalRequestClearsPendingUiState() = runTest {
        val fixture = fixture()
        val approver = requireNotNull(fixture.agent.registeredApprover)

        val decision = async { approver.decide(ApprovalRequest.ScreenControlConsent) }
        advanceUntilIdle()
        assertEquals(
            ApprovalRequest.ScreenControlConsent,
            fixture.vm.uiStateFlow.value.pendingApproval
        )

        decision.cancel()
        advanceUntilIdle()

        assertNull(fixture.vm.uiStateFlow.value.pendingApproval)
    }

    @Test
    fun selectConfigUpdatesBoundConfigAndPersistsToStoreAndAgent() = runTest {
        val config1 = SavedLlmConfig(
            id = "c1",
            name = "Claude",
            provider = "anthropic",
            endpoint = "https://api.anthropic.com",
            apiKey = "key1",
            model = "claude-3-5-sonnet",
            protocol = "",
            proxy = "",
        )
        val config2 = SavedLlmConfig(
            id = "c2",
            name = "DeepSeek",
            provider = "deepseek",
            endpoint = "https://api.deepseek.com",
            apiKey = "key2",
            model = "deepseek-chat",
            protocol = "",
            proxy = "",
        )
        val fixture = fixture(
            configDependencies = HomeChatConfigDependencies(
                loadConfigs = {
                    LlmConfigsDocument(
                        activeId = "c1",
                        configs = listOf(config1, config2),
                    )
                },
            ),
        )
        fixture.store.createRecord("conv-1", "Hello")
        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("conv-1"))
        advanceUntilIdle()

        assertEquals("conv-1", fixture.vm.uiStateFlow.value.currentConversationId)
        assertEquals("c1", fixture.vm.uiStateFlow.value.activeConfigId)

        fixture.vm.sendIntent(HomeChatIntent.OpenConfigMenu)
        advanceUntilIdle()
        assertTrue(fixture.vm.uiStateFlow.value.isConfigMenuVisible)

        fixture.vm.sendIntent(HomeChatIntent.SelectConfig("c2"))
        advanceUntilIdle()

        assertEquals("c2", fixture.vm.uiStateFlow.value.boundConfigId)
        assertEquals("c2", fixture.vm.uiStateFlow.value.activeConfigId)
        assertEquals("c2", fixture.agent.boundConfigId)
        assertEquals("c2", fixture.store.boundConfigs["conv-1"])
        assertFalse(fixture.vm.uiStateFlow.value.isConfigMenuVisible)
    }

    @Test
    fun loadConversationWithBoundConfigDisplaysBoundConfigOtherwiseFallsBackToGlobalActive() =
        runTest {
            val config1 = SavedLlmConfig(
                id = "c1",
                name = "Claude",
                provider = "anthropic",
                endpoint = "https://api.anthropic.com",
                apiKey = "key1",
                model = "claude-3-5-sonnet",
                protocol = "",
                proxy = "",
            )
            val config2 = SavedLlmConfig(
                id = "c2",
                name = "DeepSeek",
                provider = "deepseek",
                endpoint = "https://api.deepseek.com",
                apiKey = "key2",
                model = "deepseek-chat",
                protocol = "",
                proxy = "",
            )
            val fixture = fixture(
                configDependencies = HomeChatConfigDependencies(
                    loadConfigs = {
                        LlmConfigsDocument(
                            activeId = "c1",
                            configs = listOf(config1, config2),
                        )
                    },
                ),
            )
            fixture.store.createRecord("conv-bound", "Bound session", configId = "c2")
            fixture.store.createRecord("conv-unbound", "Unbound session", configId = "")
            fixture.store.createRecord(
                "conv-deleted",
                "Deleted config session",
                configId = "c-deleted"
            )

            // 1. Bound to valid config -> shows bound config
            fixture.vm.sendIntent(HomeChatIntent.LoadConversation("conv-bound"))
            advanceUntilIdle()
            assertEquals("c2", fixture.vm.uiStateFlow.value.boundConfigId)
            assertEquals("c2", fixture.vm.uiStateFlow.value.activeConfigId)
            assertEquals("DeepSeek", fixture.vm.uiStateFlow.value.displayConfig?.name)

            // 2. Unbound (empty string) -> fallback to global active config (c1)
            fixture.vm.sendIntent(HomeChatIntent.LoadConversation("conv-unbound"))
            advanceUntilIdle()
            assertEquals("", fixture.vm.uiStateFlow.value.boundConfigId)
            assertEquals("c1", fixture.vm.uiStateFlow.value.activeConfigId)
            assertEquals("Claude", fixture.vm.uiStateFlow.value.displayConfig?.name)

            // 3. Bound to deleted config -> fallback to global active config (c1)
            fixture.vm.sendIntent(HomeChatIntent.LoadConversation("conv-deleted"))
            advanceUntilIdle()
            assertEquals("c-deleted", fixture.vm.uiStateFlow.value.boundConfigId)
            assertEquals("c1", fixture.vm.uiStateFlow.value.activeConfigId)
            assertEquals("Claude", fixture.vm.uiStateFlow.value.displayConfig?.name)
        }

    @Test
    fun newConversationResetsBoundConfigToFallbackToGlobalActive() = runTest {
        val config1 = SavedLlmConfig(
            id = "c1",
            name = "Claude",
            provider = "anthropic",
            endpoint = "https://api.anthropic.com",
            apiKey = "key1",
            model = "claude-3-5-sonnet",
            protocol = "",
            proxy = "",
        )
        val config2 = SavedLlmConfig(
            id = "c2",
            name = "DeepSeek",
            provider = "deepseek",
            endpoint = "https://api.deepseek.com",
            apiKey = "key2",
            model = "deepseek-chat",
            protocol = "",
            proxy = "",
        )
        val fixture = fixture(
            configDependencies = HomeChatConfigDependencies(
                loadConfigs = {
                    LlmConfigsDocument(
                        activeId = "c1",
                        configs = listOf(config1, config2),
                    )
                },
            ),
        )
        fixture.store.createRecord("conv-bound", "Bound session", configId = "c2")
        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("conv-bound"))
        advanceUntilIdle()
        assertEquals("c2", fixture.vm.uiStateFlow.value.activeConfigId)

        // Start new conversation: resets bound config to null, falls back to c1
        fixture.vm.sendIntent(HomeChatIntent.NewConversation)
        advanceUntilIdle()
        assertNull(fixture.vm.uiStateFlow.value.boundConfigId)
        assertEquals("c1", fixture.vm.uiStateFlow.value.activeConfigId)
    }

    @Test
    fun contextProgressMirrorsAgentContextUsage() = runTest {
        val fixture = fixture()

        fixture.agent.mutableContextUsage.value =
            ContextUsage(usedTokens = 50_000L, windowTokens = 200_000L)
        runCurrent()
        assertEquals(0.25f, fixture.vm.uiStateFlow.value.contextProgress)

        fixture.agent.mutableContextUsage.value = null
        runCurrent()
        assertNull(fixture.vm.uiStateFlow.value.contextProgress)
    }

    @Test
    fun switchTurnBranch_loadsTargetBranchConversation() = runTest {
        val fixture = fixture()
        fixture.store.createRecord("c0", "hello")
        fixture.store.setSnapshot(
            "c0", snapshotOf(
                Message.User(listOf(ContentBlock.Text("hello"))),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("ans0")))),
            )
        )
        fixture.store.forkAtTurn("c0", turnIndex = 0, kind = ForkKind.Regenerate)
        fixture.store.setSnapshot(
            "fork-1", snapshotOf(
                Message.User(listOf(ContentBlock.Text("hello"))),
                Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("ans1")))),
            )
        )

        fixture.vm.sendIntent(HomeChatIntent.LoadConversation("c0"))
        advanceUntilIdle()

        val turnId = 0L
        assertEquals(1 to 2, fixture.vm.uiStateFlow.value.turnBranches[turnId]?.let { it.current to it.total })

        fixture.vm.sendIntent(HomeChatIntent.SwitchTurnBranch(turnId = turnId, delta = 1))
        advanceUntilIdle()

        assertEquals("fork-1", fixture.agent.lastLoadedId?.value)
    }
}
