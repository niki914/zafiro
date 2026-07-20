package com.niki914.nexus.agentic.chat.agentic.stream

import com.niki914.nexus.agentic.chat.LlmStreamEvent
import com.niki914.nexus.agentic.chat.ToolCallKind
import com.niki914.nexus.agentic.chat.ToolCallStatus
import com.niki914.s3ss10n.SessionEvent
import com.niki914.s3ss10n.ToolCallKind as SessionToolCallKind

object LlmStreamEventMapper {
    fun map(
        event: SessionEvent,
        accumulator: StringBuilder,
        startedAtMs: Long,
        defaultErrorMessage: String,
    ): LlmStreamEvent? {
        return when (event) {
            is SessionEvent.RoundStarted -> LlmStreamEvent.RoundStarted
            is SessionEvent.TextDelta -> {
                accumulator.clear()
                accumulator.append(event.fullText)
                LlmStreamEvent.TextDelta(
                    delta = event.delta,
                    fullText = event.fullText,
                    charsPerSecond = charsPerSecond(event.fullText, startedAtMs),
                )
            }

            is SessionEvent.ToolRunning -> LlmStreamEvent.ToolRunning(event.toToolCallStatus())
            is SessionEvent.ToolSucceeded -> {
                val call = event.toToolCallStatus()
                val failureMessage = LocalToolResultClassifier.failureMessage(event.resultJson)
                if (failureMessage != null) {
                    LlmStreamEvent.ToolFailed(call = call, message = failureMessage)
                } else {
                    LlmStreamEvent.ToolSucceeded(
                        call = call,
                        outputText = event.resultJson,
                    )
                }
            }

            is SessionEvent.ToolFailed -> LlmStreamEvent.ToolFailed(
                call = event.toToolCallStatus(),
                message = event.message,
            )

            is SessionEvent.Error -> LlmStreamEvent.Error(
                message = event.message.trim().ifEmpty { defaultErrorMessage },
                throwable = event.cause,
            )

            is SessionEvent.RoundCompleted -> {
                accumulator.clear()
                accumulator.append(event.fullText)
                LlmStreamEvent.Completed(event.fullText)
            }
        }
    }

    private fun charsPerSecond(
        fullText: String,
        startedAtMs: Long,
    ): Float {
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1)
        return fullText.length * 1000f / elapsedMs
    }

    private fun SessionEvent.ToolRunning.toToolCallStatus(): ToolCallStatus =
        ToolCallStatus(callId = callId, name = toolName, label = toolName, kind = kind.toV2Kind())

    private fun SessionEvent.ToolSucceeded.toToolCallStatus(): ToolCallStatus =
        ToolCallStatus(callId = callId, name = toolName, label = toolName, kind = kind.toV2Kind())

    private fun SessionEvent.ToolFailed.toToolCallStatus(): ToolCallStatus =
        ToolCallStatus(callId = callId, name = toolName, label = toolName, kind = kind.toV2Kind())

    private fun SessionToolCallKind.toV2Kind(): ToolCallKind {
        return when (this) {
            SessionToolCallKind.Local -> ToolCallKind.Local
            is SessionToolCallKind.Mcp -> ToolCallKind.Mcp
        }
    }
}
