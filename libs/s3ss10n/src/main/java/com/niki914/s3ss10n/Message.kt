package com.niki914.s3ss10n

sealed interface Message {
    data class Tool(
        val callId: String,
        val toolName: String,
        val contentJson: String
    ) : Message
}
