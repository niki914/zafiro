package com.niki914.okia.conversation

import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.Message
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 对话树数据结构维护者（内部实现；公开面是 Conversation 快照）。
 * 条目树 + leafId 当前位置，内部 Mutex 串行化（KMP 下唯一同步方案 =
 * kotlinx.coroutines.sync.Mutex）。
 * rewind 原地移动 leafId，被跳过的尾部保留在树中。rewind 校验 entryId 存在
 * （不存在抛 IllegalArgumentException），位置语义不校验（放开，回退粒度由下游
 * 自行约束）；历史投影 = leaf 到 root 线性投影。
 * 内部状态为不可变快照（State），写入在 mutex 内构建新快照，读取免锁。
 * Design source: pi（session-manager.ts）buildSessionPath / createBranchedSession，
 * W3 白板便签；命名参考 OkHttp Real* 惯例。
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
internal class RealConversation(
    val id: String,
    // 初始树状态与 leaf 位置（restore 恢复时传入）；公开 getter 返回防御性复制
    initialEntries: List<ConversationEntry>,
    initialLeafId: String?
) {

    // 不可变内部快照：entries 保序、byId 供校验与投影回溯、leafId 当前位置。
    // @Volatile 保证写入（mutex 内）对读取线程可见。
    private class State(
        val entries: List<ConversationEntry>,
        val byId: Map<String, ConversationEntry>,
        val leafId: String?
    )

    // 构造即校验：重复 id / 悬挂 leafId 属数据不一致，快速失败。
    // leafId 存在性校验是客观事实；位置语义（停在何处合法）不校验。
    private val initialState: State = run {
        val byId = initialEntries.associateBy { it.id }
        require(byId.size == initialEntries.size) { "duplicate entry id" }
        require(initialLeafId == null || byId.containsKey(initialLeafId)) {
            "initialLeafId does not exist: $initialLeafId"
        }
        State(initialEntries.toList(), byId, initialLeafId)
    }

    @Volatile
    private var state: State = initialState

    // 串行化所有写入操作
    private val mutex = Mutex()

    // 树形条目（append-only）。
    // 返回防御性复制：外部持有不影响内部存储。
    val entries: List<ConversationEntry>
        get() = state.entries.toList()

    // 当前 leaf 位置；append 前进，rewind 移动
    val leafId: String?
        get() = state.leafId

    // leaf 到 root 的线性历史投影，按对话顺序。
    // 返回防御性复制：外部持有不影响内部存储。
    val history: List<Message>
        get() = project(state.leafId).map { it.message }

    // 追加一条消息，返回新条目
    suspend fun append(message: Message): ConversationEntry = mutex.withLock {
        appendLocked(listOf(message)).single()
    }

    // 同一把 Mutex 下批量追加（回合产出提交入口，原子）
    suspend fun appendAll(messages: List<Message>): List<ConversationEntry> = mutex.withLock {
        appendLocked(messages)
    }

    // mutex 已持有的追加实现；构建新 State 而非原地修改
    private fun appendLocked(messages: List<Message>): List<ConversationEntry> {
        if (messages.isEmpty()) return emptyList()
        var entries = state.entries
        var byId = state.byId
        var leaf = state.leafId
        val appended = ArrayList<ConversationEntry>(messages.size)
        for (message in messages) {
            val entry = ConversationEntry(
                id = Uuid.random().toString(),
                parentId = leaf,
                timestamp = Clock.System.now().toEpochMilliseconds(),
                message = message
            )
            appended += entry
            entries = entries + entry
            byId = byId + (entry.id to entry)
            leaf = entry.id
        }
        state = State(entries, byId, leaf)
        return appended
    }

    // 投影为公开快照（构造即复制，leafId 为当前位置）
    fun toSnapshot(live: AssistantMessage? = null): Conversation {
        val current = state
        val history = project(current.leafId).map { MessageEntry(it.id, it.timestamp, it.message) }
        return Conversation(id = id, leafId = current.leafId, history = history, live = live)
    }

    // 原地移动 leafId 到 entryId；被跳过的尾部保留在树中。
    // entryId 不存在时抛 IllegalArgumentException；位置语义不校验。
    suspend fun rewind(entryId: String): Unit = mutex.withLock {
        require(state.byId.containsKey(entryId)) { "entry not found: $entryId" }
        state = State(state.entries, state.byId, entryId)
    }

    // leaf 到 root 的线性投影（构造校验 + append 只引用真实 leaf，链上必命中）
    private fun project(leafId: String?): List<ConversationEntry> {
        if (leafId == null) return emptyList()
        val byId = state.byId
        val reversed = ArrayList<ConversationEntry>()
        var cursor: String? = leafId
        while (cursor != null) {
            val entry = byId.getValue(cursor)
            reversed += entry
            cursor = entry.parentId
        }
        reversed.reverse()
        return reversed
    }
}
