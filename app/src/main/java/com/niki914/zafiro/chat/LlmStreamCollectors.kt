package com.niki914.zafiro.chat

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

    /** 思考状态行：label 为 Thinking（思考中）或 Thought（完成），渲染为 `[label]`。 */
    data class Thinking(val label: String) : RenderSegment

    data class Error(val value: String) : RenderSegment

    /** 瞬时重试状态行：流恢复即移除。 */
    data class Retrying(val event: LlmStreamEvent.Retrying) : RenderSegment
}

private class FullTextProjector(
    private val labels: ToolStatusLabels,
) {
    private val segments = mutableListOf<RenderSegment>()
    private val assistantText = StringBuilder()
    private var thinkingShown = false

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

            // 思考状态行：思考中 `[Thinking]`，完成（含被掐）更新为 `[Thought]`。空文本不发。
            is LlmStreamEvent.ThinkingStarted -> {
                if (!thinkingShown && event.text.isNotBlank()) {
                    thinkingShown = true
                    segments += RenderSegment.Thinking("Thinking")
                }
                listOf(LlmTextFrame(renderSegments(), isFirst = false, isFinal = false))
            }

            is LlmStreamEvent.ThinkingEnded -> {
                if (thinkingShown) {
                    val index = segments.indexOfLast { it is RenderSegment.Thinking }
                    if (index != -1) {
                        segments[index] = RenderSegment.Thinking("Thought")
                    }
                }
                listOf(LlmTextFrame(renderSegments(), isFirst = false, isFinal = false))
            }

            is LlmStreamEvent.ToolPending -> listOf()

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
                appendError(event)
                listOf(
                    LlmTextFrame(
                        text = renderSegments(),
                        isFirst = isFirstFrame,
                        isFinal = true,
                    )
                )
            }

            is LlmStreamEvent.Retrying -> {
                // 瞬时状态行：与工具/思考行同层展示，流恢复后由后续事件自然覆盖消失
                segments += RenderSegment.Retrying(event)
                listOf(LlmTextFrame(renderSegments(), isFirst = false, isFinal = false))
            }

            is LlmStreamEvent.Completed ->
                listOf(LlmTextFrame(renderSegments(), isFirst = false, isFinal = true))
        }
    }

    private fun appendText(text: String) {
        if (text.isEmpty()) return
        // 流恢复（重试成功 / 正常续传）：瞬时 retry 状态行退场
        segments.removeAll { it is RenderSegment.Retrying }
        assistantText.append(text)
        val last = segments.lastOrNull()
        if (last is RenderSegment.Text) {
            segments[segments.lastIndex] = last.copy(value = last.value + text)
        } else {
            segments += RenderSegment.Text(text)
        }
    }

    private fun appendError(event: LlmStreamEvent.Error) {
        // message 为空（IdleTimeout/守卫错误等无原文场景）时不注入空 Error 块：
        // code 类型由宿主侧状态行不表达，本路径只呈现原文，空 = 静默终态
        val normalized = event.message?.trim() ?: return
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
    private var thinkingShown = false

    fun apply(event: LlmStreamEvent): List<LlmTextFrame> {
        return when (event) {
            LlmStreamEvent.RoundStarted -> listOf(
                LlmTextFrame(text = fullText.toString(), isFirst = true, isFinal = false)
            )

            is LlmStreamEvent.TextDelta -> {
                appendText(event.delta)
                listOf(LlmTextFrame(fullText.toString(), isFirst = false, isFinal = false))
            }

            // 思考状态行：思考中 `[Thinking]`；完成后追加 `[Thought]`（增量流只追加，与工具行一致）。
            is LlmStreamEvent.ThinkingStarted -> {
                if (!thinkingShown && event.text.isNotBlank()) {
                    thinkingShown = true
                    appendThinkingLine("Thinking")
                }
                listOf(LlmTextFrame(fullText.toString(), isFirst = false, isFinal = false))
            }

            is LlmStreamEvent.ThinkingEnded -> {
                if (thinkingShown) {
                    appendThinkingLine("Thought")
                }
                listOf(LlmTextFrame(fullText.toString(), isFirst = false, isFinal = false))
            }

            is LlmStreamEvent.ToolPending -> listOf()

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
                appendErrorLine(event)
                listOf(
                    LlmTextFrame(
                        text = fullText.toString(),
                        isFirst = isFirstFrame,
                        isFinal = true,
                    )
                )
            }

            is LlmStreamEvent.Retrying -> {
                appendRetryLine(event)
                listOf(LlmTextFrame(fullText.toString(), isFirst = false, isFinal = false))
            }

            is LlmStreamEvent.Completed ->
                listOf(LlmTextFrame(fullText.toString(), isFirst = false, isFinal = true))
        }
    }

    private fun appendText(text: String) {
        if (text.isEmpty()) return
        // 流恢复：瞬时 retry 状态行退场
        if (retryLineStart >= 0 && retryLineStart <= fullText.length) {
            fullText.delete(retryLineStart, fullText.length)
            retryLineStart = -1
        }
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

    private fun appendToolLine(call: ToolCallStatus, label: String) {
        if (fullText.isNotEmpty() && fullText.last() != '\n') {
            fullText.append('\n')
        }
        fullText.append(call.toMarkdownLine(label))
        fullText.append('\n')
        lastWasToolLine = true
    }

    private fun appendThinkingLine(label: String) {
        if (fullText.isNotEmpty() && fullText.last() != '\n') {
            fullText.append('\n')
        }
        fullText.append("`[$label]`")
        fullText.append('\n')
        lastWasToolLine = true
    }

    private var retryLineStart = -1

    private fun appendRetryLine(event: LlmStreamEvent.Retrying) {
        // 同回合只有一行 retry 状态：替换旧行（记录行起点），流恢复时移除
        if (retryLineStart >= 0 && retryLineStart <= fullText.length) {
            fullText.delete(retryLineStart, fullText.length)
        }
        if (fullText.isNotEmpty() && fullText.last() != '\n') {
            fullText.append('\n')
        }
        retryLineStart = fullText.length
        fullText.append("`[Retrying ${event.attempt}/${event.maxAttempts}]`")
        fullText.append('\n')
        lastWasToolLine = true
    }

    private fun appendErrorLine(event: LlmStreamEvent.Error) {
        // 同 Full 路径：message 为空不注入空错误行
        val normalized = event.message?.trim() ?: return
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
            is RenderSegment.Thinking -> builder.appendThinkingSegment(segment.label)
            is RenderSegment.Retrying -> builder.appendThinkingSegment("Retrying ${segment.event.attempt}/${segment.event.maxAttempts}")
            // "## Error" 是注入宿主 markdown 的代码块结构标题，本地化会破坏注入内容一致性，保持原样
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

private fun StringBuilder.appendThinkingSegment(label: String) {
    if (isNotEmpty() && last() != '\n') {
        append('\n')
    }
    append("`[$label]`")
}

private fun ToolCallStatus.toolKey(): String = callId ?: name

private fun ToolCallStatus.toMarkdownLine(label: String): String = this.label.toMarkdownLine(label)

private fun String.toMarkdownLine(label: String): String = "`[$this] $label`"
