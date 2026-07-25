package com.niki914.nexus.agentic.chat.agentic.buildin

data class TextToolResult(
    val status: Status,
    val payload: String,
    val code: String? = null,
    val message: String? = null,
) {
    enum class Status { Success, Failure }

    companion object {
        fun success(payload: String): TextToolResult = TextToolResult(Status.Success, payload)

        fun failure(code: String, message: String, payload: String = ""): TextToolResult =
            TextToolResult(Status.Failure, payload, code, message)
    }
}
