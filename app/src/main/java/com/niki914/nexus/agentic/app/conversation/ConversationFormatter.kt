package com.niki914.nexus.agentic.app.conversation

import com.niki914.logging.Logger
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatBlock
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatTurn
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolState
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolStatus
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome

/**
 * T3 重写：消费 OKIA 会话树快照（SessionSnapshot）而非 Kai 时代 ChatTurn。
 * 渲染按 leaf 投影的线性消息列表，turn 边界 = Message.User 分组
 * （okia PRD §5.4：turn 分组由下游自行封装）。
 */
object ConversationFormatter {
    private const val LOG_TAG = "niki914_nexus_ConversationFormatter"
    private const val DEFAULT_TITLE = "新对话" // TODO languages
    private const val MAX_TITLE_LENGTH = 40
    private const val MAX_PREVIEW_LENGTH = 20
    private const val ELLIPSIS = "..."

    fun titleFromFirstInput(firstUserInput: String): String {
        val title = firstUserInput.trim()
        if (title.isEmpty()) return DEFAULT_TITLE
        return title.take(MAX_TITLE_LENGTH)
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
    fun previewFromMessages(messages: List<com.niki914.okia.conversation.MessageEntry>): String {
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
     * leafId 为 null 时按「恢复为最后一条」意图取 entries 最后一条
     * （绕 OKIA RealConversation.project(null) 返回空的坑，D16）。
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

    fun toHomeTurns(snapshot: SessionSnapshot): List<HomeChatTurn> {
        val startedAtMs = System.currentTimeMillis()
        val history = projectLeaf(snapshot.entries, snapshot.leafId)
        val turns = mutableListOf<HomeChatTurn>()
        var nextId = 0L

        history.forEach { entry ->
            when (val message = entry.message) {
                is Message.User -> {
                    turns += HomeChatTurn(id = nextId++, userText = message.textBlocks().joinToString("\n"))
                }

                is Message.Assistant -> {
                    val target = turns.lastOrNull() ?: HomeChatTurn(id = nextId++, userText = "")
                    val updated = target
                        .appendTextBlock(message.message.textBlocks().joinToString("\n"))
                        .appendToolBlocks(message.message)
                    turns.replaceLastOrAdd(updated)
                }

                is Message.ToolResult -> {
                    val target = turns.lastOrNull() ?: return@forEach
                    val (state, resultText, failedReason) = message.outcome.toHomeToolState()
                    val updated = target.updateToolState(
                        callId = message.callId,
                        toolName = message.toolName,
                        state = state,
                        resultText = resultText,
                        failedReason = failedReason,
                    )
                    turns.replaceLastOrAdd(updated)
                }
            }
        }

        return turns.also {
            Logger.i(
                LOG_TAG,
                "format history entries=${history.size} turns=${it.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun Message.User.textBlocks(): List<String> =
        content.filterIsInstance<ContentBlock.Text>().map { it.text }

    private fun com.niki914.okia.message.AssistantMessage.textBlocks(): List<String> =
        content.filterIsInstance<ContentBlock.Text>().map { it.text }

    private fun HomeChatTurn.appendTextBlock(text: String): HomeChatTurn {
        if (text.isBlank()) return this
        return copy(blocks = blocks + HomeChatBlock.Text(text))
    }

    private fun HomeChatTurn.appendToolBlocks(assistant: com.niki914.okia.message.AssistantMessage): HomeChatTurn {
        val toolCalls = assistant.content.filterIsInstance<ContentBlock.ToolCall>()
        if (toolCalls.isEmpty()) return this
        val toolBlocks = toolCalls.map { toolCall ->
            HomeChatBlock.Tool(
                HomeToolStatus(
                    callId = toolCall.id,
                    name = toolCall.name,
                    state = HomeToolState.Failed,
                ),
            )
        }
        return copy(blocks = blocks + toolBlocks)
    }

    private fun HomeChatTurn.updateToolState(
        callId: String,
        toolName: String,
        state: HomeToolState,
        resultText: String? = null,
        failedReason: String? = null,
    ): HomeChatTurn {
        val index = blocks.indexOfLast { block ->
            block is HomeChatBlock.Tool && block.status.matchesTool(callId, toolName)
        }
        if (index == -1) return this
        return copy(
            blocks = blocks.toMutableList().also { mutableBlocks ->
                val block = mutableBlocks[index] as HomeChatBlock.Tool
                mutableBlocks[index] = block.copy(
                    status = block.status.copy(
                        state = state,
                        resultText = resultText,
                        failedReason = failedReason,
                    ),
                )
            },
        )
    }

    private fun HomeToolStatus.matchesTool(callId: String, toolName: String): Boolean {
        if (this.callId != null) return this.callId == callId
        return name == toolName
    }

    /** outcome 5 态 → UI 工具块终态（Success 成功；Intercepted 按 isError；其余失败）。 */
    private fun ToolCallOutcome.toHomeToolState(): Triple<HomeToolState, String?, String?> =
        when (this) {
            is ToolCallOutcome.Success -> Triple(HomeToolState.Succeeded, content, null)
            is ToolCallOutcome.Failure -> Triple(HomeToolState.Failed, content, message)
            is ToolCallOutcome.Intercepted -> if (isError) {
                Triple(HomeToolState.Failed, content, reason)
            } else {
                Triple(HomeToolState.Succeeded, content, null)
            }
            is ToolCallOutcome.Interrupted -> Triple(HomeToolState.Failed, content, "Interrupted")
            is ToolCallOutcome.Unknown -> Triple(HomeToolState.Failed, content, message)
        }

    private fun MutableList<HomeChatTurn>.replaceLastOrAdd(turn: HomeChatTurn) {
        if (isEmpty()) {
            add(turn)
        } else {
            this[lastIndex] = turn
        }
    }
}
