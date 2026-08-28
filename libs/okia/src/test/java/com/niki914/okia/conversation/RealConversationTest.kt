package com.niki914.okia.conversation

import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.message.ToolCallOutcome
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RealConversationTest {

    // ── fixtures ───────────────────────────────────────────────────────────

    private fun user(text: String) = Message.User(listOf(ContentBlock.Text(text)))

    private fun assistant(text: String) =
        Message.Assistant(AssistantMessage(listOf(ContentBlock.Text(text)), stopReason = StopReason.Stop))

    private fun toolResult(callId: String) =
        Message.ToolResult(callId, "calculator", ToolCallOutcome.Success("42"))

    private fun newTree(id: String = "s1") = RealConversation(id, emptyList(), null)

    // ── 初始状态 ───────────────────────────────────────────────────────────

    @Test
    fun emptyTreeInitialState() {
        val tree = newTree()
        assertNull(tree.leafId)
        assertTrue(tree.entries.isEmpty())
        assertTrue(tree.history.isEmpty())
        val snapshot = tree.toSnapshot()
        assertEquals("s1", snapshot.id)
        assertNull(snapshot.leafId)
        assertTrue(snapshot.history.isEmpty())
        assertNull(snapshot.live)
    }

    // leafId null = 恢复为最后一条（issue #126 对齐 docs/okia.md §5.3）
    @Test
    fun nullLeafProjectsToLastEntry() {
        val entries = listOf(
            ConversationEntry("e1", null, 1L, user("hi")),
            ConversationEntry("e2", "e1", 2L, assistant("hello")),
        )
        val tree = RealConversation("s1", entries, null)
        assertEquals(listOf(user("hi"), assistant("hello")), tree.history)
        assertEquals("e2", tree.toSnapshot().history.lastOrNull()?.id)
    }

    @Test
    fun initialStateRejectsDanglingLeafId() {
        val entry = ConversationEntry("e1", null, 1L, user("hi"))
        val exception = try {
            RealConversation("s1", listOf(entry), "missing")
            null
        } catch (t: IllegalArgumentException) {
            t
        }
        assertNotNull(exception)
    }

    @Test
    fun initialStateRejectsDuplicateIds() {
        val entry = ConversationEntry("e1", null, 1L, user("hi"))
        val duplicate = ConversationEntry("e1", null, 2L, user("bye"))
        val exception = try {
            RealConversation("s1", listOf(entry, duplicate), null)
            null
        } catch (t: IllegalArgumentException) {
            t
        }
        assertNotNull(exception)
    }

    // ── append ─────────────────────────────────────────────────────────────

    @Test
    fun appendBuildsLinearHistory() = runBlocking {
        val tree = newTree()
        tree.append(user("q1"))
        tree.append(assistant("a1"))
        tree.append(user("q2"))

        assertEquals(
            listOf<Message>(user("q1"), assistant("a1"), user("q2")),
            tree.history
        )
        assertEquals(3, tree.entries.size)
        assertEquals(tree.entries.last().id, tree.leafId)
    }

    @Test
    fun appendReturnsEntryWithParentChain() = runBlocking {
        val tree = newTree()
        val first = tree.append(user("q1"))
        val second = tree.append(assistant("a1"))

        // leafId 指向最后追加的条目
        assertEquals(second.id, tree.leafId)
        assertEquals(second.parentId, first.id)
        assertNull(first.parentId)
        assertTrue(second.id.isNotEmpty())
        assertTrue(second.timestamp > 0)
        assertEquals(user("q1"), first.message)
    }

    @Test
    fun appendAllIsAtomicBatch() = runBlocking {
        val tree = newTree()
        val messages = listOf<Message>(user("q1"), assistant("a1"), user("q2"))
        val entries = tree.appendAll(messages)

        assertEquals(messages, tree.history)
        assertEquals(3, entries.size)
        // parentId 链连续：batch 内后一条指向 batch 内前一条
        assertEquals(entries[0].id, entries[1].parentId)
        assertEquals(entries[1].id, entries[2].parentId)
        assertEquals(entries.last().id, tree.leafId)
    }

    @Test
    fun appendAllEmptyIsNoOp() = runBlocking {
        val tree = newTree()
        tree.append(user("q1"))
        val entries = tree.appendAll(emptyList())

        assertTrue(entries.isEmpty())
        assertEquals(1, tree.entries.size)
        assertEquals(tree.entries.first().id, tree.leafId)
    }

    // ── rewind ─────────────────────────────────────────────────────────────

    @Test
    fun rewindToMiddleMovesLeafAndKeepsTail() = runBlocking {
        val tree = newTree()
        val first = tree.append(user("q1"))
        tree.append(assistant("a1"))
        tree.append(user("q2"))

        tree.rewind(first.id)

        assertEquals(first.id, tree.leafId)
        assertEquals(listOf<Message>(user("q1")), tree.history)
        // 被跳过的尾部保留在树中
        assertEquals(3, tree.entries.size)
    }

    @Test
    fun rewindToOnlyEntryIsNoOp() = runBlocking {
        val tree = newTree()
        val entry = tree.append(user("hi"))

        tree.rewind(entry.id)

        assertEquals(entry.id, tree.leafId)
        assertEquals(listOf<Message>(user("hi")), tree.history)
        assertEquals(1, tree.entries.size)
    }

    @Test
    fun rewindToCurrentLeafIsIdempotent() = runBlocking {
        val tree = newTree()
        tree.append(user("q1"))
        val second = tree.append(assistant("a1"))

        tree.rewind(second.id)

        assertEquals(second.id, tree.leafId)
        assertEquals(2, tree.entries.size)
    }

    @Test
    fun rewindMissingEntryThrows() = runBlocking {
        val tree = newTree()
        tree.append(user("q1"))

        val exception = try {
            tree.rewind("missing")
            null
        } catch (t: IllegalArgumentException) {
            t
        }
        assertNotNull(exception)
    }

    @Test
    fun rewindOnEmptyTreeThrows() = runBlocking {
        val tree = newTree()
        val exception = try {
            tree.rewind("any")
            null
        } catch (t: IllegalArgumentException) {
            t
        }
        assertNotNull(exception)
    }

    @Test
    fun rewindThenAppendCreatesBranch() = runBlocking {
        val tree = newTree()
        val first = tree.append(user("q1"))
        tree.append(assistant("a1"))
        tree.append(user("q2"))

        tree.rewind(first.id)
        val branched = tree.append(user("q1b"))

        // 投影 = leaf 到 root：原分支尾部（a1/q2）被跳过
        assertEquals(listOf<Message>(user("q1"), user("q1b")), tree.history)
        assertEquals(first.id, branched.parentId)
        // 尾部保留 + 新分支条目
        assertEquals(4, tree.entries.size)
    }

    // ── 快照 ───────────────────────────────────────────────────────────────

    @Test
    fun snapshotProjectsLeafHistory() = runBlocking {
        val tree = newTree()
        val first = tree.append(user("q1"))
        tree.append(assistant("a1"))
        tree.append(user("q2"))

        tree.rewind(first.id)
        val snapshot = tree.toSnapshot()

        assertEquals(first.id, snapshot.leafId)
        assertEquals(1, snapshot.history.size)
        assertEquals(first.id, snapshot.history[0].id)
        assertEquals(user("q1"), snapshot.history[0].message)
        assertTrue(snapshot.history[0].timestamp > 0)
    }

    @Test
    fun snapshotCarriesLive() = runBlocking {
        val tree = newTree()
        tree.append(user("q1"))
        val live = AssistantMessage(listOf(ContentBlock.Text("partial")), stopReason = StopReason.Pending)

        val snapshot = tree.toSnapshot(live)

        assertEquals(live, snapshot.live)
    }

    @Test
    fun oldSnapshotUnaffectedByLaterAppends() = runBlocking {
        val tree = newTree()
        tree.append(user("q1"))
        val snapshotBefore = tree.toSnapshot()
        val entriesBefore = tree.entries

        tree.append(user("q2"))

        // 旧引用仍是旧内容（构建新列表，不原地修改）
        assertEquals(1, snapshotBefore.history.size)
        assertEquals(1, entriesBefore.size)
        assertEquals(2, tree.toSnapshot().history.size)
    }

    // ── 并发 ───────────────────────────────────────────────────────────────

    @Test
    fun concurrentAppendAllLosesNothing() = runBlocking {
        val tree = newTree()
        val batches = 10
        val perBatch = 5

        coroutineScope {
            (0 until batches).map { b ->
                async { tree.appendAll(List(perBatch) { user("u$b-$it") }) }
            }.awaitAll()
        }

        assertEquals(batches * perBatch, tree.entries.size)
        assertEquals(batches * perBatch, tree.history.size)
        // id 全唯一：Mutex 串行化下无重复生成
        val ids = tree.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // leaf 指向最后追加的条目
        assertEquals(tree.entries.last().id, tree.leafId)
    }

    @Test
    fun concurrentRewindAndAppendStayConsistent() = runBlocking {
        val tree = newTree()
        val first = tree.append(user("q1"))
        tree.append(user("q2"))

        coroutineScope {
            val rewindJob = async { tree.rewind(first.id) }
            val appendJob = async { tree.append(user("q3")) }
            rewindJob.await()
            appendJob.await()
        }

        // 两个操作都完整执行：无丢失、状态一致。
        // 终态由调度顺序决定（两种都合法）：
        // append 先执行 → q3 接在 q2 后；rewind 先执行 → q3 接在 q1 后。
        assertEquals(3, tree.entries.size)
        val possible = setOf(
            listOf<Message>(user("q1"), user("q2"), user("q3")),
            listOf<Message>(user("q1"), user("q3"))
        )
        assertTrue("unexpected history: ${tree.history}", possible.contains(tree.history))
        // leaf 指向真实条目，投影完整
        val leaf = tree.leafId
        assertNotNull(leaf)
        assertTrue(tree.entries.any { it.id == leaf })
    }

    // ── 不变量 + 对照模型（随机操作序列）───────────────────────────────────

    @Test
    fun randomOperationsMatchModel() = runBlocking {
        val seed = 20260816L
        val random = Random(seed)
        val tree = newTree()
        val model = Model()

        repeat(300) {
            when (random.nextInt(10)) {
                in 0..4 -> {
                    val message = randomMessage(random)
                    val entry = tree.append(message)
                    model.append(entry) // 共享 tree 的真实 id
                }
                in 5..7 -> {
                    val messages = List(random.nextInt(1, 4)) { randomMessage(random) }
                    val entries = tree.appendAll(messages)
                    entries.forEach { model.append(it) }
                }
                else -> {
                    if (model.entries.isNotEmpty()) {
                        val target = model.entries[random.nextInt(model.entries.size)].id
                        tree.rewind(target)
                        model.rewind(target)
                    }
                }
            }

            // 每步断言不变量：投影一致 + leaf 位置一致（按 message 对比，
            // id 由实现生成，模型只镜像不预测）
            assertEquals(model.history(), tree.history)
            assertEquals(model.leafMessage(), tree.leafMessage())
        }

        // 收尾不变量：leafId 非空时指向真实条目；投影与 entries 一致
        assertEquals(tree.entries.size, model.entries.size)
        if (tree.leafId != null) {
            assertTrue(tree.entries.any { it.id == tree.leafId })
        }
    }

    // 朴素对照模型：测试内的独立实现（普通可变列表 + leaf 下标）。
    // 镜像 tree 产出的真实条目（共享 id 空间），投影与 leaf 位置独立重算。
    private class Model {
        val entries = mutableListOf<ConversationEntry>()
        var leafIndex: Int? = null

        fun append(entry: ConversationEntry) {
            entries += entry
            leafIndex = entries.lastIndex
        }

        fun rewind(entryId: String) {
            val index = entries.indexOfFirst { it.id == entryId }
            require(index >= 0) { "entry not found: $entryId" }
            leafIndex = index
        }

        fun leafMessage(): Message? = leafIndex?.let { entries[it].message }

        fun history(): List<Message> {
            val index = leafIndex ?: return emptyList()
            val reversed = mutableListOf<Message>()
            var cursor = index
            while (true) {
                reversed += entries[cursor].message
                val parentId = entries[cursor].parentId
                if (parentId == null) break
                cursor = entries.indexOfFirst { it.id == parentId }
            }
            reversed.reverse()
            return reversed
        }
    }

    private fun randomMessage(random: Random): Message = when (random.nextInt(3)) {
        0 -> user("q${random.nextInt(100)}")
        1 -> assistant("a${random.nextInt(100)}")
        else -> toolResult("c${random.nextInt(100)}")
    }

    private fun RealConversation.leafMessage(): Message? =
        leafId?.let { id -> entries.first { it.id == id }.message }
}
