package com.niki914.okia.conversation

import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.Message
import kotlinx.coroutines.sync.Mutex

/**
 * 对话树数据结构维护者（内部实现；公开面是 Conversation 快照）。
 * 条目树 + leafId 当前位置，内部 Mutex 串行化（KMP 下唯一同步方案 =
 * kotlinx.coroutines.sync.Mutex）。
 * fork 复制当前 leaf 路径（节点不可变共享，修改互不影响）；rewind 原地
 * 移动 leafId，被跳过的尾部保留在树中。rewind 不校验目标合法性（放开，
 * 回退粒度由下游自行约束）；历史投影 = leaf 到 root 线性投影。
 * Design source: pi（session-manager.ts）buildSessionPath / createBranchedSession，
 * W3 白板便签；命名参考 OkHttp Real* 惯例。
 */
internal class RealConversation(
    val id: String,
    val parentSessionId: String?,
    entries: List<ConversationEntry>,
    leafId: String?
) {

    // 树形条目（append-only，fork 共享不可变节点）。
    // 返回防御性复制：外部持有不影响内部存储。
    val entries: List<ConversationEntry> get() = TODO()

    // 当前 leaf 位置；append 前进，rewind 移动
    val leafId: String? get() = TODO()

    // leaf 到 root 的线性历史投影，按对话顺序。
    // 返回防御性复制：外部持有不影响内部存储。
    val history: List<Message> get() = TODO()

    // 追加一条消息，返回新条目
    suspend fun append(message: Message): ConversationEntry = TODO()

    // 同一把 Mutex 下批量追加（回合产出提交入口，原子）
    suspend fun appendAll(messages: List<Message>): List<ConversationEntry> = TODO()

    // 投影为公开快照（构造即复制，leafId 为当前位置）
    fun toSnapshot(live: AssistantMessage? = null): Conversation = TODO()

    // 新对话：从当前 leaf 路径 fork，节点不可变共享
    suspend fun fork(): RealConversation = TODO()

    // 原地移动 leafId 到 entryId；被跳过的尾部保留在树中。不校验合法性。
    suspend fun rewind(entryId: String): Unit = TODO()

    // 串行化所有数据操作
    private val mutex: Mutex = TODO()
}
