package com.niki914.zafiro.app.ui.model

import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.repo.XRepo

/** Pure slot logic for the fallback list: three ordered slots, blank = unused, no model twice. */
object FallbackSlots {
    const val COUNT = 3

    fun toSlots(chain: List<String>): List<String> = List(COUNT) { chain.getOrElse(it) { "" } }

    fun set(slots: List<String>, index: Int, id: String): List<String> {
        require(index in 0 until COUNT) { "slot $index out of range" }
        val next = slots.toMutableList()
        if (id.isNotBlank()) {
            for (i in next.indices) if (i != index && next[i] == id) next[i] = ""
        }
        next[index] = id
        return next
    }

    fun toChain(slots: List<String>): List<String> = slots.filter { it.isNotBlank() }.distinct()

    /** Drops ids that no longer exist (the owner deleted that model) and closes gaps so slot 1 is always filled first. */
    fun prune(slots: List<String>, existingIds: Set<String>): List<String> =
        toSlots(toChain(slots).filter { it in existingIds })
}

data class FallbackChainUiState(
    val slots: List<String> = FallbackSlots.toSlots(emptyList()),
    val configs: List<SavedConfigSummary> = emptyList(),
)

sealed interface FallbackChainIntent {
    data object Load : FallbackChainIntent
    data class SetSlot(val index: Int, val configId: String) : FallbackChainIntent
}

sealed interface FallbackChainEffect

class FallbackChainViewModel : ComposeMVIViewModel<FallbackChainIntent, FallbackChainUiState, FallbackChainEffect>() {

    override fun initUiState(): FallbackChainUiState = FallbackChainUiState()

    override suspend fun handleIntent(intent: FallbackChainIntent) {
        when (intent) {
            FallbackChainIntent.Load -> load()
            is FallbackChainIntent.SetSlot -> {
                val slots = FallbackSlots.set(currentState.slots, intent.index, intent.configId)
                XRepo.setLlmFallbackChain(FallbackSlots.toChain(slots))
                updateState { copy(slots = slots) }
                Logger.i(LOG_TAG, "fallback chain saved size=${FallbackSlots.toChain(slots).size}")
            }
        }
    }

    private suspend fun load() {
        val configs = summariesOf(XRepo.llmConfigs.document())
        val slots = FallbackSlots.prune(FallbackSlots.toSlots(XRepo.llmFallbackChain()), configs.map { it.id }.toSet())
        updateState { copy(slots = slots, configs = configs) }
    }

    private companion object {
        const val LOG_TAG = "niki914_zafiro_FallbackChainViewModel"
    }
}
