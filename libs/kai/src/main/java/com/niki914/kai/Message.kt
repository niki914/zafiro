package com.niki914.kai

sealed interface Message {
    data class Tool(
        val callId: String,
        val toolName: String,
        val contentJson: String
    ) : Message
}
