package com.niki914.nexus.agentic.app.conversation

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.niki914.kai.ChatTurn

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
)

@Entity(
    tableName = "conversation_turn",
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
        Index(value = ["conversation_id", "turn_index"], unique = true),
    ],
)
data class ConversationTurnEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    @ColumnInfo(name = "turn_index")
    val turnIndex: Int,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "payload_json")
    val payloadJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
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

data class ConversationRecord(
    val summary: ConversationSummary,
    val draftText: String,
    val history: List<ChatTurn>,
)

enum class StoredChatTurnKind {
    User,
    Assistant,
    ToolResult,
}
