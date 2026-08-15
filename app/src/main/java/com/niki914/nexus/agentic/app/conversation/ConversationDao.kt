package com.niki914.nexus.agentic.app.conversation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    @Query(
        """
        SELECT * FROM conversation_turn
        WHERE conversation_id = :conversationId
        ORDER BY turn_index ASC
        """,
    )
    suspend fun listTurnsQuery(conversationId: String): List<ConversationTurnEntity>

    suspend fun listTurns(conversationId: String): List<ConversationTurnEntity> {
        val startedAtMs = System.currentTimeMillis()
        return listTurnsQuery(conversationId).also { rows ->
            Logger.i(
                LOG_TAG,
                "query listTurns conversationId=$conversationId rows=${rows.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertConversation(entity: ConversationEntity): Long

    @Query("DELETE FROM conversation_turn WHERE conversation_id = :conversationId")
    suspend fun deleteTurns(conversationId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurns(turns: List<ConversationTurnEntity>): List<Long>

    @Query(
        """
        UPDATE conversation
        SET updated_at = :updatedAt,
            last_message_preview = :lastMessagePreview,
            turn_count = :turnCount
        WHERE id = :conversationId
        """,
    )
    suspend fun updateHistoryMetadata(
        conversationId: String,
        updatedAt: Long,
        lastMessagePreview: String,
        turnCount: Int,
    ): Int

    @Query("UPDATE conversation SET draft_text = :draftText WHERE id = :conversationId")
    suspend fun updateDraft(conversationId: String, draftText: String): Int

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun deleteConversation(id: String): Int

    @Query("UPDATE conversation SET title = :title, title_edited = 1 WHERE id = :id")
    suspend fun renameConversation(id: String, title: String): Int

    @Transaction
    suspend fun replaceTurnsAndMetadata(
        conversationId: String,
        turns: List<ConversationTurnEntity>,
        updatedAt: Long,
        lastMessagePreview: String,
        turnCount: Int,
    ): Int {
        deleteTurns(conversationId)
        insertTurns(turns)
        return updateHistoryMetadata(
            conversationId = conversationId,
            updatedAt = updatedAt,
            lastMessagePreview = lastMessagePreview,
            turnCount = turnCount,
        )
    }
}
