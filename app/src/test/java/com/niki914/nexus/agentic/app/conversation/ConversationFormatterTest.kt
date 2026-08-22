package com.niki914.nexus.agentic.app.conversation

import com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatBlock
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolState
import com.niki914.nexus.agentic.app.util.SilentLoggerRule
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConversationFormatterTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @Test
    fun toHomeTurns_groupsByUserTurn() {
        val snapshot = snapshotOf(
            Message.User(listOf(ContentBlock.Text("first"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("answer 1")))),
            Message.User(listOf(ContentBlock.Text("second"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("answer 2")))),
        )

        val turns = ConversationFormatter.toHomeTurns(snapshot)

        assertEquals(2, turns.size)
        assertEquals("first", turns[0].userText)
        assertEquals("second", turns[1].userText)
        assertEquals(
            listOf("answer 1"),
            turns[0].blocks.filterIsInstance<HomeChatBlock.Text>().map { it.text },
        )
        assertEquals(
            listOf("answer 2"),
            turns[1].blocks.filterIsInstance<HomeChatBlock.Text>().map { it.text },
        )
    }

    @Test
    fun toHomeTurns_restoresToolSuccessFromOutcome() {
        val snapshot = snapshotOf(
            Message.User(listOf(ContentBlock.Text("question"))),
            Message.Assistant(
                AssistantMessage(
                    listOf(
                        ContentBlock.Text("let me tap"),
                        ContentBlock.ToolCall("c1", "screen_operation_accessibility", "{}"),
                    ),
                ),
            ),
            Message.ToolResult(
                callId = "c1",
                toolName = "screen_operation_accessibility",
                outcome = ToolCallOutcome.Success(content = "tree yaml"),
            ),
        )

        val turns = ConversationFormatter.toHomeTurns(snapshot)
        val toolBlock = turns.single().blocks.last() as HomeChatBlock.Tool
        assertEquals(HomeToolState.Succeeded, toolBlock.status.state)
        assertEquals("tree yaml", toolBlock.status.resultText)
    }

    @Test
    fun toHomeTurns_restoresToolFailureFromOutcome() {
        val snapshot = snapshotOf(
            Message.User(listOf(ContentBlock.Text("question"))),
            Message.Assistant(
                AssistantMessage(
                    listOf(
                        ContentBlock.ToolCall("c1", "screen_operation_accessibility", "{}"),
                    ),
                ),
            ),
            Message.ToolResult(
                callId = "c1",
                toolName = "screen_operation_accessibility",
                outcome = ToolCallOutcome.Failure(
                    message = "Token expired",
                    content = "yaml",
                ),
            ),
        )

        val turns = ConversationFormatter.toHomeTurns(snapshot)
        val toolBlock = turns.single().blocks.last() as HomeChatBlock.Tool
        assertEquals(HomeToolState.Failed, toolBlock.status.state)
        assertEquals("Token expired", toolBlock.status.failedReason)
    }

    @Test
    fun toHomeTurns_interceptedErrorShowsFailed() {
        val snapshot = snapshotOf(
            Message.User(listOf(ContentBlock.Text("q"))),
            Message.Assistant(
                AssistantMessage(listOf(ContentBlock.ToolCall("c1", "mcp__s__t", "{}"))),
            ),
            Message.ToolResult(
                callId = "c1",
                toolName = "mcp__s__t",
                outcome = ToolCallOutcome.Intercepted(reason = "denied", isError = true),
            ),
        )

        val turns = ConversationFormatter.toHomeTurns(snapshot)
        val toolBlock = turns.single().blocks.last() as HomeChatBlock.Tool
        assertEquals(HomeToolState.Failed, toolBlock.status.state)
    }

    @Test
    fun toHomeTurns_ignoresThinkingBlocks() {
        val snapshot = snapshotOf(
            Message.User(listOf(ContentBlock.Text("q"))),
            Message.Assistant(
                AssistantMessage(
                    listOf(
                        ContentBlock.Thinking("hidden reasoning"),
                        ContentBlock.Text("visible"),
                    ),
                ),
            ),
        )

        val turns = ConversationFormatter.toHomeTurns(snapshot)
        val textBlocks = turns.single().blocks.filterIsInstance<HomeChatBlock.Text>()
        assertEquals(listOf("visible"), textBlocks.map { it.text })
    }

    @Test
    fun toHomeTurns_unpairedToolCallDefaultsToFailedPlaceholder() {
        val snapshot = snapshotOf(
            Message.User(listOf(ContentBlock.Text("q"))),
            Message.Assistant(
                AssistantMessage(listOf(ContentBlock.ToolCall("c1", "search", "{}"))),
            ),
        )

        val turns = ConversationFormatter.toHomeTurns(snapshot)
        val toolBlock = turns.single().blocks.last() as HomeChatBlock.Tool
        assertEquals(HomeToolState.Failed, toolBlock.status.state)
    }

    @Test
    fun projectLeaf_followsParentChainToRoot() {
        val entries = listOf(
            ConversationEntry("e0", null, 0L, Message.User(listOf(ContentBlock.Text("a")))),
            ConversationEntry("e1", "e0", 1L, Message.User(listOf(ContentBlock.Text("b")))),
            ConversationEntry("e2", "e1", 2L, Message.User(listOf(ContentBlock.Text("c")))),
        )

        val projected = ConversationFormatter.projectLeaf(entries, "e1")

        assertEquals(listOf("e0", "e1"), projected.map { it.id })
    }

    @Test
    fun projectLeaf_nullLeafFallsBackToLastEntry() {
        val entries = listOf(
            ConversationEntry("e0", null, 0L, Message.User(listOf(ContentBlock.Text("a")))),
            ConversationEntry("e1", "e0", 1L, Message.User(listOf(ContentBlock.Text("b")))),
        )

        val projected = ConversationFormatter.projectLeaf(entries, null)

        assertEquals(listOf("e0", "e1"), projected.map { it.id })
    }

    @Test
    fun previewFromEntries_usesLatestNonEmptyMessage() {
        val entries = listOf(
            ConversationEntry("e0", null, 0L, Message.User(listOf(ContentBlock.Text("q")))),
            ConversationEntry("e1", "e0", 1L, Message.ToolResult("c1", "t", ToolCallOutcome.Success("r"))),
        )

        assertEquals("q", ConversationFormatter.previewFromEntries(entries))
    }

    private fun snapshotOf(vararg messages: Message): SessionSnapshot {
        var parent: String? = null
        val entries = messages.mapIndexed { index, message ->
            val entry = ConversationEntry(
                id = "e$index",
                parentId = parent,
                timestamp = 1000L + index,
                message = message,
            )
            parent = entry.id
            entry
        }
        return SessionSnapshot(
            id = "session-1",
            leafId = entries.lastOrNull()?.id,
            version = 1,
            entries = entries,
        )
    }
}
