package com.niki914.zafiro.app.conversation

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.niki914.okia.conversation.SessionSnapshot

@Entity(
    tableName = "conversation",
    // 对话列表按分支组收敛（根 = root_id，取组内 updated_at 最新者）
    indices = [Index(value = ["root_id"])],
)
data class ConversationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "title_edited")
    val titleEdited: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
    @ColumnInfo(name = "last_message_preview")
    val lastMessagePreview: String,
    @ColumnInfo(name = "turn_count")
    val turnCount: Int,
    /**
     * // githooks:ignore legacy-comment
     * 死字段（T3 遗留）：旧版在此按会话持久化草稿文本。
     * 现已改用 XRepo 全局草稿（跨会话/新建会话保持不丢，冷启动自动恢复）。
     * 此字段保留在 SQLite 表与实体中仅用于维持现有 Room Schema 校验兼容性，
     * 业务层严禁再读写该字段。
     */
    @ColumnInfo(name = "draft_text")
    val draftText: String = "",
    @ColumnInfo(name = "leaf_id")
    val leafId: String?,
    /** 绑定的 LLM 配置 id。空串表示未绑定（跟随全局活跃配置）。 */
    @ColumnInfo(name = "config_id", defaultValue = "''")
    val configId: String = "",
    /** 后台执行完成或等待审批时的未读标记。 */
    @ColumnInfo(name = "has_unread", defaultValue = "0")
    val hasUnread: Boolean = false,
    /**
     * 重新生成分支组的根会话 id（自身为根时等于 [id]）。
     * 对话列表按本列分组：一组只显示组内 updated_at 最新的那一行。
     */
    @ColumnInfo(name = "root_id", defaultValue = "''")
    val rootId: String = "",
    /** 分叉来源的直接父会话 id；自身为根时为 null。 */
    @ColumnInfo(name = "parent_id")
    val parentId: String? = null,
    /** 分叉发生在源会话的哪一个 Turn 下标；自身为根时为 null。 */
    @ColumnInfo(name = "fork_turn")
    val forkTurn: Int? = null,
)

/**
 * 会话树的一个节点（T3 持久化重做，D3-3）。
 * 复合主键 (conversation_id, id)：fork 复制出的会话共享同一批 entry id，
 * 不同会话下同 id 可共存。恢复时按 parent_id 链重投影（无序号列，YAGNI）。
 */
@Entity(
    tableName = "conversation_entry",
    primaryKeys = ["conversation_id", "id"],
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id"]),
    ],
)
data class ConversationEntryEntity(
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "parent_id")
    val parentId: String?,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "message_json")
    val messageJson: String,
)

data class ConversationSummary(
    val id: String,
    val title: String,
    val titleEdited: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessagePreview: String,
    val turnCount: Int,
    val hasUnread: Boolean = false,
    /** 分支组的根会话 id；自身为根时等于 [id]。 */
    val rootId: String = id,
    /** 分叉来源的直接父会话 id；自身为根时为 null。 */
    val parentId: String? = null,
    /** 分叉发生在源会话的哪一个 Turn 下标；自身为根时为 null。 */
    val forkTurn: Int? = null,
)

/**
 * 一条完整会话记录：元信息 + 草稿 + 可恢复的 OKIA 会话树快照。
 * T3 起历史不再以平列表承载，而是整棵树快照（open(restore) 直接消费）。
 */
data class ConversationRecord(
    val summary: ConversationSummary,
    /**
     * // githooks:ignore legacy-comment
     * 死字段（T3 遗留）：草稿已改由 XRepo 全局持久化。
     * 本字段保留默认值仅防破坏旧调用编译，始终为空串，业务层不再消费。
     */
    val draftText: String = "",
    val snapshot: SessionSnapshot,
    /** 绑定的 LLM 配置 id。空串表示未绑定（跟随全局活跃配置）。 */
    val configId: String = "",
)

/**
 * 历史会话搜索结果条目：包含会话基础元信息及命中的正文 Snippet 与匹配来源。
 */
data class ConversationSearchResult(
    val summary: ConversationSummary,
    /** 正文命中时提取的上下文片段；若仅标题命中则为 null。 */
    val matchedSnippet: String? = null,
    val matchedInTitle: Boolean = false,
    val matchedInBody: Boolean = false,
)
