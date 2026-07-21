package com.niki914.nexus.agentic.app.conversation

import com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatBlock
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolState
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolStatus
import com.niki914.s3ss10n.ChatTurn
import com.niki914.s3ss10n.ToolCallSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationFormatterTest {
    @Test
    fun previewFromText_trimsAndKeepsEmptyOrShortText() {
        assertEquals("", ConversationFormatter.previewFromText("   "))
        assertEquals("short text", ConversationFormatter.previewFromText("  short text  "))
    }

    @Test
    fun previewFromText_keepsExactlyTwentyCharacters() {
        assertEquals(
            "12345678901234567890",
            ConversationFormatter.previewFromText("12345678901234567890")
        )
    }

    @Test
    fun previewFromText_truncatesLongTextWithEllipsis() {
        assertEquals(
            "12345678901234567890...",
            ConversationFormatter.previewFromText("123456789012345678901")
        )
    }

    @Test
    fun previewFromHistory_ignoresToolResultAndUsesLatestTextTurn() {
        val history = listOf(
            ChatTurn.User("first"),
            ChatTurn.ToolResult(callId = "call-1", toolName = "search", resultJson = "{}"),
            ChatTurn.Assistant("latest assistant"),
            ChatTurn.ToolResult(callId = "call-2", toolName = "calc", resultJson = "{}"),
        )

        assertEquals("latest assistant", ConversationFormatter.previewFromHistory(history))
    }

    @Test
    fun toHomeTurns_mapsUserAssistantTextAndToolCalls() {
        val turns = ConversationFormatter.toHomeTurns(
            listOf(
                ChatTurn.System("ignored"),
                ChatTurn.User("question"),
                ChatTurn.Assistant(
                    content = "answer",
                    toolCalls = listOf(ToolCallSpec("call-1", "search", "{}")),
                ),
                ChatTurn.ToolResult(callId = "call-1", toolName = "search", resultJson = "{}"),
                ChatTurn.User("next"),
            ),
        )

        assertEquals(2, turns.size)
        assertEquals("question", turns[0].userText)
        assertEquals(
            listOf(
                HomeChatBlock.Text("answer"),
                HomeChatBlock.Tool(HomeToolStatus("call-1", "search", HomeToolState.Succeeded)),
            ),
            turns[0].blocks,
        )
        assertEquals("next", turns[1].userText)
        assertEquals(emptyList<HomeChatBlock>(), turns[1].blocks)
    }

    @Test
    fun toHomeTurns_restoresToolStateFromToolResults() {
        val turns = ConversationFormatter.toHomeTurns(
            listOf(
                ChatTurn.User("question"),
                ChatTurn.Assistant(
                    content = "answer",
                    toolCalls = listOf(
                        ToolCallSpec("ok-call", "search", "{}"),
                        ToolCallSpec("failed-ok-call", "memory", "{}"),
                        ToolCallSpec("failed-exit-call", "command", "{}"),
                    ),
                ),
                ChatTurn.ToolResult(
                    callId = "ok-call",
                    toolName = "search",
                    resultJson = """{"ok":true}""",
                ),
                ChatTurn.ToolResult(
                    callId = "failed-ok-call",
                    toolName = "memory",
                    resultJson = """{"ok":false,"message":"denied"}""",
                ),
                ChatTurn.ToolResult(
                    callId = "failed-exit-call",
                    toolName = "command",
                    resultJson = """{"exit_code":"2","stderr":"boom"}""",
                ),
            ),
        )

        assertEquals(
            listOf(
                HomeChatBlock.Text("answer"),
                HomeChatBlock.Tool(HomeToolStatus("ok-call", "search", HomeToolState.Succeeded)),
                HomeChatBlock.Tool(
                    HomeToolStatus(
                        "failed-ok-call",
                        "memory",
                        HomeToolState.Failed
                    )
                ),
                HomeChatBlock.Tool(
                    HomeToolStatus(
                        "failed-exit-call",
                        "command",
                        HomeToolState.Failed
                    )
                ),
            ),
            turns.single().blocks,
        )
    }
}
