package com.niki914.nexus.agentic.chat

import kotlinx.coroutines.flow.Flow

data class LlmTextFrame(
    val text: String,
    val isFirst: Boolean,
    val isFinal: Boolean,
)

suspend fun Flow<LlmStreamEvent>.collectAsFull(
    render: suspend (LlmTextFrame) -> Unit,
) {
    collectAsFull(
        labels = ToolStatusLabels.Default,
        render = render,
    )
}

internal suspend fun Flow<LlmStreamEvent>.collectAsFull(
    labels: ToolStatusLabels,
    render: suspend (LlmTextFrame) -> Unit,
) {
    val projector = FullTextProjector(labels)
    collect { event ->
        projector.apply(event).forEach { frame ->
            render(frame)
        }
    }
}

suspend fun Flow<LlmStreamEvent>.collectAsChunk(
    render: suspend (LlmTextFrame) -> Unit,
) {
    collectAsChunk(
        labels = ToolStatusLabels.Default,
        render = render,
    )
}

internal suspend fun Flow<LlmStreamEvent>.collectAsChunk(
    labels: ToolStatusLabels,
    render: suspend (LlmTextFrame) -> Unit,
) {
    val projector = ChunkTextProjector(labels)
    collect { event ->
        projector.apply(event).forEach { frame ->
            render(frame)
        }
    }
}

internal data class ToolStatusLabels(
    val called: String,
    val running: String,
    val success: String,
    val failed: String,
) {
    companion object {
        val Default = ToolStatusLabels(
            called = "called",
            running = "running",
            success = "success",
            failed = "failed",
        )
    }
}

private sealed interface RenderSegment {
    data class Text(val value: String) : RenderSegment
    data class Tool(
        val key: String,
        val name: String,
        val label: String,
    ) : RenderSegment

    data class Error(val value: String) : RenderSegment
}

private class FullTextProjector(
    private val labels: ToolStatusLabels,
) {
    private val segments = mutableListOf<RenderSegment>()
    private val assistantText = StringBuilder()

    fun apply(event: LlmStreamEvent): List<LlmTextFrame> {
        return when (event) {
            LlmStreamEvent.RoundStarted -> listOf(
                LlmTextFrame(
                    text = renderSegments(),
                    isFirst = true,
                    isFinal = false,
                )
            )

            is LlmStreamEvent.TextDelta -> {
                appendText(event.delta)
                listOf(LlmTextFrame(renderSegments(), isFirst = false, isFinal = false))
            }

            is LlmStreamEvent.ToolRunning -> {
                upsertTool(event.call, labels.called)
                val calledFrame = LlmTextFrame(renderSegments(), isFirst = false, isFinal = false)
                upsertTool(event.call, labels.running)
                val runningFrame = LlmTextFrame(renderSegments(), isFirst = false, isFinal = false)
                listOf(calledFrame, runningFrame)
            }

            is LlmStreamEvent.ToolSucceeded -> {
                upsertTool(event.call, labels.success)
                listOf(LlmTextFrame(renderSegments(), isFirst = false, isFinal = false))
            }

            is LlmStreamEvent.ToolFailed -> {
                upsertTool(event.call, labels.failed)
                listOf(LlmTextFrame(renderSegments(), isFirst = false, isFinal = false))
            }

            is LlmStreamEvent.Error -> {
                val isFirstFrame = segments.isEmpty()
                appendError(event.message)
                listOf(
                    LlmTextFrame(
                        text = renderSegments(),
                        isFirst = isFirstFrame,
                        isFinal = true,
                    )
                )
            }

            is LlmStreamEvent.Completed -> {
                appendMissingFinalText(event.fullText)
                listOf(LlmTextFrame(renderSegments(), isFirst = false, isFinal = true))
            }
        }
    }

    private fun appendText(text: String) {
        if (text.isEmpty()) return
        assistantText.append(text)
        val last = segments.lastOrNull()
        if (last is RenderSegment.Text) {
            segments[segments.lastIndex] = last.copy(value = last.value + text)
        } else {
            segments += RenderSegment.Text(text)
        }
    }

    private fun appendMissingFinalText(finalText: String) {
        appendText(finalText.removePrefix(assistantText.toString()))
    }

    private fun appendError(message: String) {
        val normalized = message.trim()
        if (normalized.isEmpty()) return
        segments += RenderSegment.Error(normalized)
    }

    private fun upsertTool(call: ToolCallStatus, label: String) {
        val key = call.toolKey()
        val index = segments.indexOfFirst { segment ->
            segment is RenderSegment.Tool && segment.key == key
        }
        val tool = RenderSegment.Tool(key = key, name = call.label, label = label)
        if (index == -1) {
            segments += tool
        } else {
            segments[index] = tool
        }
    }

    private fun renderSegments(): String = segments.render()
}

