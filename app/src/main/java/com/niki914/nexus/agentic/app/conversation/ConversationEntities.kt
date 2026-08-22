package com.niki914.nexus.agentic.app.conversation

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.niki914.okia.conversation.SessionSnapshot

@Entity(tableName = "conversation")
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
    @ColumnInfo(name = "draft_text")
    val draftText: String,
    @ColumnInfo(name = "leaf_id")
    val leafId: String?,
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
)

/**
 * 一条完整会话记录：元信息 + 草稿 + 可恢复的 OKIA 会话树快照。
 * T3 起历史不再以平列表承载，而是整棵树快照（open(restore) 直接消费）。
 */
data class ConversationRecord(
    val summary: ConversationSummary,
    val draftText: String,
    val snapshot: SessionSnapshot,
)
