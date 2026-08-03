package com.niki914.s3ss10n

sealed interface ChatTurn {
    data class User(val content: String) : ChatTurn

    data class Assistant(
        val content: String,
        /**
         * 为空表示本轮 assistant 只返回了文本，没有发起工具调用。
         */
        val toolCalls: List<ToolCallSpec> = emptyList(),
        val reasoningContent: String? = null,
        val reasoningSignature: String? = null
    ) : ChatTurn

    data class ToolResult(
        val callId: String,
        val toolName: String,
        val resultJson: String
    ) : ChatTurn

    data class System(val content: String) : ChatTurn
}

data class ToolCallSpec(
    val callId: String,
    val toolName: String,
    val argumentsJson: String
)
