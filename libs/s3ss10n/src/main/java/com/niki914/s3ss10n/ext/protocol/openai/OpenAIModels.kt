package com.niki914.s3ss10n.ext.protocol.openai


internal data class OpenAIChatRequestBody(
    val model: String,
    val messages: List<OpenAIMessage>,
    val tools: List<ToolDefinition>? = null,
    val temperature: Float? = null,
    val stream: Boolean = true
)

internal sealed class OpenAIMessage(val role: String) {
    abstract val content: String?

    data class System(override val content: String) : OpenAIMessage("system")

    data class User(override val content: String) : OpenAIMessage("user")

    data class Assistant(
        override val content: String?,
        val tool_calls: List<ToolCall>? = null,
        val reasoning_content: String? = null
    ) : OpenAIMessage("assistant")

    data class Tool(
        val tool_call_id: String,
        val name: String,
        override val content: String
    ) : OpenAIMessage("tool")
}

data class ToolDefinition(
    val function: FunctionTool
) {
    val type: String = "function"
}

data class FunctionTool(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>
)

internal data class OpenAIChatResponseFrame(
    val choices: List<Choice?>?
)

internal data class Choice(
    val delta: Delta?
)

internal data class Delta(
    val content: String?,
    val reasoning_content: String?,
    val tool_calls: List<ToolCall?>?
)

internal data class ToolCall(
    val id: String?,
    val type: String?,
    val function: FunctionCall?
)

internal data class FunctionCall(
    val name: String?,
    val arguments: String?
)
