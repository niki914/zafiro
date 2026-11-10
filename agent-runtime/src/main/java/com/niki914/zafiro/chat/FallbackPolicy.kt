package com.niki914.zafiro.chat

/**
 * When may a failed turn be retried on the next model in the owner's fallback chain?
 *
 * Only for provider-side faults (credits gone, key rejected, rate limit, overload, network) and only while the failed
 * attempt produced nothing the user or a tool could have acted on. After partial text or any tool call a retry could
 * duplicate output or repeat a side effect (a sent message, a post), so the original error is shown instead.
 */
object FallbackPolicy {
    private val providerFaults = setOf(
        LlmErrorCode.Auth,
        LlmErrorCode.Quota,
        LlmErrorCode.RateLimit,
        LlmErrorCode.Overloaded,
        LlmErrorCode.Transport,
        LlmErrorCode.RetryExhausted,
        LlmErrorCode.IdleTimeout,
    )

    fun shouldFallBack(code: LlmErrorCode?, sawOutput: Boolean, isLastCandidate: Boolean): Boolean =
        !isLastCandidate && !sawOutput && code in providerFaults

    /** Output that makes a retry unsafe: anything the user saw or a tool did. Lifecycle events (RoundStarted, Retrying) do not count. */
    fun isOutput(event: LlmStreamEvent): Boolean = when (event) {
        is LlmStreamEvent.TextDelta,
        is LlmStreamEvent.ThinkingStarted,
        is LlmStreamEvent.ThinkingEnded,
        is LlmStreamEvent.ToolRunning,
        is LlmStreamEvent.ToolPending,
        is LlmStreamEvent.ToolSucceeded,
        is LlmStreamEvent.ToolFailed -> true
        else -> false
    }

    /**
     * Ordered, de-duplicated fallback ids: drops blanks, ids that no longer exist, the model already in use, and the active
     * model when the turn runs on it (a blank [primaryId] means "the active one").
     */
    fun resolveChain(
        primaryId: String?,
        activeId: String?,
        configured: List<String>,
        existingIds: Set<String>,
    ): List<String> {
        val inUse = primaryId?.takeIf { it.isNotBlank() } ?: activeId
        return configured.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it in existingIds && it != inUse }
            .distinct()
            .toList()
    }
}
