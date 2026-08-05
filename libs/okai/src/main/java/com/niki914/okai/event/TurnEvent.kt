package com.niki914.okai.event

import com.niki914.okai.error.LLMError
import com.niki914.okai.message.AssistantMessage
import com.niki914.okai.message.ContentBlock

/**
 * Turn event protocol, the library's public contract. Every block-level event
 * carries a full partial snapshot so consumers render streaming output without
 * accumulating deltas. Failures are encoded as TurnFailed, never thrown.
 *
 * Design source: pi (earendil-works/pi) AssistantMessageEvent set, as specified
 * in kai PRD section 4.2; Turn = one whole user-input-to-final-answer cycle
 * (codex turn semantics), not a single model round (pi turn semantics).
 */
sealed interface TurnEvent {

    /** One turn started with the user input. Emitted once. */
    data class TurnStarted(val input: String) : TurnEvent

    /** A text block started at contentIndex. */
    data class TextStarted(val index: Int, val partial: AssistantMessage) : TurnEvent

    /** A text delta appended to the block at contentIndex. */
    data class TextDelta(val index: Int, val delta: String, val partial: AssistantMessage) : TurnEvent

    /** The text block at contentIndex completed. */
    data class TextEnded(val index: Int, val content: String, val partial: AssistantMessage) : TurnEvent

    /** A thinking block started at contentIndex. */
    data class ThinkingStarted(val index: Int, val partial: AssistantMessage) : TurnEvent

    /** A thinking delta appended to the block at contentIndex. */
    data class ThinkingDelta(val index: Int, val delta: String, val partial: AssistantMessage) : TurnEvent

    /** The thinking block at contentIndex completed. */
    data class ThinkingEnded(val index: Int, val content: String, val partial: AssistantMessage) : TurnEvent

    /** A tool call block started at contentIndex. */
    data class ToolCallStarted(val index: Int, val partial: AssistantMessage) : TurnEvent

    /** A tool call arguments delta appended at contentIndex. */
    data class ToolCallDelta(val index: Int, val delta: String, val partial: AssistantMessage) : TurnEvent

    /** The tool call at contentIndex completed with its final arguments. */
    data class ToolCallEnded(val index: Int, val toolCall: ContentBlock.ToolCall, val partial: AssistantMessage) : TurnEvent

    /** A retry was scheduled; attempt starts at 1. */
    data class RetryScheduled(
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val reason: String
    ) : TurnEvent

    /** Turn finished normally. */
    data class TurnCompleted(val message: AssistantMessage, val reason: FinishReason) : TurnEvent

    /** Turn failed or was aborted. The error carries the classification when present. */
    data class TurnFailed(
        val message: AssistantMessage,
        val reason: FinishReason,
        val error: LLMError? = null
    ) : TurnEvent
}

/**
 * Why a whole turn ended. Excludes toolUse: that is a message-level StopReason,
 * a normal intermediate state inside a turn.
 *
 * Design source: kai PRD section 4.2 FinishReason, minus toolUse which moved
 * to StopReason (pi stopReason alignment).
 */
enum class FinishReason {
    Stop,
    Length,
    Error,
    Aborted,
    IdleTimeout,
    RetryExhausted
}

/**
 * Why a turn was cancelled, recorded by the Okai coordinator when it
 * cancelled the turn job and carried on the cancellation, so the loop
 * can tell a user stop, a Replace and an external cancellation apart.
 * Idle timeout is a FinishReason, not a StopCause.
 *
 * Design source: kai PRD section 4.4 stop semantics; the three causes
 * cover the cancellation sources listed in the force-only stop design.
 */
enum class StopCause {
    UserStop,
    Replace,
    External
}
