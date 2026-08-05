package com.niki914.okai.message

/**
 * A message in session history. Three concrete roles, no generic role+content base,
 * matching pi's message model.
 *
 * Design source: pi (earendil-works/pi, packages/ai types.ts) UserMessage /
 * AssistantMessage / ToolResultMessage.
 */
sealed interface Message {

    /** User input. Content blocks support future image input. */
    data class User(
        val content: List<ContentBlock>,
        val timestamp: Long
    ) : Message

    /** Assistant response, carrying the full message object used in event partials. */
    data class Assistant(val message: AssistantMessage) : Message

    /**
     * Tool execution result fed back to the model. The content lives
     * inside the outcome, so a call that never produced one (interrupted
     * or unknown) stays distinguishable after a session reload.
     */
    data class ToolResult(
        val callId: String,
        val toolName: String,
        val outcome: ToolCallOutcome
    ) : Message
}

/**
 * Terminal result of one tool call, shared by the interceptor chain, the
 * executor and the session history, so the chain's outcome and the
 * persisted message are the same type and no mapping can lose state.
 * Success, Failure and Blocked are the normal outcomes; Interrupted and
 * Unknown cover a cancelled turn and must never be retried. The provider
 * encoding derives its isError flag from the outcome.
 *
 * Design source: independent design; cancellation semantics required by the
 * force-only stop (kai PRD section 4.4). pi and codex tool result messages
 * carry only content plus isError, without force stop, so the
 * Interrupted/Unknown states have no precedent in either.
 */
sealed interface ToolCallOutcome {

    /** Tool succeeded with a result payload. Content is arbitrary text, not necessarily JSON. */
    data class Success(val content: String) : ToolCallOutcome

    /** Tool ran but failed. */
    data class Failure(val message: String, val content: String? = null) : ToolCallOutcome

    /** Tool was refused before execution. The loop feeds this back to the model. */
    data class Blocked(val reason: String) : ToolCallOutcome

    /** Tool was interrupted before or during execution; content holds partial output when present. */
    data class Interrupted(val content: String? = null) : ToolCallOutcome

    /** Execution state unknown, e.g. a remote call may have run before cancellation. Never retried. */
    data class Unknown(val message: String, val content: String? = null) : ToolCallOutcome
}

/**
 * Full assistant response state. Emitted as a partial snapshot on every turn event
 * so consumers render without accumulating deltas. Usage, response model and
 * reasoning signature are parsed from responses and stay nullable.
 *
 * Design source: pi (earendil-works/pi, packages/ai types.ts) AssistantMessage.
 */
data class AssistantMessage(
    val content: List<ContentBlock>,
    val stopReason: StopReason = StopReason.Pending,
    val usage: Usage? = null,
    val responseModel: String? = null,
    val reasoningSignature: String? = null
)
