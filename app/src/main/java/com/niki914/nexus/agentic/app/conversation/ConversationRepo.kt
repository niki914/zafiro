package com.niki914.nexus.agentic.app.conversation

import android.content.Context
import com.niki914.logging.Logger
import com.niki914.nexus.agentic.app.R
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.Message
import java.util.UUID
import kotlinx.serialization.json.Json

/** fork / regenerate 派生会话的命名前缀（D3-11）。 */
enum class ForkKind {
    Fork,
    Regenerate,
}

object ConversationRepo {
    private const val LOG_TAG = "niki914_nexus_ConversationRepo"
    private const val SNAPSHOT_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var database: ConversationDatabase? = null

    @Volatile
    private var forkTitleFormat = "Fork · %1\$s"
    @Volatile
    private var regenerateTitleFormat = "Regenerate · %1\$s"

    fun init(context: Context) {
        if (database != null) return
        synchronized(this) {
            if (database == null) {
                database = buildConversationDatabase(context.applicationContext)
                // Robolectric 4.13 + AGP 9 下 getString 对任意 ID 报 Bad identifier
                // （T3 实测，非本字符串问题）；生产真机资源正常，测试 fallback 硬编码
                forkTitleFormat = runCatching {
                    context.getString(R.string.conversation_fork_title)
                }.getOrDefault(forkTitleFormat)
                regenerateTitleFormat = runCatching {
                    context.getString(R.string.conversation_regenerate_title)
                }.getOrDefault(regenerateTitleFormat)
            }
        }
    }

