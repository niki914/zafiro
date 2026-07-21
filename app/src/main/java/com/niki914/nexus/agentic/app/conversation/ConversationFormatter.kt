package com.niki914.nexus.agentic.app.conversation

import com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatBlock
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatTurn
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolState
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolStatus
import com.niki914.nexus.agentic.chat.agentic.stream.LocalToolResultClassifier
import com.niki914.s3ss10n.ChatTurn

object ConversationFormatter {
    private const val DEFAULT_TITLE = "新对话"
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

    fun previewFromHistory(history: List<ChatTurn>): String {
        val text = history.asReversed().firstNotNullOfOrNull { turn ->
            when (turn) {
                is ChatTurn.User -> turn.content.takeIf { it.isNotBlank() }
                is ChatTurn.Assistant -> turn.content.takeIf { it.isNotBlank() }
                is ChatTurn.ToolResult,
                is ChatTurn.System,
                    -> null
            }
        }.orEmpty()
        return previewFromText(text)
    }

    fun toHomeTurns(history: List<ChatTurn>): List<HomeChatTurn> {
        val turns = mutableListOf<HomeChatTurn>()
        var nextId = 0L

        history.forEach { turn ->
            when (turn) {
                is ChatTurn.User -> {
                    turns += HomeChatTurn(id = nextId++, userText = turn.content)
                }

                is ChatTurn.Assistant -> {
                    val target = turns.lastOrNull() ?: HomeChatTurn(id = nextId++, userText = "")
                    val updated = target
                        .appendTextBlock(turn.content)
                        .appendToolBlocks(turn)
                    turns.replaceLastOrAdd(updated)
                }

                is ChatTurn.ToolResult -> {
                    val target = turns.lastOrNull() ?: return@forEach
                    val updated = target.updateToolState(
                        callId = turn.callId,
                        toolName = turn.toolName,
                        state = if (LocalToolResultClassifier.failureMessage(turn.resultJson) == null) {
                            HomeToolState.Succeeded
                        } else {
                            HomeToolState.Failed
                        },
                    )
                    turns.replaceLastOrAdd(updated)
                }

                is ChatTurn.System -> Unit
            }
        }

        return turns
    }

    private fun HomeChatTurn.appendTextBlock(text: String): HomeChatTurn {
        if (text.isBlank()) return this
        return copy(blocks = blocks + HomeChatBlock.Text(text))
    }

    private fun HomeChatTurn.appendToolBlocks(turn: ChatTurn.Assistant): HomeChatTurn {
        if (turn.toolCalls.isEmpty()) return this
        val toolBlocks = turn.toolCalls.map { toolCall ->
            HomeChatBlock.Tool(
                HomeToolStatus(
                    callId = toolCall.callId,
                    name = toolCall.toolName,
                    state = HomeToolState.Succeeded,
                ),
            )
        }
        return copy(blocks = blocks + toolBlocks)
    }

    private fun HomeChatTurn.updateToolState(
        callId: String,
        toolName: String,
        state: HomeToolState,
    ): HomeChatTurn {
        val index = blocks.indexOfLast { block ->
            block is HomeChatBlock.Tool && block.status.matchesTool(callId, toolName)
        }
        if (index == -1) return this
        return copy(
            blocks = blocks.toMutableList().also { mutableBlocks ->
                val block = mutableBlocks[index] as HomeChatBlock.Tool
                mutableBlocks[index] = block.copy(status = block.status.copy(state = state))
            },
        )
    }

    private fun HomeToolStatus.matchesTool(callId: String, toolName: String): Boolean {
        return this.callId == callId || (this.callId.isNullOrBlank() && name == toolName)
    }

    private fun MutableList<HomeChatTurn>.replaceLastOrAdd(turn: HomeChatTurn) {
        if (isEmpty()) {
            add(turn)
        } else {
            this[lastIndex] = turn
        }
    }
}
