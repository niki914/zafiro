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

    /** Tool execution result fed back to the model. */
    data class ToolResult(
        val callId: String,
        val toolName: String,
        val contentJson: String,
        val isError: Boolean
    ) : Message
}

/**
 * Full assistant response state. Emitted as a partial snapshot on every turn event
 * so consumers render without accumulating deltas. Usage and stop reason ride along.
 *
 * Design source: pi (earendil-works/pi, packages/ai types.ts) AssistantMessage.
 */
data class AssistantMessage(
    val content: List<ContentBlock>,
    val stopReason: StopReason = StopReason.Pending,
    val usage: Usage = Usage(),
    val responseModel: String? = null,
    val reasoningSignature: String? = null
)
