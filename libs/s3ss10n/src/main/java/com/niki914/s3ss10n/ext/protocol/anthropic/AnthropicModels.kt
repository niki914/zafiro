package com.niki914.s3ss10n.ext.protocol.anthropic


/**
 * Request body for the Anthropic Messages API.
 * All non-null and non-default fields are serialized by Gson.
 */
internal data class AnthropicRequestBody(
    val model: String,
    val messages: List<AnthropicMessage>,
    val max_tokens: Int,
    val system: String? = null,
    val tools: List<AnthropicToolDef>? = null,
    val temperature: Float? = null,
    val stream: Boolean = true
)

/**
 * A single message in the Anthropic messages array.
 * content is always a list of content blocks (Anthropic accepts
 * both plain strings and arrays; we always use arrays for consistency).
 */
internal data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContentBlock>
)

/**
 * A content block inside an Anthropic message.
 * Type-specific fields are nullable; Gson omits null fields during serialization.
 *
 * Supported types and their fields:
 * - "text":       text
 * - "tool_use":   id, name, input
 * - "tool_result": tool_use_id, content
 */
internal data class AnthropicContentBlock(
    val type: String,
    val text: String? = null,
    val thinking: String? = null,
    val signature: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: Map<String, Any?>? = null,
    val tool_use_id: String? = null,
    val content: String? = null
)

/**
 * A tool definition in the Anthropic tool format.
 * Flat structure — no function wrapper like OpenAI.
 */
internal data class AnthropicToolDef(
    val name: String,
    val description: String,
    val input_schema: Map<String, Any?>
)