    suspend fun listConversations(): List<ConversationSummary> {
        val startedAtMs = System.currentTimeMillis()
        return dao().listConversations().map { it.toSummary() }.also { summaries ->
            Logger.i(
                LOG_TAG,
                "list conversations count=${summaries.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    suspend fun getConversation(id: String): ConversationRecord? {
        val startedAtMs = System.currentTimeMillis()
        val conversation = dao().getConversation(id)
        if (conversation == null) {
            Logger.d(
                LOG_TAG,
                "get conversation id=$id notFound " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            return null
        }
        val snapshot = readSnapshot(id, conversation.leafId)
        return ConversationRecord(
            summary = conversation.toSummary(),
            draftText = conversation.draftText,
            snapshot = snapshot,
        ).also {
            Logger.i(
                LOG_TAG,
                "get conversation id=$id entries=${snapshot.entries.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
        }
    }

    /**
     * 显式 id 创建会话（T3：id = OKIA 树 id，保证 Room id == 树 id）。
     */
    suspend fun createConversation(
        id: String,
        firstUserInput: String,
        now: Long = System.currentTimeMillis(),
    ): String {
        val startedAtMs = System.currentTimeMillis()
        dao().insertConversation(
            ConversationEntity(
                id = id,
                title = ConversationFormatter.titleFromFirstInput(firstUserInput),
                titleEdited = false,
                createdAt = now,
                updatedAt = now,
                lastMessagePreview = ConversationFormatter.previewFromText(firstUserInput),
                turnCount = 0,
                draftText = "",
                leafId = null,
            ),
        )
        Logger.i(
            LOG_TAG,
            "conversation created id=$id " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return id
    }

    /**
     * fork/regenerate（D3-10/D3-11）：复制源会话的截断子树到新会话
     * （entries 原样复制、id 共享，复合主键允许跨会话同 id），新会话
     * 树 id = 新 Room id（loadConversation 时 open(restore) 对齐）。
     */
    suspend fun forkConversation(
        sourceId: String,
        keepEntryCount: Int,
        kind: ForkKind,
        now: Long = System.currentTimeMillis(),
    ): String {
        val startedAtMs = System.currentTimeMillis()
        val source = dao().getConversation(sourceId)
            ?: throw IllegalStateException("Source conversation not found: $sourceId")

        val allEntries = dao().listEntries(sourceId)
        val projected = ConversationFormatter.projectLeaf(
            allEntries.mapNotNull { it.toConversationEntry() },
            source.leafId,
        )
        val truncated = projected.take(keepEntryCount)

        val newId = UUID.randomUUID().toString()
        val titleFormat = when (kind) {
            ForkKind.Fork -> forkTitleFormat
            ForkKind.Regenerate -> regenerateTitleFormat
        }
        val preview = ConversationFormatter.previewFromEntries(truncated)

        dao().insertConversation(
            ConversationEntity(
                id = newId,
                title = String.format(titleFormat, source.title),
                titleEdited = true,
                createdAt = now,
                updatedAt = now,
                lastMessagePreview = preview,
                turnCount = truncated.size,
                draftText = "",
                leafId = truncated.lastOrNull()?.id,
            ),
        )
        dao().insertEntries(
            truncated.map { entry ->
                entry.toEntity(newId)
            },
        )
        Logger.i(
            LOG_TAG,
            "fork done sourceId=$sourceId kind=$kind keepEntryCount=$keepEntryCount " +
                "newId=$newId entries=${truncated.size} " +
                "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return newId
    }

    /**
     * 消息级增量落盘（D3-2/D3-8，持久化器调用）：插入新 commit 的消息，
     * 按 (conversation_id, id) 幂等。
     */
    suspend fun insertEntries(
        conversationId: String,
        entries: List<ConversationEntry>,
    ) {
        if (entries.isEmpty()) return
        dao().insertEntries(entries.map { it.toEntity(conversationId) })
    }

    suspend fun countEntries(conversationId: String): Int = dao().countEntries(conversationId)

    suspend fun updateLeafId(conversationId: String, leafId: String) {
        dao().updateLeafId(conversationId, leafId)
    }

    suspend fun updateConversationMetadata(
        conversationId: String,
        updatedAt: Long,
        lastMessagePreview: String,
        turnCount: Int,
    ) {
        dao().updateConversationMetadata(
            conversationId = conversationId,
            updatedAt = updatedAt,
            lastMessagePreview = lastMessagePreview,
            turnCount = turnCount,
        )
    }

    suspend fun updateDraft(conversationId: String, draftText: String) {
        dao().updateDraft(conversationId = conversationId, draftText = draftText)
    }

    suspend fun deleteConversation(id: String) {
        dao().deleteConversation(id)
    }

    suspend fun renameConversation(id: String, title: String) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return
        dao().renameConversation(id = id, title = trimmedTitle)
    }

    internal suspend fun closeForTest() {
        synchronized(this) {
            database?.close()
            database = null
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun dao(): ConversationDao {
        return requireNotNull(database) {
            "ConversationRepo.init(context) must be called before use."
        }.conversationDao()
    }

    /** Room 行 → OKIA 会话树快照（leafId 缺省时显式取最后一条，绕 OKIA project(null) 坑 D16）。 */
    private suspend fun readSnapshot(id: String, storedLeafId: String?): SessionSnapshot {
        val entries = dao().listEntries(id).mapNotNull { entity ->
            entity.toConversationEntry()
        }
        val leafId = storedLeafId ?: entries.lastOrNull()?.id
        return SessionSnapshot(
            id = id,
            leafId = leafId,
            version = SNAPSHOT_VERSION,
            entries = entries,
        )
    }

    private fun ConversationEntryEntity.toConversationEntry(): ConversationEntry? {
        val message = runCatching {
            json.decodeFromString(Message.serializer(), messageJson)
        }.getOrNull() ?: return null
        return ConversationEntry(
            id = id,
            parentId = parentId,
            timestamp = timestamp,
            message = message,
        )
    }

    private fun ConversationEntry.toEntity(conversationId: String): ConversationEntryEntity {
        return ConversationEntryEntity(
            conversationId = conversationId,
            id = id,
            parentId = parentId,
            timestamp = timestamp,
            messageJson = json.encodeToString(Message.serializer(), message),
        )
    }

    private fun ConversationEntity.toSummary(): ConversationSummary {
        return ConversationSummary(
            id = id,
            title = title,
            titleEdited = titleEdited,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastMessagePreview = lastMessagePreview,
            turnCount = turnCount,
        )
    }
}
