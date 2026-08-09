package com.niki914.okia.conversation

import com.niki914.okia.message.Message
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable

/**
 * 树中的一条消息及其位置。id / parentId 构成 append-only 链。
 * Design source: pi（session-manager.ts）SessionEntryBase { id, parentId }。
 */
@Serializable
data class ConversationEntry(
    val id: String,
    val parentId: String?,
    val timestamp: Long,
    val message: Message
)

/**
 * 对话数据结构维护者：条目树 + leafId 当前位置，内部 Mutex 串行化
 * （KMP 下唯一同步方案 = kotlinx.coroutines.sync.Mutex）。
 * fork 复制当前 leaf 路径（节点不可变共享，修改互不影响）；rewind 原地
 * 移动 leafId，被跳过的尾部保留在树中。历史投影 = leaf 到 root 线性投影。
 * Design source: pi（session-manager.ts）buildSessionPath / createBranchedSession，
 * W3 白板便签。
 */
class Conversation(
    val id: String,
    val parentSessionId: String?,
    entries: List<ConversationEntry>,
    leafId: String?
) {

    // 树形条目（append-only，fork 共享不可变节点）
    val entries: List<ConversationEntry> get() = TODO()

    // 当前 leaf 位置；append 前进，rewind 移动
    val leafId: String? get() = TODO()

    // leaf 到 root 的线性历史投影，按对话顺序
    val history: List<Message> get() = TODO()

    // 追加一条消息，返回新条目
    suspend fun append(message: Message): ConversationEntry = TODO()

    // 新对话：从当前 leaf 路径 fork，节点不可变共享
    suspend fun fork(): Conversation = TODO()

    // 原地移动 leafId 到 entryId；被跳过的尾部保留在树中
    suspend fun rewind(entryId: String): Unit = TODO()

    // 串行化所有数据操作
    private val mutex: Mutex = TODO()
}
