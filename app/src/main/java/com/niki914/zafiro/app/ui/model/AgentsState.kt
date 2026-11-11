package com.niki914.zafiro.app.ui.model

import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.AgentDelegator
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.findService
import com.tricreta.zafiro.autonomy.AutonomyGraph
import com.tricreta.zafiro.autonomy.data.AgentProfileEntity
import com.tricreta.zafiro.autonomy.form.AgentDraft
import com.tricreta.zafiro.autonomy.form.AgentDraftResult
import com.tricreta.zafiro.autonomy.form.AgentField
import kotlinx.coroutines.launch
import java.util.UUID

data class AgentsUiState(val agents: List<AgentProfileEntity> = emptyList(), val isLoading: Boolean = true)

sealed interface AgentsIntent {
    data object Start : AgentsIntent
}

sealed interface AgentsEffect

class AgentsViewModel : ComposeMVIViewModel<AgentsIntent, AgentsUiState, AgentsEffect>() {
    private var started = false
    override fun initUiState() = AgentsUiState()

    override suspend fun handleIntent(intent: AgentsIntent) {
        if (intent == AgentsIntent.Start && !started) {
            started = true
            viewModelScope.launch {
                AutonomyGraph.require().repo.observeAgents().collect { updateState { copy(agents = it, isLoading = false) } }
            }
        }
    }
}

data class AgentEditorUiState(
    val agentId: String? = null,
    val draft: AgentDraft = AgentDraft(),
    val initial: AgentDraft = AgentDraft(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: String? = null,
    val errorField: AgentField? = null,
    val configs: List<SavedConfigSummary> = emptyList(),
    val deleteConfirmation: String? = null,
) {
    val hasUnsavedChanges: Boolean get() = !isLoading && draft != initial
}

sealed interface AgentEditorIntent {
    data class Load(val agentId: String?) : AgentEditorIntent
    data class Update(val draft: AgentDraft) : AgentEditorIntent
    data object Save : AgentEditorIntent
    data object TestRun : AgentEditorIntent
    data object RequestDelete : AgentEditorIntent
    data object DismissDelete : AgentEditorIntent
    data object ConfirmDelete : AgentEditorIntent
}

sealed interface AgentEditorEffect {
    data object Exit : AgentEditorEffect
    data class Message(val text: String) : AgentEditorEffect
}

class AgentEditorViewModel : ComposeMVIViewModel<AgentEditorIntent, AgentEditorUiState, AgentEditorEffect>() {
    private val graph: AutonomyGraph get() = AutonomyGraph.require()
    private var existing: AgentProfileEntity? = null

    override fun initUiState() = AgentEditorUiState()

    override suspend fun handleIntent(intent: AgentEditorIntent) {
        when (intent) {
            is AgentEditorIntent.Load -> load(intent.agentId)
            is AgentEditorIntent.Update -> updateState { copy(draft = intent.draft, error = null, errorField = null) }
            AgentEditorIntent.Save -> save()
            AgentEditorIntent.TestRun -> testRun()
            AgentEditorIntent.RequestDelete -> updateState { copy(deleteConfirmation = existing?.name.orEmpty()) }
            AgentEditorIntent.DismissDelete -> updateState { copy(deleteConfirmation = null) }
            AgentEditorIntent.ConfirmDelete -> {
                val id = currentState.agentId ?: return
                graph.repo.deleteAgent(id)
                updateState { copy(deleteConfirmation = null, initial = draft) }
                sendEffect(AgentEditorEffect.Exit)
            }
        }
    }

    private suspend fun load(agentId: String?) {
        if (!currentState.isLoading && currentState.agentId == agentId) return
        val agent = agentId?.let { graph.repo.agent(it) }
        existing = agent
        val draft = agent?.let(AgentDraft::from) ?: AgentDraft()
        val configs = runCatching { summariesOf(XRepo.llmConfigs.document()) }.getOrDefault(emptyList())
        updateState { copy(agentId = agentId, draft = draft, initial = draft, isLoading = false, configs = configs) }
    }

    private suspend fun save() {
        if (currentState.isSaving) return
        updateState { copy(isSaving = true, error = null) }
        val id = currentState.agentId ?: UUID.randomUUID().toString()
        val names = graph.repo.agents().map { it.name }
        when (val r = currentState.draft.toEntity(id, existing?.createdAtMs, System.currentTimeMillis(), names, existing?.name)) {
            is AgentDraftResult.Invalid -> updateState { copy(isSaving = false, error = r.message, errorField = r.field) }
            is AgentDraftResult.Ok -> runCatching { graph.repo.saveAgent(r.agent) }
                .onSuccess {
                    Logger.i(LOG_TAG, "saved agent id=$id")
                    updateState { copy(isSaving = false, initial = draft) }
                    sendEffect(AgentEditorEffect.Exit)
                }
                .onFailure { t -> updateState { copy(isSaving = false, error = t.message ?: "Could not save the agent.") } }
        }
    }

    /** Runs the saved agent once with a harmless task so the owner can see it work (or fail) before relying on it. */
    private suspend fun testRun() {
        val saved = existing ?: return sendEffect(AgentEditorEffect.Message("Save the agent first, then test it."))
        val delegator = findService<AgentDelegator>() ?: return sendEffect(AgentEditorEffect.Message("The agent system is not running."))
        sendEffect(AgentEditorEffect.Message("Testing ${saved.name}..."))
        val r = delegator.call(saved.name, "Introduce yourself in two sentences and say which tools and skills you will use.", background = false)
        sendEffect(AgentEditorEffect.Message("${r.status}: ${r.text.take(400)}"))
    }

    private companion object {
        const val LOG_TAG = "niki914_zafiro_AgentEditorViewModel"
    }
}
