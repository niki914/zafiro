package com.niki914.zafiro.app.conversation

import com.niki914.logging.Logger
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.MessageEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.ConversationTurn
import com.niki914.zafiro.api.model.ToolInvocation
import com.niki914.zafiro.api.model.ToolOutcome
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.business.agent.blockIdAt
import com.niki914.zafiro.business.agent.turnIdAt

/**
 * T3 重写：消费 OKIA 会话树快照（SessionSnapshot）而非 Kai 时代 ChatTurn。
 * 渲染按 leaf 投影的线性消息列表，turn 边界 = Message.User 分组
 * （okia PRD §5.4：turn 分组由下游自行封装）。
 */
object ConversationFormatter {
    private const val LOG_TAG = "niki914_nexus_ConversationFormatter"
    private const val MAX_TITLE_LENGTH = 40
    private const val MAX_PREVIEW_LENGTH = 20
    private const val ELLIPSIS = "..."

    /** 没有配对 `ToolResult` 的工具调用：恢复后记为失败，且无失败原因。 */
    private const val UNPAIRED_TOOL_REASON = ""

    fun titleFromFirstInput(firstUserInput: String): String {
        return firstUserInput.trim().take(MAX_TITLE_LENGTH)
    }

    fun previewFromText(text: String): String {
        val trimmed = text.trim()
        if (trimmed.length <= MAX_PREVIEW_LENGTH) return trimmed
        return trimmed.take(MAX_PREVIEW_LENGTH) + ELLIPSIS
    }

    fun previewFromEntries(entries: List<ConversationEntry>): String {
        val text = entries.asReversed().firstNotNullOfOrNull { entry ->
            previewTextOf(entry.message).takeIf { it.isNotEmpty() }
        }.orEmpty()
        return previewFromText(text)
    }

    /** 基于 leaf 投影消息（MessageEntry）取预览：持久化器增量写入时用完整 history 而非仅新条目。 */
    fun previewFromMessages(messages: List<MessageEntry>): String {
        val text = messages.asReversed().firstNotNullOfOrNull { entry ->
            previewTextOf(entry.message).takeIf { it.isNotEmpty() }
        }.orEmpty()
        return previewFromText(text)
    }

    private fun previewTextOf(message: Message?): String {
        val text = when (message) {
            is Message.User -> message.textBlocks().joinToString("\n")
            is Message.Assistant -> message.message.textBlocks().joinToString("\n")
            else -> ""
        }
        return text.trim()
    }

    /**
     * leaf 投影：沿 leafId 的 parent 链回溯再反转，得到根到 leaf 的线性列表。
     * leafId 为 null 时取 entries 最后一条（对齐 OKIA §5.3 恢复语义）。
     */
    fun projectLeaf(entries: List<ConversationEntry>, leafId: String?): List<ConversationEntry> {
        if (entries.isEmpty()) return emptyList()
        val byId = entries.associateBy { it.id }
        val target = leafId ?: entries.lastOrNull()?.id
        val chain = buildList {
            var cursor = byId[target]
            while (cursor != null) {
                add(cursor)
                cursor = cursor.parentId?.let(byId::get)
            }
        }
        return chain.reversed()
    }

