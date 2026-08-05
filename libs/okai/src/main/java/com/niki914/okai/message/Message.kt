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
     * Tool execution result fed back to the model. Content is arbitrary
     * text, not necessarily JSON, and null when the call never produced
     * one (interrupted or unknown outcomes). status keeps the cancellation
     * semantics in the history so a reloaded session can tell a call that
     * never ran from one that may have executed remotely.
     */
    data class ToolResult(
        val callId: String,
        val toolName: String,
        val content: String?,
        val status: ToolResultStatus
    ) : Message
}

/**
 * Terminal state of one tool call in history. Success, Failure and Blocked
 * are the normal outcomes; Interrupted and Unknown cover a cancelled turn
 * and must never be retried. The provider encoding's isError flag derives
 * from status != Success.
 *
 * Design source: independent design; cancellation semantics required by the
 * force-only stop (kai PRD section 4.4). pi and codex tool result messages
 * carry only content plus isError, without force stop, so this enum has no
 * precedent in either.
 */
enum class ToolResultStatus {
    Success,
    Failure,
    Blocked,
    Interrupted,
    Unknown
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
