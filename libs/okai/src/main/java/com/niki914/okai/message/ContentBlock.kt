package com.niki914.okai.message

/**
 * One content unit inside a message. Assistant messages hold a list of blocks
 * so thinking, text and tool calls coexist in a single response (Anthropic style).
 *
 * Design source: pi (earendil-works/pi, packages/ai types.ts) content block union,
 * as specified in kai PRD section 4.1. ToolResult is deliberately a message type,
 * not a block, following pi's message model.
 */
sealed interface ContentBlock {

    /** Plain text output with optional provider signature for replay. */
    data class Text(val text: String, val signature: String? = null) : ContentBlock

    /** Model reasoning, kept separate from final text. */
    data class Thinking(val text: String, val signature: String? = null) : ContentBlock

    /** Base64-encoded image. Multimodal entry point, unsupported until M2. */
    data class Image(val data: String, val mimeType: String) : ContentBlock

    /** A model-issued tool call. Arguments stay as JSON so the library stays codec-free. */
    data class ToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String
    ) : ContentBlock
}
