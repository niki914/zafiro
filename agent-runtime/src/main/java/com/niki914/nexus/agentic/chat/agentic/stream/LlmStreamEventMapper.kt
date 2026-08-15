package com.niki914.nexus.agentic.chat.agentic.stream

import com.niki914.nexus.agentic.chat.LlmStreamEvent
import com.niki914.nexus.agentic.chat.ToolCallKind
import com.niki914.nexus.agentic.chat.ToolCallStatus
import com.niki914.logging.Logger
import com.niki914.nexus.agentic.chat.agentic.buildin.TextToolResult
import com.niki914.kai.KaiEvent
import com.niki914.kai.ToolCallKind as SessionToolCallKind

object LlmStreamEventMapper {
    private const val LOG_TAG = "niki914_nexus_LlmStreamEventMapper"

    fun map(
        event: KaiEvent,
        accumulator: StringBuilder,
        startedAtMs: Long,
        defaultErrorMessage: String,
    ): LlmStreamEvent? {
        val mapped = when (event) {
            is KaiEvent.RoundStarted -> LlmStreamEvent.RoundStarted
            is KaiEvent.TextDelta -> {
                accumulator.clear()
                accumulator.append(event.fullText)
                LlmStreamEvent.TextDelta(
                    delta = event.delta,
                    fullText = event.fullText,
                    charsPerSecond = charsPerSecond(event.fullText, startedAtMs),
                )
            }

            is KaiEvent.ToolRunning -> LlmStreamEvent.ToolRunning(event.toToolCallStatus())
            is KaiEvent.ToolSucceeded -> {
                val call = event.toToolCallStatus()
                val parsed = ParsedToolResult.decode(
                    raw = event.resultJson,
                    toolName = event.toolName,
                )
                if (parsed.status == TextToolResult.Status.Failure) {
                    LlmStreamEvent.ToolFailed(
                        call = call,
                        message = parsed.message ?: parsed.code ?: "Tool failed.",
                        resultText = parsed.payload.takeIf { it.isNotBlank() },
                    )
                } else {
                    LlmStreamEvent.ToolSucceeded(
                        call = call,
                        outputText = parsed.payload,
                    )
                }
            }

            is KaiEvent.ToolFailed -> {
                val parsedPayload = event.resultJson?.let {
                    ParsedToolResult.decode(raw = it, toolName = event.toolName).payload
                        .takeIf { p -> p.isNotBlank() }
                }
                LlmStreamEvent.ToolFailed(
                    call = event.toToolCallStatus(),
                    message = event.message,
                    resultText = parsedPayload,
                )
            }

            is KaiEvent.Error -> LlmStreamEvent.Error(
                message = event.message.trim().ifEmpty { defaultErrorMessage },
                throwable = event.cause,
            )

            is KaiEvent.RoundCompleted -> {
                accumulator.clear()
                accumulator.append(event.fullText)
                LlmStreamEvent.Completed(event.fullText)
            }
        }
        // TextDelta 每 token 触发，属高频路径，不记日志；其余事件低频，保留
        if (event !is KaiEvent.TextDelta) {
            Logger.d(
                LOG_TAG,
                "mapped kaiEvent=${event::class.simpleName} " +
                    "-> ${mapped?.let { it::class.simpleName } ?: "null"}"
            )
        }
        return mapped
    }

    private fun charsPerSecond(
        fullText: String,
        startedAtMs: Long,
    ): Float {
        val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(1)
        return fullText.length * 1000f / elapsedMs
    }

    private fun KaiEvent.ToolRunning.toToolCallStatus(): ToolCallStatus =
        ToolCallStatus(callId = callId, name = toolName, label = toolName, kind = kind.toV2Kind())

    private fun KaiEvent.ToolSucceeded.toToolCallStatus(): ToolCallStatus =
        ToolCallStatus(callId = callId, name = toolName, label = toolName, kind = kind.toV2Kind())

    private fun KaiEvent.ToolFailed.toToolCallStatus(): ToolCallStatus =
        ToolCallStatus(callId = callId, name = toolName, label = toolName, kind = kind.toV2Kind())

    private fun SessionToolCallKind.toV2Kind(): ToolCallKind {
        return when (this) {
            SessionToolCallKind.Local -> ToolCallKind.Local
            is SessionToolCallKind.Mcp -> ToolCallKind.Mcp
        }
    }
}
