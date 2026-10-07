// 保护：历史页 ViewModel 的状态计算——加载成败落点、删除的活动/普通分流与收尾、重命名回报、分叉导航。
package com.niki914.zafiro.app.ui.model.conversation

import com.niki914.zafiro.app.conversation.ConversationSummary
import com.niki914.zafiro.app.ui.model.MainDispatcherRule
import com.niki914.zafiro.app.util.SilentLoggerRule
import com.niki914.zafiro.repo.PinnedConversation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationHistoryViewModelTest {

    @get:Rule
    val silentLoggerRule = SilentLoggerRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** 记录式依赖：删除会同步改动内存列表，便于断言删除后的重载结果。 */
    private class FakeDeps {
        var conversations: List<ConversationSummary> = emptyList()
        var pinnedConversations: List<PinnedConversation> = emptyList()
        val deletedIds = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        val pinCalls = mutableListOf<Pair<String, Boolean>>()
        var forkResult: String? = null
        var loadError: Throwable? = null
        var deleteError: Throwable? = null

        fun toDependencies(): ConversationHistoryViewModelDependencies =
            ConversationHistoryViewModelDependencies(
                loadConversations = { loadError?.let { throw it } ?: conversations },
                loadPinnedConversations = { pinnedConversations },
                deleteConversation = { id ->
                    deleteError?.let { throw it }
                    deletedIds += id
                    conversations = conversations.filterNot { it.id == id }
                },
                renameConversation = { id, title -> renamed += id to title },
                forkConversation = { forkResult },
                setConversationPinned = { id, pinned -> pinCalls += id to pinned },
            )
    }

    private fun summary(id: String): ConversationSummary = ConversationSummary(
        id = id,
        title = id,
        titleEdited = false,
        createdAt = 0L,
        updatedAt = 0L,
        lastMessagePreview = "",
        turnCount = 0,
    )

    @Test
    fun loadSuccess_populatesListAndClearsLoading() = runTest {
        val deps = FakeDeps()
        deps.conversations = listOf(summary("a"))
        val viewModel = ConversationHistoryViewModel(deps.toDependencies())

        viewModel.sendIntent(ConversationHistoryIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertFalse(state.isLoading)
        assertEquals(listOf("a"), state.conversations.map { it.id })
    }

    @Test
    fun loadFailure_surfacesErrorAndStopsLoading() = runTest {
        val deps = FakeDeps()
        deps.loadError = IllegalStateException("boom")
        val viewModel = ConversationHistoryViewModel(deps.toDependencies())

        viewModel.sendIntent(ConversationHistoryIntent.Load)
        advanceUntilIdle()

        val state = viewModel.uiStateFlow.value
        assertFalse(state.isLoading)
        assertEquals("boom", state.errorMessage)
    }

    @Test
    fun deleteActiveConversation_delegatesToHostWithoutRepositoryDelete() = runTest {
        val deps = FakeDeps()
        val viewModel = ConversationHistoryViewModel(deps.toDependencies())
        viewModel.sendIntent(ConversationHistoryIntent.SetActiveConversation("a"))
        val effectDeferred = async { viewModel.uiEffect.first() }

        viewModel.sendIntent(ConversationHistoryIntent.Delete("a"))
        advanceUntilIdle()

        assertEquals(
            ConversationHistoryEffect.DeleteActiveConversation("a"),
            effectDeferred.await(),
        )
        assertTrue(deps.deletedIds.isEmpty())
    }

    @Test
    fun deleteNormalConversation_unpinsAndReloads() = runTest {
        val deps = FakeDeps()
        deps.conversations = listOf(summary("a"), summary("b"))
        val viewModel = ConversationHistoryViewModel(deps.toDependencies())
        viewModel.sendIntent(ConversationHistoryIntent.Load)
        advanceUntilIdle()

        viewModel.sendIntent(ConversationHistoryIntent.Delete("a"))
        advanceUntilIdle()

        assertEquals(listOf("a"), deps.deletedIds)
        assertEquals(listOf("a" to false), deps.pinCalls)
        assertEquals(listOf("b"), viewModel.uiStateFlow.value.conversations.map { it.id })
    }

    @Test
    fun deleteFailure_setsDeleteError() = runTest {
        val deps = FakeDeps()
        deps.deleteError = IllegalStateException("nope")
        val viewModel = ConversationHistoryViewModel(deps.toDependencies())

        viewModel.sendIntent(ConversationHistoryIntent.Delete("a"))
        advanceUntilIdle()

        assertEquals("nope", viewModel.uiStateFlow.value.deleteErrorMessage)
    }

    @Test
    fun renameActiveConversation_notifiesHost() = runTest {
        val deps = FakeDeps()
        val viewModel = ConversationHistoryViewModel(deps.toDependencies())
        viewModel.sendIntent(ConversationHistoryIntent.SetActiveConversation("a"))
        val effectDeferred = async { viewModel.uiEffect.first() }

        viewModel.sendIntent(ConversationHistoryIntent.Rename("a", "新名字"))
        advanceUntilIdle()

        assertEquals(
            ConversationHistoryEffect.ActiveConversationRenamed("新名字"),
            effectDeferred.await(),
        )
        assertEquals(listOf("a" to "新名字"), deps.renamed)
    }

    @Test
    fun renameOtherConversation_doesNotNotifyHost() = runTest {
        val deps = FakeDeps()
        val viewModel = ConversationHistoryViewModel(deps.toDependencies())
        viewModel.sendIntent(ConversationHistoryIntent.SetActiveConversation("a"))
        val effects = mutableListOf<ConversationHistoryEffect>()
        backgroundScope.launch { viewModel.uiEffect.collect { effects += it } }
        advanceUntilIdle()

        viewModel.sendIntent(ConversationHistoryIntent.Rename("b", "新名字"))
        advanceUntilIdle()

        assertEquals(listOf("b" to "新名字"), deps.renamed)
        assertTrue(effects.isEmpty())
    }

    @Test
    fun forkSuccess_opensNewConversation() = runTest {
        val deps = FakeDeps()
        deps.forkResult = "forked"
        val viewModel = ConversationHistoryViewModel(deps.toDependencies())
        val effectDeferred = async { viewModel.uiEffect.first() }

        viewModel.sendIntent(ConversationHistoryIntent.Fork("a"))
        advanceUntilIdle()

        assertEquals(
            ConversationHistoryEffect.OpenConversation("forked"),
            effectDeferred.await(),
        )
    }

    @Test
    fun toggleSelection_addsThenRemoves() = runTest {
        val deps = FakeDeps()
        val viewModel = ConversationHistoryViewModel(deps.toDependencies())
        viewModel.sendIntent(ConversationHistoryIntent.EnterSelection)

        viewModel.sendIntent(ConversationHistoryIntent.ToggleSelection("a"))
        advanceUntilIdle()
        assertEquals(setOf("a"), viewModel.uiStateFlow.value.selectedIds)

        viewModel.sendIntent(ConversationHistoryIntent.ToggleSelection("a"))
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.selectedIds.isEmpty())
    }

    @Test
    fun exitSelection_clearsFlagAndSelection() = runTest {
        val deps = FakeDeps()
        val viewModel = ConversationHistoryViewModel(deps.toDependencies())
        viewModel.sendIntent(ConversationHistoryIntent.EnterSelection)
        viewModel.sendIntent(ConversationHistoryIntent.ToggleSelection("a"))
        advanceUntilIdle()
        assertTrue(viewModel.uiStateFlow.value.selecting)

        viewModel.sendIntent(ConversationHistoryIntent.ExitSelection)
        advanceUntilIdle()

        assertFalse(viewModel.uiStateFlow.value.selecting)
        assertTrue(viewModel.uiStateFlow.value.selectedIds.isEmpty())
    }
}
