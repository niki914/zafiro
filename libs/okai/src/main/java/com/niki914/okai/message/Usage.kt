package com.niki914.okai.message

/**
 * Token accounting for one assistant response. Fills the context budget after each turn.
 *
 * Design source: pi (earendil-works/pi, packages/ai types.ts) Usage.
 */
data class Usage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val reasoningTokens: Long = 0
)

/**
 * Why the model ended one response. Message-level, distinct from turn-level FinishReason:
 * toolUse is a normal intermediate state inside a turn.
 *
 * Design source: pi (earendil-works/pi) stopReason, split from kai PRD FinishReason.
 */
enum class StopReason {
    Pending,
    Stop,
    Length,
    ToolUse,
    Error,
    Aborted
}
