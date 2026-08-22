package com.niki914.nexus.agentic.app.conversation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.niki914.logging.Logger

@Dao
interface ConversationDao {
    companion object {
        private const val LOG_TAG = "niki914_nexus_ConversationDao"
    }

    @Query("SELECT * FROM conversation ORDER BY updated_at DESC")
    suspend fun listConversationsQuery(): List<ConversationEntity>

    suspend fun listConversations(): List<ConversationEntity> {
        val startedAtMs = System.currentTimeMillis()
        return listConversationsQuery().also { rows ->
            Logger.i(
                LOG_TAG,
                "query listConversations rows=${rows.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    @Query("SELECT * FROM conversation WHERE id = :id LIMIT 1")
    suspend fun getConversationQuery(id: String): ConversationEntity?

    suspend fun getConversation(id: String): ConversationEntity? {
        val startedAtMs = System.currentTimeMillis()
        return getConversationQuery(id).also { row ->
            Logger.d(
                LOG_TAG,
                "query getConversation id=$id found=${row != null} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(entity: ConversationEntity): Long

    @Query(
        """
        SELECT * FROM conversation_entry
        WHERE conversation_id = :conversationId
        """,
    )
    suspend fun listEntriesQuery(conversationId: String): List<ConversationEntryEntity>

    suspend fun listEntries(conversationId: String): List<ConversationEntryEntity> {
        val startedAtMs = System.currentTimeMillis()
        return listEntriesQuery(conversationId).also { rows ->
            Logger.d(
                LOG_TAG,
                "query listEntries conversationId=$conversationId rows=${rows.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    @Query("SELECT COUNT(*) FROM conversation_entry WHERE conversation_id = :conversationId")
    suspend fun countEntries(conversationId: String): Int

    /**
     * 消息级增量落盘（D3-2/D3-8）：按 (conversation_id, id) 幂等，观察协程
     * 重启/切会话重复观察不重复写。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntries(entries: List<ConversationEntryEntity>)

    @Query(
        """
        UPDATE conversation
        SET updated_at = :updatedAt,
            last_message_preview = :lastMessagePreview,
            turn_count = :turnCount
        WHERE id = :conversationId
        """,
    )
    suspend fun updateConversationMetadata(
        conversationId: String,
        updatedAt: Long,
        lastMessagePreview: String,
        turnCount: Int,
    ): Int

    @Query("UPDATE conversation SET leaf_id = :leafId WHERE id = :conversationId")
    suspend fun updateLeafId(conversationId: String, leafId: String): Int

    @Query("UPDATE conversation SET draft_text = :draftText WHERE id = :conversationId")
    suspend fun updateDraft(conversationId: String, draftText: String): Int

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun deleteConversation(id: String): Int

    @Query("UPDATE conversation SET title = :title, title_edited = 1 WHERE id = :id")
    suspend fun renameConversation(id: String, title: String): Int
}
