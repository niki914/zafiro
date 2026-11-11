package com.niki914.zafiro.chat

import com.niki914.okia.message.Usage
import com.niki914.zafiro.api.model.TokenUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sums model usage across an engine's lifetime.
 *
 * Okia stores usage on each committed assistant message but emits no per-request event, and `TurnCompleted` only
 * carries the LAST message of a multi-step turn. So the meter watches the committed history instead and counts each
 * assistant entry exactly once, by id: compaction, rewinds and repeated snapshots can never double-count.
 */
class UsageMeter {
    private val state = MutableStateFlow(TokenUsage())
    private val seen = HashSet<String>()
    val totals: StateFlow<TokenUsage> get() = state

    /** @param entries (entryId, usage) of the ASSISTANT entries in the current history; non-assistant entries are skipped by the caller. */
    @Synchronized
    fun ingest(entries: List<Pair<String, Usage?>>) {
        var next = state.value
        for ((id, usage) in entries) {
            if (usage == null || !seen.add(id)) continue
            next = next.copy(
                requests = next.requests + 1,
                inputTokens = next.inputTokens + usage.inputTokens.coerceAtLeast(0),
                outputTokens = next.outputTokens + usage.outputTokens.coerceAtLeast(0),
                cacheReadTokens = next.cacheReadTokens + usage.cacheReadTokens.coerceAtLeast(0),
                cacheWriteTokens = next.cacheWriteTokens + usage.cacheWriteTokens.coerceAtLeast(0),
            )
        }
        state.value = next
    }

    @Synchronized
    fun reset() {
        seen.clear()
        state.value = TokenUsage()
    }
}
