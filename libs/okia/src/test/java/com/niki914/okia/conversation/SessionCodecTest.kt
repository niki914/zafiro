package com.niki914.okia.conversation

import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.message.ToolCallOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SessionCodecTest {

    private val codec = JsonSessionCodec()

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun user(text: String) = Message.User(listOf(ContentBlock.Text(text)))

    private fun assistant(text: String) =
        Message.Assistant(
            AssistantMessage(
                listOf(ContentBlock.Text(text)),
                stopReason = StopReason.Stop
            )
        )

    private fun toolResult(callId: String) =
        Message.ToolResult(callId, "calculator", ToolCallOutcome.Success("42"))

    private fun fullEntries(): List<ConversationEntry> = listOf(
        ConversationEntry("e1", null, 100L, user("q1")),
        ConversationEntry(
            "e2",
            "e1",
            200L,
            Message.Assistant(
                AssistantMessage(
                    listOf(
                        ContentBlock.Thinking("think"),
                        ContentBlock.Text("answer"),
                        ContentBlock.ToolCall("c1", "calculator", """{"expr":"1+2"}""")
                    ),
                    stopReason = StopReason.ToolUse,
                    usage = null
                )
            )
        ),
        ConversationEntry("e3", "e2", 300L, toolResult("c1"))
    )

    // ── 往返 ───────────────────────────────────────────────────────────────

    @Test
    fun emptySessionRoundTrip() {
        val snapshot = SessionSnapshot("s1", null, version = 1, entries = emptyList())

        val decoded = codec.decode(codec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun fullSessionRoundTrip() {
        val snapshot = SessionSnapshot("s1", "e3", version = 1, entries = fullEntries())

        val decoded = codec.decode(codec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun rewindPositionSurvivesRoundTrip() {
        // leafId 指向中间条目（rewind 后的位置），重载后必须保持
        val snapshot = SessionSnapshot("s1", "e1", version = 2, entries = fullEntries())

        val decoded = codec.decode(codec.encode(snapshot))

        assertEquals("e1", decoded.leafId)
        assertEquals(2, decoded.version)
        assertEquals(fullEntries(), decoded.entries)
    }

    @Test
    fun nullLeafIdSurvivesRoundTrip() {
        val snapshot = SessionSnapshot("s1", null, version = 1, entries = fullEntries())

        val decoded = codec.decode(codec.encode(snapshot))

        assertNull(decoded.leafId)
    }

    // ── 失败路径 ───────────────────────────────────────────────────────────

    @Test
    fun malformedJsonThrows() {
        val exception = try {
            codec.decode("not json at all")
            null
        } catch (t: IllegalArgumentException) {
            t
        }
        assertNotNull(exception)
    }

    @Test
    fun missingFieldThrows() {
        val exception = try {
            codec.decode("""{"id":"s1"}""")
            null
        } catch (t: IllegalArgumentException) {
            t
        }
        assertNotNull(exception)
    }

    // ── 恢复重建（codec + 树集成）───────────────────────────────────────────

    @Test
    fun restoreRebuildsEquivalentTree() = runBlocking {
        val original = RealConversation("s1", emptyList(), null)
        val first = original.append(user("q1"))
        original.append(assistant("a1"))
        original.append(user("q2"))
        original.rewind(first.id) // 停在中间

        val snapshot =
            SessionSnapshot(original.id, original.leafId, version = 1, entries = original.entries)
        val decoded = codec.decode(codec.encode(snapshot))
        val restored = RealConversation(decoded.id, decoded.entries, decoded.leafId)

        // 投影与 leaf 位置一致
        assertEquals(original.history, restored.history)
        assertEquals(original.leafId, restored.leafId)
        assertEquals(original.entries, restored.entries)

        // 恢复后继续 append 形成新分支，历史 id 不冲突
        val branched = restored.append(user("q1b"))
        assertEquals(listOf<Message>(user("q1"), user("q1b")), restored.history)
        assertEquals(first.id, branched.parentId)
    }
}
