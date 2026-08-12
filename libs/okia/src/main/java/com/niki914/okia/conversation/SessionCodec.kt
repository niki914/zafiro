package com.niki914.okia.conversation

import kotlinx.serialization.Serializable

/**
 * 一份会话快照的持久化契约。存储位置与后端由 host 决定，这里只做
 * 快照 ↔ 交换格式转换。id / parentId / 条目 id / leaf 位置 / 时间戳持久化，
 * 使 host 在重载后重建树、fork 链与当前位置。
 * 快照由门面 Okia.export() 产出、open(restore = snapshot) 消费（§5.3）；
 * codec 只做编解码，不接触门面与树。
 * leafId 必须持久化：rewind 位置重载后保持；null = 恢复为最后一条。
 * version 供 schema 演进迁移。
 * Design source: kai PRD §4.6；pi（session-manager.ts）显式 leafId + 头部 version。
 */
interface SessionCodec {

    // 快照 → 交换格式
    fun encode(snapshot: SessionSnapshot): String = TODO()

    // 交换格式 → 快照
    fun decode(raw: String): SessionSnapshot = TODO()
}

/**
 * 一份会话的持久化视图：身份、fork 父、当前 leaf 与条目。
 * leaf 是当前位置，缺省则 rewind 位置在重载后丢失。
 */
@Serializable
data class SessionSnapshot(
    val id: String,
    val parentSessionId: String?,
    val leafId: String?,
    val version: Int,
    val entries: List<ConversationEntry>
)
