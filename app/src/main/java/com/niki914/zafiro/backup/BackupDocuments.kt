package com.niki914.zafiro.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class ManifestDocument(
    @SerialName("format_version")
    val formatVersion: Int,
    @SerialName("app_version")
    val appVersion: String = "",
    @SerialName("created_at")
    val createdAt: Long = 0L,
    val secrets: String,
    val groups: Map<String, List<String>> = emptyMap(),
    val partitions: Map<String, PartitionWire> = emptyMap(),
    @SerialName("external_attachment_paths")
    val externalAttachmentPaths: Int = 0,
)

@Serializable
internal data class PartitionWire(
    val group: String,
    val count: Int,
    val state: String,
    val apply: String,
    @SerialName("singleton_apply")
    val singletonApply: String? = null,
)

@Serializable
internal data class ConversationDocument(
    val conversations: List<ConversationWire> = emptyList(),
    val entries: List<EntryWire> = emptyList(),
    @SerialName("last_opened_conversation_id")
    val lastOpenedConversationId: String = "",
    @SerialName("draft_text")
    val draftText: String = "",
    @SerialName("pinned_conversations")
    val pinnedConversations: JsonElement,
)

@Serializable
internal data class ConversationWire(
    val id: String,
    val title: String,
    @SerialName("title_edited")
    val titleEdited: Boolean = false,
    @SerialName("created_at")
    val createdAt: Long = 0L,
    @SerialName("updated_at")
    val updatedAt: Long = 0L,
    @SerialName("last_message_preview")
    val lastMessagePreview: String = "",
    @SerialName("turn_count")
    val turnCount: Int = 0,
    @SerialName("leaf_id")
    val leafId: String? = null,
    @SerialName("config_id")
    val configId: String = "",
    @SerialName("has_unread")
    val hasUnread: Boolean = false,
    @SerialName("root_id")
    val rootId: String = "",
    @SerialName("parent_id")
    val parentId: String? = null,
    @SerialName("fork_turn")
    val forkTurn: Int? = null,
)

@Serializable
internal data class EntryWire(
    @SerialName("conversation_id")
    val conversationId: String,
    val id: String,
    @SerialName("parent_id")
    val parentId: String? = null,
    val timestamp: Long = 0L,
    val message: JsonElement,
)
