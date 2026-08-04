package com.niki914.okai.protocol

/**
 * Per-provider compatibility facts. The loop consults these when committing
 * history and retrying, not just when building requests.
 *
 * Design source: pi (earendil-works/pi) OpenAICompletionsCompat, per kai PRD section 4.3.
 */
interface Compat {

    val maxTokensField: MaxTokensField

    val thinkingFormat: ThinkingFormat

    val supportsReasoningEffort: Boolean

    val requiresThinkingAsText: Boolean

    val requiresReasoningContentOnAssistantMessages: Boolean

    val requiresAssistantAfterToolResult: Boolean

    val requiresToolResultName: Boolean

    val supportsUsageInStreaming: Boolean

    val supportsFinishReason: Boolean

    val retryableStatusCodes: Set<Int>
}

/** Max tokens field name in request bodies. */
enum class MaxTokensField {
    MaxTokens,
    MaxCompletionTokens
}

/** How a provider expresses thinking. */
enum class ThinkingFormat {
    DeepSeek,
    OpenAI,
    ChatTemplate
}

/**
 * Default compatibility profile for the DeepSeek OpenAI-compatible API.
 * M0 ships this profile only; OpenAI and Anthropic profiles come in M1.
 *
 * Design source: kai PRD section 4.3 DeepSeekCompat (M0 scope).
 */
class DeepSeekCompat : Compat {
    override val maxTokensField: MaxTokensField = MaxTokensField.MaxTokens
    override val thinkingFormat: ThinkingFormat = ThinkingFormat.DeepSeek
    override val supportsReasoningEffort: Boolean = true
    override val requiresThinkingAsText: Boolean = false
    override val requiresReasoningContentOnAssistantMessages: Boolean = true
    override val requiresAssistantAfterToolResult: Boolean = false
    override val requiresToolResultName: Boolean = false
    override val supportsUsageInStreaming: Boolean = true
    override val supportsFinishReason: Boolean = true
    override val retryableStatusCodes: Set<Int> = setOf(408, 409, 429, 500, 502, 503, 504)
}
