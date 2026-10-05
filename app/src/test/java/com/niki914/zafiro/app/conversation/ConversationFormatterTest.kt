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
import com.niki914.zafiro.app.util.SilentLoggerRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class ConversationFormatterTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    @Test
    fun sanitizeDisplayTitle_stripsSingleAndRepeatedRegeneratePrefixes() {
        assertEquals("原标题", ConversationFormatter.sanitizeDisplayTitle("Regenerate · 原标题"))
        assertEquals("原标题", ConversationFormatter.sanitizeDisplayTitle("Regenerate · Regenerate · 原标题"))
        assertEquals("原标题", ConversationFormatter.sanitizeDisplayTitle("Regenerate · Regenerate · Regenerate · 原标题"))
        assertEquals("原标题", ConversationFormatter.sanitizeDisplayTitle("  Regenerate ·   Regenerate · 原标题  "))
        assertEquals("", ConversationFormatter.sanitizeDisplayTitle("Regenerate · "))
        assertEquals("", ConversationFormatter.sanitizeDisplayTitle("Regenerate ·"))
        assertEquals("普通标题", ConversationFormatter.sanitizeDisplayTitle("普通标题"))
    }

    @Test
    fun parseDisplayTitle_detectsKindAndStripsNestedMultiLocalePrefixes() {
        // 单层前缀
        val regen = ConversationFormatter.parseDisplayTitle("Regenerate · 测试会话")
        assertEquals("测试会话", regen.cleanTitle)
        assertEquals(ConversationOriginKind.Regenerate, regen.originKind)

        // 嵌套多层相同前缀
        val nestedRegen = ConversationFormatter.parseDisplayTitle("Regenerate · Regenerate · 深度测试")
        assertEquals("深度测试", nestedRegen.cleanTitle)
        assertEquals(ConversationOriginKind.Regenerate, nestedRegen.originKind)

        // 嵌套不同前缀：以最外层（第一个）为准
        val forkThenRegen = ConversationFormatter.parseDisplayTitle("Fork · Regenerate · 派生后重新生成")
        assertEquals("派生后重新生成", forkThenRegen.cleanTitle)
        assertEquals(ConversationOriginKind.Fork, forkThenRegen.originKind)

        val rewindThenFork = ConversationFormatter.parseDisplayTitle("Rewind · Fork · 回退后分支")
        assertEquals("回退后分支", rewindThenFork.cleanTitle)
        assertEquals(ConversationOriginKind.Rewind, rewindThenFork.originKind)

        // 多语言前缀：西班牙语、中文、日文
        val spanish = ConversationFormatter.parseDisplayTitle("Regenerar · Mi charla")
        assertEquals("Mi charla", spanish.cleanTitle)
        assertEquals(ConversationOriginKind.Regenerate, spanish.originKind)

        val chinese = ConversationFormatter.parseDisplayTitle("重新生成 · 中文会话")
        assertEquals("中文会话", chinese.cleanTitle)
        assertEquals(ConversationOriginKind.Regenerate, chinese.originKind)

        val japanese = ConversationFormatter.parseDisplayTitle("再生成 · 日本語の会話")
        assertEquals("日本語の会話", japanese.cleanTitle)
        assertEquals(ConversationOriginKind.Regenerate, japanese.originKind)

        // 不同分隔符 (·, •, :, ：)
        val bullet = ConversationFormatter.parseDisplayTitle("Regenerate • 圆点分隔")
        assertEquals("圆点分隔", bullet.cleanTitle)
        assertEquals(ConversationOriginKind.Regenerate, bullet.originKind)

        val colon = ConversationFormatter.parseDisplayTitle("Fork: 英文冒号")
        assertEquals("英文冒号", colon.cleanTitle)
        assertEquals(ConversationOriginKind.Fork, colon.originKind)

        val fullWidthColon = ConversationFormatter.parseDisplayTitle("Rewind：全角冒号")
        assertEquals("全角冒号", fullWidthColon.cleanTitle)
        assertEquals(ConversationOriginKind.Rewind, fullWidthColon.originKind)

        // 包含前缀关键词但不是前缀（如文件名 Fork.kt）
        val filename = ConversationFormatter.parseDisplayTitle("Fork.kt is a kotlin file")
        assertEquals("Fork.kt is a kotlin file", filename.cleanTitle)
        assertNull(filename.originKind)

        // 无前缀普通标题
        val normal = ConversationFormatter.parseDisplayTitle("今日工作小结")
        assertEquals("今日工作小结", normal.cleanTitle)
        assertNull(normal.originKind)

        // 空标题与仅有前缀
        val onlyPrefix = ConversationFormatter.parseDisplayTitle("Regenerate · ")
        assertEquals("", onlyPrefix.cleanTitle)
        assertEquals(ConversationOriginKind.Regenerate, onlyPrefix.originKind)
    }

    @Test
    fun previewFromText_flattensWhitespaceAndAllowsLongerText() {
        val multiline = "第一行内容\n\n第二行内容\t第三行"
        assertEquals("第一行内容 第二行内容 第三行", ConversationFormatter.previewFromText(multiline))

        val longText = "a".repeat(150)
        val preview = ConversationFormatter.previewFromText(longText)
        assertEquals(120 + 3, preview.length) // 120 + "..."
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

        assertEquals(listOf(Attachment("/files/a.png", "image/png")), turn.images)
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