    /** 历史恢复装配成契约类型，与流式归约共用回合与块 id 规则。 */
    fun toConversation(snapshot: SessionSnapshot): Conversation {
        val startedAtMs = System.currentTimeMillis()
        val history = projectLeaf(snapshot.entries, snapshot.leafId)
        val turns = mutableListOf<ConversationTurn>()

        history.forEach { entry ->
            when (val message = entry.message) {
                is Message.User -> turns += ConversationTurn(
                    id = turnIdAt(turns.size),
                    userText = message.textBlocks().joinToString("\n"),
                    attachments = message.content.filterIsInstance<ContentBlock.Image>()
                        .map { Attachment(path = it.path, mimeType = it.mimeType) },
                )

                is Message.Assistant -> {
                    val target = turns.lastOrNull()
                    if (target == null) {
                        // 没有前置用户消息的助手条目（异常快照）：丢弃，不造空回合
                        Logger.w(
                            LOG_TAG,
                            "assistant entry skipped entryId=${entry.id} reason=noUserTurn",
                        )
                    } else {
                        turns[turns.lastIndex] =
                            target.appendAssistantContent(message.message, turns.lastIndex)
                    }
                }

                is Message.ToolResult -> {
                    val target = turns.lastOrNull() ?: return@forEach
                    turns[turns.lastIndex] = target.applyToolOutcome(message)
                }
            }
        }

        return Conversation(id = ConversationId(snapshot.id), turns = turns).also { assembled ->
            Logger.i(
                LOG_TAG,
                "assemble conversation entries=${history.size} turns=${assembled.turns.size} " +
                        "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 按 `assistant.content` 原序追加块，与流式归约（事件到达顺序）同一条规则。
     *
     * 块 id 是下标，两条路径顺序不一致会让同一下标指向不同的块。引擎提交的
     * `content` 本身交错（想 → 答 → 再想 → 再答 → 调工具），按类型分批就排列不同。
     */
    private fun ConversationTurn.appendAssistantContent(
        assistant: AssistantMessage,
        turnIndex: Int,
    ): ConversationTurn {
        var result = this
        assistant.content.forEach { block ->
            result = when (block) {
                is ContentBlock.Thinking -> {
                    if (block.text.isBlank()) {
                        result
                    } else {
                        result.copy(
                            blocks = result.blocks + TurnBlock.Thinking(
                                id = blockIdAt(turnIndex, result.blocks.size),
                                text = block.text,
                                // 恢复后不再流式：块已结束
                                isComplete = true,
                            ),
                        )
                    }
                }

                is ContentBlock.Text -> {
                    if (block.text.isBlank()) {
                        result
                    } else {
                        result.appendText(
                            text = block.text,
                            id = blockIdAt(turnIndex, result.blocks.size),
                        )
                    }
                }

                is ContentBlock.ToolCall -> result.copy(
                    blocks = result.blocks + TurnBlock.Tool(
                        id = blockIdAt(turnIndex, result.blocks.size),
                        invocation = ToolInvocation(
                            id = block.id,
                            name = block.name,
                            label = block.name,
                            argumentsJson = block.argumentsJson,
                        ),
                        // 有配对 ToolResult 时由 applyToolOutcome 覆盖；没有 = 回合被打断
                        outcome = ToolOutcome.Failed(message = UNPAIRED_TOOL_REASON),
                    ),
                )

                // 助手消息里的图片不单独成块（旧装配同口径）
                is ContentBlock.Image -> result
            }
        }
        return result
    }

    /** 相邻文本段原位合并，段边界（工具块之后）新起一块；与归约器 `appendText` 同规则。 */
    private fun ConversationTurn.appendText(text: String, id: String): ConversationTurn {
        val last = blocks.lastOrNull()
        return if (last is TurnBlock.Text) {
            copy(blocks = blocks.dropLast(1) + last.copy(text = last.text + text))
        } else {
            copy(blocks = blocks + TurnBlock.Text(id = id, text = text))
        }
    }

    private fun ConversationTurn.applyToolOutcome(message: Message.ToolResult): ConversationTurn {
        val index = blocks.indexOfLast { block ->
            block is TurnBlock.Tool && block.invocation.matches(message.callId)
        }
        if (index == -1) return this
        val block = blocks[index] as TurnBlock.Tool
        return copy(
            blocks = blocks.toMutableList().also {
                it[index] = block.copy(outcome = message.outcome.toToolOutcome())
            },
        )
    }

    /** OKIA 工具结果按 callId 匹配对应工具调用。 */
    private fun ToolInvocation.matches(callId: String): Boolean = id == callId

    /** outcome 5 态 → 契约工具结果（Intercepted 按 isError 分成功/失败）。 */
    private fun ToolCallOutcome.toToolOutcome(): ToolOutcome = when (this) {
        is ToolCallOutcome.Success -> ToolOutcome.Succeeded(
            resultText = content,
            images = images.map { Attachment(it.path, it.mimeType) },
        )

        is ToolCallOutcome.Failure -> ToolOutcome.Failed(message = message, resultText = content)
        is ToolCallOutcome.Intercepted -> if (isError) {
            ToolOutcome.Failed(message = reason, resultText = content)
        } else {
            ToolOutcome.Succeeded(resultText = content)
        }

        is ToolCallOutcome.Interrupted -> ToolOutcome.Failed(message = "Interrupted", resultText = content)
        is ToolCallOutcome.Unknown -> ToolOutcome.Failed(message = message, resultText = content)
    }

    private fun Message.User.textBlocks(): List<String> =
        content.filterIsInstance<ContentBlock.Text>().map { it.text }

    private fun AssistantMessage.textBlocks(): List<String> =
        content.filterIsInstance<ContentBlock.Text>().map { it.text }
}
