package com.niki914.zafiro.runtime.service

import com.niki914.zafiro.api.model.ConversationTurn
import com.niki914.zafiro.api.model.ToolOutcome
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.api.model.TurnFailureCode
import com.niki914.zafiro.chat.ToolStatusLabels

/**
 * 将 [ConversationTurn] 转换为宿主（语音助手）渲染所需的纯文本 Markdown 帧。
 */
internal object HostConversationProjector {

    fun render(
        turn: ConversationTurn,
        labels: ToolStatusLabels,
        resolveErrorMessage: (TurnFailureCode?) -> String = { "Internal error" },
    ): String {
        val sb = StringBuilder()
        for (block in turn.blocks) {
            when (block) {
                is TurnBlock.Thinking -> {
                    val text = block.text.trim()
                    if (text.isNotEmpty()) {
                        val quoted = text.lines().joinToString("\n") { line ->
                            if (line.isEmpty()) ">" else "> $line"
                        }
                        appendBlockquote(sb, quoted)
                    }
                }

                is TurnBlock.Tool -> {
                    val statusLabel = when (block.outcome) {
                        null -> labels.running
                        is ToolOutcome.Succeeded -> labels.success
                        is ToolOutcome.Failed -> labels.failed
                    }
                    appendLine(sb, "`[${block.invocation.name}] $statusLabel`")
                }

                is TurnBlock.Text -> {
                    if (block.text.isNotEmpty()) {
                        appendText(sb, block.text)
                    }
                }

                is TurnBlock.Failure -> {
                    val msg = block.message?.trim()?.takeIf { it.isNotEmpty() }
                        ?: resolveErrorMessage(block.code)
                    appendLine(sb, msg)
                }

                is TurnBlock.Retrying -> {
                    appendLine(sb, "`[retrying] ${block.attempt}/${block.maxAttempts}`")
                }
            }
        }
        return sb.toString().trimEnd()
    }

    private fun appendBlockquote(sb: StringBuilder, quoted: String) {
        if (sb.isNotEmpty()) {
            if (sb.last() != '\n') sb.append('\n')
            if (!sb.endsWith("\n\n")) sb.append('\n')
        }
        sb.append(quoted)
        sb.append("\n\n")
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
