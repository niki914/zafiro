package com.niki914.zafiro.app.conversation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.niki914.logging.Logger
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    companion object {
        private const val LOG_TAG = "niki914_zafiro_ConversationDao"
    }

    @Query("SELECT * FROM conversation ORDER BY updated_at DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    /**
     * 对话列表：每个分支组只出一行——组内 `updated_at` 最新的那一版。
     *
     * 取「最近更新」而非「版本序号最大」：preview、点击落点、列表排序键
     * 三者必须指向同一行，否则在老版本里继续对话时列表纹丝不动。
     */
    @Query(
        """
        SELECT * FROM conversation
        WHERE updated_at = (
            SELECT MAX(updated_at) FROM conversation AS sibling
            WHERE sibling.root_id = conversation.root_id
        )
        ORDER BY updated_at DESC
        """,
    )
    fun observeLatestBranches(): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT * FROM conversation
        WHERE updated_at = (
            SELECT MAX(updated_at) FROM conversation AS sibling
            WHERE sibling.root_id = conversation.root_id
        )
        ORDER BY updated_at DESC
        """,
    )
    suspend fun listLatestBranches(): List<ConversationEntity>

    @Query("SELECT * FROM conversation WHERE id = :id LIMIT 1")
    suspend fun getConversationQuery(id: String): ConversationEntity?

    /** 分支组的全部成员，按创建时间升序。 */
    @Query("SELECT * FROM conversation WHERE root_id = :rootId ORDER BY created_at ASC")
    suspend fun listBranchGroup(rootId: String): List<ConversationEntity>

    /** 组内最近更新的一版（冷启动 / 列表点击的落点，与 [observeLatestBranches] 同键）。 */
    @Query("SELECT * FROM conversation WHERE root_id = :rootId ORDER BY updated_at DESC LIMIT 1")
    suspend fun latestBranch(rootId: String): ConversationEntity?

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

    @Query(
        """
        SELECT * FROM conversation_entry
        WHERE message_json LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
        """,
    )
    suspend fun searchEntriesQuery(query: String): List<ConversationEntryEntity>

    suspend fun searchEntries(query: String): List<ConversationEntryEntity> {
        val startedAtMs = System.currentTimeMillis()
        return searchEntriesQuery(query).also { rows ->
            Logger.d(
                LOG_TAG,
                "query searchEntries query=$query rows=${rows.size} " +
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
        SET last_message_preview = :lastMessagePreview,
            turn_count = :turnCount
        WHERE id = :conversationId
        """,
    )
    suspend fun updateConversationProgress(
        conversationId: String,
        lastMessagePreview: String,
        turnCount: Int,
    ): Int

    @Query("UPDATE conversation SET updated_at = :updatedAt WHERE id = :conversationId")
    suspend fun updateConversationUpdatedAt(
        conversationId: String,
        updatedAt: Long,
    ): Int

    @Query("UPDATE conversation SET leaf_id = :leafId WHERE id = :conversationId")
    suspend fun updateLeafId(conversationId: String, leafId: String): Int

    @Query("UPDATE conversation SET config_id = :configId WHERE id = :conversationId")
    suspend fun updateConfigId(conversationId: String, configId: String): Int

    @Query("UPDATE conversation SET has_unread = :hasUnread WHERE id = :conversationId")
    suspend fun updateHasUnread(conversationId: String, hasUnread: Boolean): Int

    /**
     * 死字段操作（T3 遗留）：保留仅防编译破坏，业务层严禁再调用。草稿已改用 XRepo 全局管理。
     */
    @Deprecated("Dead method: draft has migrated to XRepo global storage.")
    @Query("UPDATE conversation SET draft_text = :draftText WHERE id = :conversationId")
    suspend fun updateDraft(conversationId: String, draftText: String): Int

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun deleteConversation(id: String): Int

    /** 删除整个分支组（含根）；用于「删掉一个会话就是删掉它的全部重新生成版本」。 */
    @Query("DELETE FROM conversation WHERE root_id = :rootId")
    suspend fun deleteBranchGroup(rootId: String): Int

    /** 组内所有分支，按创建时间升序（删除时需先逐个销毁 Agent）。 */
    @Query("SELECT id FROM conversation WHERE root_id = :rootId ORDER BY created_at ASC")
    suspend fun listBranchGroupIds(rootId: String): List<String>

    @Query("UPDATE conversation SET title = :title, title_edited = 1 WHERE root_id = :rootId")
    suspend fun renameBranchGroup(rootId: String, title: String): Int

    /**
     * 消息增量落盘原子化（问题 5 修复）：entry insert + leaf + 进度
     * 一个事务完成——进程死亡不会留下「entries 已插入但 leaf_id 陈旧」
     * 的不可达节点中间态。
     *
     * 只写进度不写 `updated_at`（防多会话并发时列表上下跳动）；时间戳由
     * [updateConversationUpdatedAt] 在会话的生命周期边界单独刷新。
     */
    @Transaction
    suspend fun appendEntriesTransaction(
        conversationId: String,
        entries: List<ConversationEntryEntity>,
        leafId: String,
        lastMessagePreview: String,
        turnCount: Int,
    ) {
        insertEntries(entries)
        updateLeafId(conversationId, leafId)
        updateConversationProgress(conversationId, lastMessagePreview, turnCount)
    }

    /** fork/regenerate 的 conversation row + entries 一个事务完成（问题 5 修复）。 */
    @Transaction
    suspend fun forkConversationTransaction(
        conversation: ConversationEntity,
        entries: List<ConversationEntryEntity>,
    ) {
        insertConversation(conversation)
        insertEntries(entries)
    }
}
