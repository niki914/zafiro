package com.niki914.okia.conversation

import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.Message
import kotlinx.serialization.Serializable

/**
 * UI 友好的不可变快照：StateFlow 每次发射都是完整状态，观察者只读不写。
 * leafId 为 rewind 当前位置（null = 最后一条），UI 可读不可写。
 * history 为已提交的完整消息（leaf 路径投影，平列表，不做 turn 分组——
 * turn 边界由下游按 Message.User 自行封装）；live 为正在流式、尚未成条的
 * 助手消息，空闲为 null。历史变化与 live 更新都会重新发射。
 * 快照值构造即复制：List 字段为防御性复制，外部持有不影响内部存储。
 * Design source: okia PRD §5.4（StateFlow 投影）+ 白板 W1（MVI）。
 */
data class Conversation(
    val id: String,
    val leafId: String?,
    val history: List<MessageEntry>,
    val live: AssistantMessage? = null
)

/**
 * 门面条目：id 与 timestamp 与消息绑定，是 rewind(entryId) 的目标。
 * 下游只需认识本类型 + Message，不需要接触树结构。turn 级回退 =
 * 取 Message.User 对应的 entry id（下游自行封装，库不替下游决定粒度）。
 * Design source: okia CR #1 门面化裁决（避免暴露树节点类型）。
 */
data class MessageEntry(
    val id: String,
    val timestamp: Long,
    val message: Message
)

/**
 * 树节点与持久化行格式（SessionSnapshot.entries）。id / parentId 构成
 * append-only 链。非门面类型：下游仅在持久化时接触。
 * Design source: pi（session-manager.ts）SessionEntryBase { id, parentId }。
 */
@Serializable
data class ConversationEntry(
    val id: String,
    val parentId: String?,
    val timestamp: Long,
    val message: Message
)
