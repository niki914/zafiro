package com.niki914.zafiro.chat.agentic.stream

import com.niki914.zafiro.chat.LlmStreamEvent

enum class ToolRenderMode {
    AppendOnly,
    ReplaceStatus,
}

data class ToolRenderText(
    val text: String,
    val replacePreviousStatus: Boolean = false,
)

object ToolEventFormatter {

    fun format(
        event: LlmStreamEvent,
        mode: ToolRenderMode,
    ): ToolRenderText? {
        return when (event) {
            is LlmStreamEvent.ToolRunning -> {
                ToolRenderText(
                    text = "Calling tool: ${event.call.label}",
                    replacePreviousStatus = mode == ToolRenderMode.ReplaceStatus,
                )
            }

            is LlmStreamEvent.ToolSucceeded -> {
                ToolRenderText(
                    text = "Tool succeeded: ${event.call.label}",
                    replacePreviousStatus = mode == ToolRenderMode.ReplaceStatus,
                )
            }

            is LlmStreamEvent.ToolFailed -> {
                ToolRenderText(
                    text = "Tool failed: ${event.call.label} (${event.message})",
                    replacePreviousStatus = mode == ToolRenderMode.ReplaceStatus,
                )
            }

            else -> null
        }
    }
}