private class ChunkTextProjector(
    private val labels: ToolStatusLabels,
) {
    private val fullText = StringBuilder()
    private val assistantText = StringBuilder()
    private var lastWasToolLine = false

    fun apply(event: LlmStreamEvent): List<LlmTextFrame> {
        return when (event) {
            LlmStreamEvent.RoundStarted -> listOf(
                LlmTextFrame(text = fullText.toString(), isFirst = true, isFinal = false)
            )

            is LlmStreamEvent.TextDelta -> {
                appendText(event.delta)
                listOf(LlmTextFrame(fullText.toString(), isFirst = false, isFinal = false))
            }

            is LlmStreamEvent.ToolRunning -> {
                appendToolLine(event.call, labels.called)
                val calledFrame =
                    LlmTextFrame(fullText.toString(), isFirst = false, isFinal = false)
                appendToolLine(event.call, labels.running)
                val runningFrame =
                    LlmTextFrame(fullText.toString(), isFirst = false, isFinal = false)
                listOf(calledFrame, runningFrame)
            }

            is LlmStreamEvent.ToolSucceeded -> {
                appendToolLine(event.call, labels.success)
                listOf(LlmTextFrame(fullText.toString(), isFirst = false, isFinal = false))
            }

            is LlmStreamEvent.ToolFailed -> {
                appendToolLine(event.call, labels.failed)
                listOf(LlmTextFrame(fullText.toString(), isFirst = false, isFinal = false))
            }

            is LlmStreamEvent.Error -> {
                val isFirstFrame = fullText.isEmpty()
                appendErrorLine(event.message)
                listOf(
                    LlmTextFrame(
                        text = fullText.toString(),
                        isFirst = isFirstFrame,
                        isFinal = true,
                    )
                )
            }

            is LlmStreamEvent.Completed -> {
                appendMissingFinalText(event.fullText)
                listOf(LlmTextFrame(fullText.toString(), isFirst = false, isFinal = true))
            }
        }
    }

    private fun appendText(text: String) {
        if (text.isEmpty()) return
        if (lastWasToolLine && fullText.isNotEmpty() && fullText.last() != '\n' && !text.startsWith(
                "\n"
            )
        ) {
            fullText.append('\n')
        }
        assistantText.append(text)
        fullText.append(text)
        lastWasToolLine = false
    }

    private fun appendMissingFinalText(finalText: String) {
        appendText(finalText.removePrefix(assistantText.toString()))
    }

    private fun appendToolLine(call: ToolCallStatus, label: String) {
        if (fullText.isNotEmpty() && fullText.last() != '\n') {
            fullText.append('\n')
        }
        fullText.append(call.toMarkdownLine(label))
        fullText.append('\n')
        lastWasToolLine = true
    }

    private fun appendErrorLine(message: String) {
        val normalized = message.trim()
        if (normalized.isEmpty()) return
        if (fullText.isNotEmpty() && fullText.last() != '\n') {
            fullText.append('\n')
        }
        fullText.append(normalized)
        lastWasToolLine = false
    }
}

private fun MutableList<RenderSegment>.render(): String {
    val builder = StringBuilder()
    forEach { segment ->
        when (segment) {
            is RenderSegment.Text -> builder.appendTextSegment(segment.value)
            is RenderSegment.Tool -> builder.appendToolSegment(segment)
            is RenderSegment.Error -> builder.appendTextSegment("## Error\n```\n${segment.value}\n```")
        }
    }
    return builder.toString()
}

private fun StringBuilder.appendTextSegment(text: String) {
    if (text.isEmpty()) return
    if (isNotEmpty() && last() != '\n' && !text.startsWith("\n")) {
        append('\n')
    }
    append(text)
}

private fun StringBuilder.appendToolSegment(tool: RenderSegment.Tool) {
    if (isNotEmpty() && last() != '\n') {
        append('\n')
    }
    append(tool.name.toMarkdownLine(tool.label))
}

private fun ToolCallStatus.toolKey(): String = callId ?: name

private fun ToolCallStatus.toMarkdownLine(label: String): String = this.label.toMarkdownLine(label)

private fun String.toMarkdownLine(label: String): String = "`[$this] $label`"
