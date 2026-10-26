// 保护：会话仓储的持久化读写契约——建档/读回、leaf 与进度落盘、fork 派生、
// 重新生成分支分组与列表收敛、删除级联、configId 往返及 Flow 发射。
package com.niki914.zafiro.app.conversation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val DB_NAME = "test-conversation.db"

@RunWith(RobolectricTestRunner::class)
class ConversationRepoTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        ConversationRepo.init(context)
    }

    @After
    fun tearDown() = runTest {
        ConversationRepo.closeForTest()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun exists_isFalseBeforeCreateAndTrueAfter() = runTest {
        assertFalse(ConversationRepo.exists("session-missing"))

        ConversationRepo.createConversation("session-exists", "hello")

        assertTrue(ConversationRepo.exists("session-exists"))
    }

    @Test
    fun createAndGet_persistsConversationMetadata() = runTest {
        val id = ConversationRepo.createConversation("session-1", "hello world")

        val record = ConversationRepo.getConversation(id)!!
        assertEquals("session-1", record.summary.id)
        assertEquals("hello world", record.summary.title)
        assertFalse(record.summary.titleEdited)
        assertEquals(0, record.summary.turnCount)
        assertEquals("hello world", record.summary.lastMessagePreview)
        assertTrue(record.snapshot.entries.isEmpty())
        assertNull(record.snapshot.leafId)
        assertEquals("", record.configId)
    }

    @Test
    fun insertEntries_roundTripsMessageTreeExactly() = runTest {
        val id = ConversationRepo.createConversation("session-1", "hi")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("q1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
            Message.ToolResult("c1", "search", ToolCallOutcome.Success("result")),
        )
        ConversationRepo.insertEntries(id, entries)
        ConversationRepo.updateLeafId(id, entries.last().id)

        val snapshot = ConversationRepo.getConversation(id)!!.snapshot
        assertEquals(3, snapshot.entries.size)
        snapshot.entries.zip(entries).forEach { (actual, expected) ->
            assertEquals(expected.id, actual.id)
            assertEquals(expected.parentId, actual.parentId)
            assertEquals(expected.timestamp, actual.timestamp)
            assertEquals(expected.message, actual.message)
        }
        assertEquals(entries.last().id, snapshot.leafId)
    }

    @Test
    fun insertEntries_isIdempotentByEntryId() = runTest {
        val id = ConversationRepo.createConversation("session-1", "hi")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("q1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
        )
        ConversationRepo.insertEntries(id, entries)
        ConversationRepo.insertEntries(id, entries)

        assertEquals(2, ConversationRepo.countEntries(id))
        assertEquals(2, ConversationRepo.getConversation(id)!!.snapshot.entries.size)
    }

    @Test
    fun getConversation_passesNullLeafIdThrough() = runTest {
        val id = ConversationRepo.createConversation("session-1", "hi")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("q1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
        )
        ConversationRepo.insertEntries(id, entries)
        // 不调用 updateLeafId：leaf_id 保持 null，repo 透传；由 OKIA 恢复为最后一条（§5.3）

        val snapshot = ConversationRepo.getConversation(id)!!.snapshot
        assertNull(snapshot.leafId)
        assertEquals(entries.size, snapshot.entries.size)
    }

    @Test
    fun forkAtTurn_forkKeepsWholeTurnWithForkTitle() = runTest {
        val sourceId = ConversationRepo.createConversation("session-src", "original")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("u1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
            Message.User(listOf(ContentBlock.Text("u2"))),
        )
        ConversationRepo.insertEntries(sourceId, entries)
        ConversationRepo.updateLeafId(sourceId, entries.last().id)

        val result = ConversationRepo.forkAtTurn(sourceId, turnIndex = 0, kind = ForkKind.Fork)!!

        val newRecord = ConversationRepo.getConversation(result.newConversationId)!!
        assertTrue(newRecord.summary.title.startsWith("Fork ·"))
        assertTrue(newRecord.summary.titleEdited)
        // 整回合保留：截在下一个 User 条目之前
        assertEquals(2, newRecord.snapshot.entries.size)
        assertEquals(entries[0].id, newRecord.snapshot.entries[0].id)
        assertEquals(entries[1].id, newRecord.snapshot.entries[1].id)
        assertEquals(entries[1].id, newRecord.snapshot.leafId)
        // Fork 不回填草稿
        assertEquals("", result.promptText)
        assertTrue(result.images.isEmpty())
        // 源会话不受影响
        assertEquals(3, ConversationRepo.countEntries(sourceId))
    }

    @Test
    fun forkAtTurn_forkOnLastTurnKeepsToTail() = runTest {
        val sourceId = ConversationRepo.createConversation("session-src", "original")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("u1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
        )
        ConversationRepo.insertEntries(sourceId, entries)
        ConversationRepo.updateLeafId(sourceId, entries.last().id)

        val result = ConversationRepo.forkAtTurn(sourceId, turnIndex = 0, kind = ForkKind.Fork)!!

        assertEquals(2, ConversationRepo.countEntries(result.newConversationId))
    }

    @Test
    fun forkAtTurn_regenTruncatesBeforeUserAndExtractsInput() = runTest {
        val sourceId = ConversationRepo.createConversation("session-src", "original")
        val entries = linearEntries(
            Message.User(
                listOf(
                    ContentBlock.Text("u1"),
                    ContentBlock.Image("/image.jpg", "image/jpeg"),
                ),
            ),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
            Message.User(listOf(ContentBlock.Text("u2"))),
        )
        ConversationRepo.insertEntries(sourceId, entries)
        ConversationRepo.updateLeafId(sourceId, entries.last().id)

        val result =
            ConversationRepo.forkAtTurn(sourceId, turnIndex = 0, kind = ForkKind.Regenerate)!!

        val newRecord = ConversationRepo.getConversation(result.newConversationId)!!
        // 分支留在同一组：共享原始标题
        assertEquals("original", newRecord.summary.title)
        assertEquals(sourceId, newRecord.summary.rootId)
        assertEquals(sourceId, newRecord.summary.parentId)
        assertEquals(0, newRecord.summary.forkTurn)
        // 截在该回合用户条目之前（不含）：turn 0 → 0 条
        assertTrue(newRecord.snapshot.entries.isEmpty())
        assertEquals("u1", result.promptText)
        assertEquals(listOf("/image.jpg"), result.images.map { it.path })
    }

    @Test
    fun branchGroup_listsAllRegenerationsAndCollapsesListToLatest() = runTest {
        val sourceId = ConversationRepo.createConversation("session-src", "original")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("u1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
        )
        ConversationRepo.insertEntries(sourceId, entries)
        ConversationRepo.updateLeafId(sourceId, entries.last().id)

        val first = ConversationRepo.forkAtTurn(sourceId, turnIndex = 0, ForkKind.Regenerate)!!
        val second = ConversationRepo.forkAtTurn(sourceId, turnIndex = 0, ForkKind.Regenerate)!!

        // 同一组：原始 1 + 两次重新生成
        val group = ConversationRepo.branchGroup(second.newConversationId)
        assertEquals(3, group.size)
        assertEquals(
            listOf(sourceId, first.newConversationId, second.newConversationId),
            group.map { it.id },
        )
        assertEquals(listOf(null, sourceId, sourceId), group.map { it.parentId })
        assertEquals(listOf(null, 0, 0), group.map { it.forkTurn })
        // 组内最新分支
        assertEquals(second.newConversationId, ConversationRepo.latestBranchId(sourceId))
    }

    // 保护：单版本会话的分组必须为空，这是「首次不出 <1/1>」的边界。
    @Test
    fun branchGroup_singleVersionConversationReturnsEmpty() = runTest {
        val sourceId = ConversationRepo.createConversation("session-solo", "original")
        assertTrue(ConversationRepo.branchGroup(sourceId).isEmpty())
    }

    // 保护：删除一个会话要连它整个分支组一起删——
    // 只删当前版本会留下隐藏兄弟，列表又把它顶上来。
    @Test
    fun deleteConversation_removesWholeBranchGroup() = runTest {
        val sourceId = ConversationRepo.createConversation("session-src", "original")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("u1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
        )
        ConversationRepo.insertEntries(sourceId, entries)
        ConversationRepo.updateLeafId(sourceId, entries.last().id)
        val regen = ConversationRepo.forkAtTurn(sourceId, turnIndex = 0, ForkKind.Regenerate)!!

        ConversationRepo.deleteConversation(regen.newConversationId)

        assertFalse(ConversationRepo.exists(sourceId))
        assertFalse(ConversationRepo.exists(regen.newConversationId))
    }

    // 保护：fork 会脱钩。删除 B 所在的重新生成组不能伤到 A（B 的 fork 源）
    // 与 E（从 C fork 出去的独立组）。
    @Test
    fun deleteConversation_forkedAncestorAndChildSurvive() = runTest {
        // A -fork-> B, B -regen-> C、D, C -fork-> E
        val a = ConversationRepo.createConversation("a", "A")
        ConversationRepo.insertEntries(a, linearEntries(Message.User(listOf(ContentBlock.Text("u1")))))
        val b = ConversationRepo.forkAtTurn(a, turnIndex = 0, kind = ForkKind.Fork)!!.newConversationId
        ConversationRepo.insertEntries(b, linearEntries(Message.User(listOf(ContentBlock.Text("u1")))))

        val c = ConversationRepo.forkAtTurn(b, turnIndex = 0, kind = ForkKind.Regenerate)!!.newConversationId
        val d = ConversationRepo.forkAtTurn(b, turnIndex = 0, kind = ForkKind.Regenerate)!!.newConversationId
        ConversationRepo.insertEntries(c, linearEntries(Message.User(listOf(ContentBlock.Text("u1")))))
        val e = ConversationRepo.forkAtTurn(c, turnIndex = 0, kind = ForkKind.Fork)!!.newConversationId

        ConversationRepo.deleteConversation(d)

        // B、C、D 整组消失
        assertFalse(ConversationRepo.exists(b))
        assertFalse(ConversationRepo.exists(c))
        assertFalse(ConversationRepo.exists(d))
        // A、E 已脱钩，不受影响
        assertTrue(ConversationRepo.exists(a))
        assertTrue(ConversationRepo.exists(e))
    }

    // 保护：列表收敛取「组内 updated_at 最新」而非「版本序号最大」——
    // 在老版本里继续对话，该组要浮顶且列表落到那一行。
    @Test
    fun observeConversations_collapsesToMostRecentlyUpdatedBranch() = runTest {
        val sourceId = ConversationRepo.createConversation("session-src", "original")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("u1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
        )
        ConversationRepo.insertEntries(sourceId, entries)
        ConversationRepo.updateLeafId(sourceId, entries.last().id)
        val regen = ConversationRepo.forkAtTurn(sourceId, turnIndex = 0, ForkKind.Regenerate)!!

        // 重新生成后 V2 更新，列表应当收敛到 V2
        assertEquals(regen.newConversationId, ConversationRepo.latestBranchId(sourceId))
        assertEquals(regen.newConversationId, ConversationRepo.observeConversations().first()[0].id)

        // 回到 V1 继续聊：V1 成为最近更新，列表与落点都改回 V1
        ConversationRepo.touchConversation(sourceId, now = System.currentTimeMillis() + 10_000L)
        assertEquals(sourceId, ConversationRepo.latestBranchId(sourceId))
        assertEquals(sourceId, ConversationRepo.observeConversations().first()[0].id)
    }

    @Test
    fun forkAtTurn_rewindOnLaterTurnExtractsThatTurnInput() = runTest {
        val sourceId = ConversationRepo.createConversation("session-src", "original")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("u1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
            Message.User(listOf(ContentBlock.Text("u2"))),
        )
        ConversationRepo.insertEntries(sourceId, entries)
        ConversationRepo.updateLeafId(sourceId, entries.last().id)

        val result = ConversationRepo.forkAtTurn(sourceId, turnIndex = 1, kind = ForkKind.Rewind)!!

        val newRecord = ConversationRepo.getConversation(result.newConversationId)!!
        assertTrue(newRecord.summary.title.startsWith("Rewind ·"))
        // turn 1 的用户条目在投影下标 2：保留前两条（turn 0 完整）
        assertEquals(2, newRecord.snapshot.entries.size)
        assertEquals("u2", result.promptText)
        assertTrue(result.images.isEmpty())
    }

    @Test
    fun forkAtTurn_missingSourceOrTurnReturnsNull() = runTest {
        val sourceId = ConversationRepo.createConversation("session-src", "original")
        val entries = linearEntries(Message.User(listOf(ContentBlock.Text("u1"))))
        ConversationRepo.insertEntries(sourceId, entries)
        ConversationRepo.updateLeafId(sourceId, entries.last().id)

        assertNull(ConversationRepo.forkAtTurn("session-nope", turnIndex = 0, kind = ForkKind.Fork))
        assertNull(ConversationRepo.forkAtTurn(sourceId, turnIndex = 1, kind = ForkKind.Fork))
    }

    @Test
    fun forkConversation_createsCompleteCopy() = runTest {
        val sourceId = ConversationRepo.createConversation("session-src", "original")
        val entries = linearEntries(
            Message.User(listOf(ContentBlock.Text("u1"))),
            Message.Assistant(AssistantMessage(listOf(ContentBlock.Text("a1")))),
            Message.User(listOf(ContentBlock.Text("u2"))),
        )
        ConversationRepo.insertEntries(sourceId, entries)
        ConversationRepo.updateLeafId(sourceId, entries.last().id)

        val newId = ConversationRepo.forkConversation(sourceId)!!

        val newRecord = ConversationRepo.getConversation(newId)!!
        assertTrue(newRecord.summary.title.startsWith("Fork ·"))
        assertTrue(newRecord.summary.titleEdited)
        assertEquals(3, newRecord.snapshot.entries.size)
        assertEquals(entries.last().id, newRecord.snapshot.leafId)
        assertEquals(3, ConversationRepo.countEntries(sourceId))
    }

    @Test
    fun rename_mutatesConversationTitle() = runTest {
        val rootId = ConversationRepo.createConversation("session-1", "original")
        val entries = linearEntries(Message.User(listOf(ContentBlock.Text("u1"))))
        ConversationRepo.insertEntries(rootId, entries)
        ConversationRepo.updateLeafId(rootId, entries.last().id)
        val regen = ConversationRepo.forkAtTurn(rootId, turnIndex = 0, kind = ForkKind.Regenerate)!!
        val branchId = regen.newConversationId

        ConversationRepo.renameConversation(branchId, "new title")

        val rootRecord = ConversationRepo.getConversation(rootId)!!
        val branchRecord = ConversationRepo.getConversation(branchId)!!
        assertEquals("new title", rootRecord.summary.title)
        assertEquals("new title", branchRecord.summary.title)
        assertTrue(rootRecord.summary.titleEdited)
        assertTrue(branchRecord.summary.titleEdited)
    }

    @Test
    fun deleteConversation_hardDeletesRecord() = runTest {
        val id = ConversationRepo.createConversation("session-1", "hi")
        val entries = linearEntries(Message.User(listOf(ContentBlock.Text("q1"))))
        ConversationRepo.insertEntries(id, entries)

        ConversationRepo.deleteConversation(id)

        assertNull(ConversationRepo.getConversation(id))
    }

    @Test
    fun updateConfigId_persistsAndRoundTripsConfigId() = runTest {
        val id = ConversationRepo.createConversation("session-1", "hi")
        assertEquals("", ConversationRepo.getConversation(id)!!.configId)

        ConversationRepo.updateConfigId(id, "cfg-custom-1")
        val record = ConversationRepo.getConversation(id)!!
        assertEquals("cfg-custom-1", record.configId)
    }

    @Test
    fun forkConversation_copiesSourceConfigId() = runTest {
        val sourceId = ConversationRepo.createConversation("session-source", "source prompt")
        ConversationRepo.updateConfigId(sourceId, "cfg-source")

        val forkedId = ConversationRepo.forkConversation(sourceId)!!
        val forkedRecord = ConversationRepo.getConversation(forkedId)!!
        assertEquals("cfg-source", forkedRecord.configId)
    }

    @Test
    fun observeConversations_emitsWheneverConversationsAreMutated() = runTest {
        val list1 = ConversationRepo.observeConversations().first()
        assertTrue(list1.isEmpty())

        ConversationRepo.createConversation("session-1", "first")
        val list2 = ConversationRepo.observeConversations().first()
        assertEquals(1, list2.size)
        assertEquals("session-1", list2[0].id)

        ConversationRepo.deleteConversation("session-1")
        val list3 = ConversationRepo.observeConversations().first()
        assertTrue(list3.isEmpty())
    }

    private fun linearEntries(vararg messages: Message): List<ConversationEntry> {
        var parent: String? = null
        return messages.mapIndexed { index, message ->
            val entry = ConversationEntry(
                id = "entry-$index",
                parentId = parent,
                timestamp = 1000L + index,
                message = message,
            )
            parent = entry.id
            entry
        }
    }
}
