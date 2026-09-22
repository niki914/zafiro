package com.niki914.zafiro.business.agent

import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId

// 存废：阶段 3 替换（临时存储端口：Room 只在 app，本端口把 app 的读出面接给实现侧；
// M7 定案会话实例落点后换成正式端口）

/**
 * 会话持久化的读出面（app 侧实现，Room 在那里）。
 *
 * 实现侧只经本端口读写会话记录，不碰 Room：`Agent.load()` 由实现侧驱动，
 * 业务方不参与。
 */
interface ConversationStore_Tmp {

    /** 读一条已持久化的会话；不存在返回 null。 */
    suspend fun load(id: ConversationId): StoredConversation_Tmp?

    /** 该会话是否已建档（不读快照）。建档可能已经由别处发生过，见 `create` 的幂等要求。 */
    suspend fun exists(id: ConversationId): Boolean

    /** 首次建档：会话 id 与首条提问（树 id 即 Room id）。 */
    suspend fun create(id: ConversationId, firstUserInput: String)

    /** 草稿文本落盘（Room 的 `draft_text` 列）。 */
    suspend fun saveDraft(id: ConversationId, draftText: String)

    /** 记录最后打开的会话；null = 清空。 */
    suspend fun setLastOpened(id: ConversationId?)
}

/**
 * 持久化读出的一条会话。
 *
 * @property snapshot 引擎恢复快照（`open(restore)` 的入参）。
 * @property conversation 装配好的回合列表（历史恢复与流式归约共用同一 id 规则）。
 * @property draftText 该会话的草稿文本。
 */
data class StoredConversation_Tmp(
    val snapshot: SessionSnapshot,
    val conversation: Conversation,
    val draftText: String,
)
