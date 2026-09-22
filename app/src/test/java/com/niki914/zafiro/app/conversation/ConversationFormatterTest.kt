package com.niki914.zafiro.app.conversation

import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.ToolOutcome
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.app.ui.model.HomeChatBlock
import com.niki914.zafiro.app.ui.model.HomeToolState
import com.niki914.zafiro.app.util.SilentLoggerRule
import org.junit.Assert.assertEquals
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
            ConversationEntry(
                "e1",
                "e0",
                1L,
                Message.ToolResult("c1", "t", ToolCallOutcome.Success("r"))
            ),
        )

        assertEquals("q", ConversationFormatter.previewFromEntries(entries))
    }

    @Test
    fun toHomeTurns_restoresUserImagesFromSandboxPaths() {
        val snapshot = snapshotOf(
            Message.User(
                listOf(
                    ContentBlock.Text("look"),
                    ContentBlock.Image("/data/user/0/com.niki914.zafiro/files/image_cache/aaa.jpg", "image/jpeg"),
                    ContentBlock.Image("/data/user/0/com.niki914.zafiro/files/image_cache/bbb.jpg", "image/jpeg"),
                )
            ),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("answer")))),
        )

        val turns = ConversationFormatter.toHomeTurns(snapshot)

        // 恢复投影：Image 块 → HomeChatImage（path 持久化，字节在沙箱重启不丢）
        val turn = turns.single()
        assertEquals("look", turn.userText)
        assertEquals(2, turn.images.size)
        assertEquals(
            "/data/user/0/com.niki914.zafiro/files/image_cache/aaa.jpg",
            turn.images[0].path,
        )
        assertEquals(
            "/data/user/0/com.niki914.zafiro/files/image_cache/aaa.jpg".hashCode().toString(),
            turn.images[0].id,
        )
    }

    @Test
    fun toHomeTurns_imageOnlyUserTurnKeepsImagesWithoutText() {
        val snapshot = snapshotOf(
            Message.User(
                listOf(
                    ContentBlock.Image("/data/user/0/com.niki914.zafiro/files/image_cache/aaa.jpg", "image/jpeg"),
                )
            ),
        )

        val turns = ConversationFormatter.toHomeTurns(snapshot)

        assertEquals(1, turns.size)
        assertEquals("", turns.single().userText)
        assertEquals(1, turns.single().images.size)
    }

    @Test
    fun toConversation_reassemblyYieldsIdenticalIds() {
        val snapshot = snapshotOf(
            Message.User(listOf(ContentBlock.Text("first"))),
            Message.Assistant(
                AssistantMessage(
                    listOf(
                        ContentBlock.Thinking("thought"),
                        ContentBlock.Text("answer 1"),
                        ContentBlock.ToolCall("c1", "search", "{}"),
                    ),
                ),
            ),
            Message.ToolResult(
                callId = "c1",
                toolName = "search",
                outcome = ToolCallOutcome.Success(content = "ok"),
            ),
            Message.User(listOf(ContentBlock.Text("second"))),
        )

        val first = ConversationFormatter.toConversation(snapshot)
        val second = ConversationFormatter.toConversation(snapshot)

        assertEquals(first, second)
        assertEquals(ConversationId("session-1"), first.id)
        assertEquals(listOf("t0", "t1"), first.turns.map { it.id.value })
        assertEquals(
            listOf("t0:0", "t0:1", "t0:2"),
            first.turns.first().blocks.map { it.id },
        )
    }

    @Test
    fun toConversation_keepsAttachmentMimeTypeAndThinkingCompletion() {
        val snapshot = snapshotOf(
            Message.User(
                listOf(
                    ContentBlock.Text("看图"),
                    ContentBlock.Image("/files/a.png", "image/png"),
                ),
            ),
            Message.Assistant(
                AssistantMessage(listOf(ContentBlock.Thinking("想一想"))),
            ),
        )

        val conversation = ConversationFormatter.toConversation(snapshot)
        val turn = conversation.turns.single()

        assertEquals(listOf(Attachment("/files/a.png", "image/png")), turn.attachments)
        val thinking = turn.blocks.single() as TurnBlock.Thinking
        assertEquals("想一想", thinking.text)
        // 恢复后不再流式：块已结束
        assertEquals(true, thinking.isComplete)
    }

    @Test
    fun toConversation_skipsAssistantWithoutUserTurn() {
        val snapshot = snapshotOf(
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("orphan")))),
            Message.User(listOf(ContentBlock.Text("first"))),
        )

        val conversation = ConversationFormatter.toConversation(snapshot)

        assertEquals(1, conversation.turns.size)
        assertEquals("first", conversation.turns.single().userText)
        assertEquals("t0", conversation.turns.single().id.value)
    }

    /**
     * 块序必须跟 `assistant.content` 的原序一致。按类型分批（旧装配的写法）会让
     * 「想 → 答 → 再想 → 再答 → 调工具」排成 Thinking×2 → Text → Tool，
     * 下标 id 与流式归约（事件到达顺序）对不上。
     */
    @Test
    fun toConversation_keepsAssistantContentOrder() {
        val snapshot = snapshotOf(
            Message.User(listOf(ContentBlock.Text("first"))),
            Message.Assistant(
                AssistantMessage(
                    listOf(
                        ContentBlock.Thinking("想一"),
                        ContentBlock.Text("答一"),
                        ContentBlock.Thinking("想二"),
                        ContentBlock.Text("答二"),
                        ContentBlock.ToolCall("c1", "search", "{}"),
                    ),
                ),
            ),
        )

        val blocks = ConversationFormatter.toConversation(snapshot).turns.single().blocks

        assertEquals(
            listOf(
                "t0:0",
                "t0:1",
                "t0:2",
                "t0:3",
                "t0:4",
            ),
            blocks.map { it.id },
        )
        assertEquals(
            listOf("想一", "答一", "想二", "答二", null),
            blocks.map { (it as? TurnBlock.Text)?.text ?: (it as? TurnBlock.Thinking)?.text },
        )
    }

    /** 相邻文本段原位合并（与归约器 `appendText` 同规则），隔了块的段另起一块。 */
    @Test
    fun toConversation_mergesAdjacentTextBlocks() {
        val snapshot = snapshotOf(
            Message.User(listOf(ContentBlock.Text("first"))),
            Message.Assistant(
                AssistantMessage(
                    listOf(
                        ContentBlock.Text("上半"),
                        ContentBlock.Text("下半"),
                        ContentBlock.ToolCall("c1", "search", "{}"),
                        ContentBlock.Text("工具后"),
                    ),
                ),
            ),
        )

        val blocks = ConversationFormatter.toConversation(snapshot).turns.single().blocks

        assertEquals(listOf("上半下半", "search", "工具后"), blocks.map {
            when (it) {
                is TurnBlock.Text -> it.text
                is TurnBlock.Tool -> it.invocation.name
                else -> "?"
            }
        })
    }

    /** 没有配对 ToolResult 的调用：旧装配记为失败且无原因，恢复后不再当作未结算。 */
    @Test
    fun toConversation_unpairedToolCallIsFailedWithoutReason() {
        val snapshot = snapshotOf(
            Message.User(listOf(ContentBlock.Text("first"))),
            Message.Assistant(
                AssistantMessage(listOf(ContentBlock.ToolCall("c1", "search", "{}"))),
            ),
        )

        val tool = ConversationFormatter.toConversation(snapshot).turns.single().blocks
            .single() as TurnBlock.Tool

        assertEquals(ToolOutcome.Failed(message = ""), tool.outcome)
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
