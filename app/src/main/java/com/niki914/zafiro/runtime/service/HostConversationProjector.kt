package com.niki914.zafiro.runtime.service

import com.niki914.zafiro.api.model.ConversationTurn
import com.niki914.zafiro.api.model.ToolOutcome
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.api.model.TurnFailureCode
import com.niki914.zafiro.chat.ToolStatusLabels
import com.niki914.zafiro.runtime.ipc.ToolItem
import com.niki914.zafiro.runtime.ipc.ToolStatus

/**
 * 将 [ConversationTurn] 转换为宿主渲染所需的语义数据帧。
 * 本层只负责数据结构归一化，绝对不包含任何表现层格式化字符（如 '>'、'`[tool] status`'）。
 */
internal object HostConversationProjector {

    data class ProjectedFrame(
        val content: String,
        val thinking: String? = null,
        val isThinkingComplete: Boolean = false,
        val tools: List<ToolItem> = emptyList(),
    )

    fun project(
        turn: ConversationTurn,
        resolveErrorMessage: (TurnFailureCode?) -> String = { "Internal error" },
    ): ProjectedFrame {
        var thinking: String? = null
        var isThinkingComplete = false
        val tools = mutableListOf<ToolItem>()
        val contentBuilder = StringBuilder()

        for (block in turn.blocks) {
            when (block) {
                is TurnBlock.Thinking -> {
                    val raw = block.text.trim()
                    if (raw.isNotEmpty()) {
                        thinking = raw
                        isThinkingComplete = block.isComplete
                    }
                }

                is TurnBlock.Tool -> {
                    val status = when (block.outcome) {
                        null -> ToolStatus.RUNNING
                        is ToolOutcome.Succeeded -> ToolStatus.SUCCESS
                        is ToolOutcome.Failed -> ToolStatus.FAILED
                    }
                    tools.add(ToolItem(name = block.invocation.name, status = status))
                }

                is TurnBlock.Text -> {
                    if (block.text.isNotEmpty()) {
                        appendText(contentBuilder, block.text)
                    }
                }

                is TurnBlock.Failure -> {
                    val msg = block.message?.trim()?.takeIf { it.isNotEmpty() }
                        ?: resolveErrorMessage(block.code)
                    appendLine(contentBuilder, msg)
                }

                is TurnBlock.Retrying -> {
                    appendLine(contentBuilder, "`[retrying] ${block.attempt}/${block.maxAttempts}`")
                }
            }
        }

        return ProjectedFrame(
            content = contentBuilder.toString().trimEnd(),
            thinking = thinking,
            isThinkingComplete = isThinkingComplete,
            tools = tools,
        )
    }

    /**
     * 兼容旧版调用的 Markdown 纯文本投影。
     */
    fun render(
        turn: ConversationTurn,
        labels: ToolStatusLabels,
        resolveErrorMessage: (TurnFailureCode?) -> String = { "Internal error" },
    ): String {
        val projected = project(turn, resolveErrorMessage)
        val sb = StringBuilder()
        if (!projected.thinking.isNullOrBlank()) {
            val quoted = projected.thinking.lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
            sb.append(quoted).append("\n\n")
        }
        for (tool in projected.tools) {
            val label = when (tool.status) {
                ToolStatus.RUNNING -> labels.running
                ToolStatus.SUCCESS -> labels.success
                ToolStatus.FAILED -> labels.failed
            }
            appendLine(sb, "`[${tool.name}] $label`")
        }
        if (projected.content.isNotEmpty()) {
            appendText(sb, projected.content)
        }
        return sb.toString().trimEnd()
    }

    private fun appendLine(sb: StringBuilder, line: String) {
        if (sb.isNotEmpty() && sb.last() != '\n') {
            sb.append('\n')
        }
        sb.append(line)
    }

    private fun appendText(sb: StringBuilder, text: String) {
        if (sb.isNotEmpty() && sb.last() != '\n') {
            sb.append('\n')
        }
        sb.append(text)
    }
}
