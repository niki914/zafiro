package com.niki914.zafiro.app.conversation

import android.content.Context
import com.niki914.logging.Logger
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.app.R
import kotlinx.serialization.json.Json
import java.util.UUID

/** fork / regenerate 派生会话的命名前缀（D3-11）。 */
enum class ForkKind {
    Fork,
    Regenerate,
    Rewind,
}

/**
 * 派生结果：新会话 id + 需要回填草稿的原用户输入。
 *
 * Fork 不回填（截断保留整轮，草稿为空）；Regenerate / Rewind 回填该回合的
 * 文本与附件（[Attachment] 是契约类型，okia 的 ContentBlock.Image 不出仓储层）。
 */
data class ForkResult(
    val newConversationId: String,
    val promptText: String = "",
    val attachments: List<Attachment> = emptyList(),
)

object ConversationRepo {
    private const val LOG_TAG = "niki914_zafiro_ConversationRepo"
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

    @Volatile
    private var rewindTitleFormat = "Rewind · %1\$s"

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
                rewindTitleFormat = runCatching {
                    context.getString(R.string.conversation_rewind_title)
                }.getOrDefault(rewindTitleFormat)
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

    /**
     * 会话是否已建档（只查行，不读快照与条目；[getConversation] 会装配快照）。
     *
     * 用于「建档可能已经发生过」的幂等路径：`createConversation` 的 DAO 冲突策略是
     * ABORT，重复建档会抛异常。
     */
    suspend fun exists(id: String): Boolean = dao().getConversation(id) != null

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
     * 回合级派生（D3-10/D3-11）：把源会话截断到某个回合的条目边界，复制成
     * 一个新会话（entries 原样复制、id 共享，复合主键允许跨会话同 id），
     * 新会话树 id = 新 Room id（loadConversation 时 open(restore) 对齐）。
     *
     * 截断点由 leaf 投影上的 User 计数反推——UI 的 turnId 就是回合下标：
     * - [ForkKind.Regenerate] / [ForkKind.Rewind]：截在该回合的用户条目之前
     *   （不含），原输入随 [ForkResult] 返回，由调用方回填草稿；
     * - [ForkKind.Fork]：整回合保留，截在下一个用户条目之前（无则到尾部）。
     *
     * @param turnIndex 目标回合下标（第 N 个用户消息）。
     * @return 源会话不存在或该回合不存在时 null。
     */
    suspend fun forkAtTurn(
        sourceId: String,
        turnIndex: Int,
        kind: ForkKind,
        now: Long = System.currentTimeMillis(),
    ): ForkResult? {
        val startedAtMs = System.currentTimeMillis()
        val source = dao().getConversation(sourceId) ?: return null

        val projected = ConversationFormatter.projectLeaf(
            dao().listEntries(sourceId).mapNotNull { it.toConversationEntry() },
            source.leafId,
        )
        val userEntryIndex = projected.indexOfUserTurn(turnIndex)
        if (userEntryIndex < 0) return null
        val keepEntryCount = when (kind) {
            ForkKind.Fork -> projected.indexOfNextUserTurn(userEntryIndex + 1) ?: projected.size
            ForkKind.Regenerate, ForkKind.Rewind -> userEntryIndex
        }
        val truncated = projected.take(keepEntryCount)

        val newId = UUID.randomUUID().toString()
        val titleFormat = when (kind) {
            ForkKind.Fork -> forkTitleFormat
            ForkKind.Regenerate -> regenerateTitleFormat
            ForkKind.Rewind -> rewindTitleFormat
        }
        val preview = ConversationFormatter.previewFromEntries(truncated)

        dao().forkConversationTransaction(
            conversation = ConversationEntity(
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
            entries = truncated.map { entry -> entry.toEntity(newId) },
        )
        Logger.i(
            LOG_TAG,
            "fork done sourceId=$sourceId kind=$kind turnIndex=$turnIndex " +
                    "keepEntryCount=$keepEntryCount newId=$newId entries=${truncated.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        if (kind == ForkKind.Fork) return ForkResult(newId)
        val userMessage = projected[userEntryIndex].message as Message.User
        return ForkResult(
            newConversationId = newId,
            promptText = userMessage.text(),
            attachments = userMessage.attachments(),
        )
    }

    /** 第 [turnIndex] 个 User 条目在 leaf 投影里的下标；不存在返回 -1。 */
    private fun List<ConversationEntry>.indexOfUserTurn(turnIndex: Int): Int {
        var seen = 0
        forEachIndexed { index, entry ->
            if (entry.message is Message.User) {
                if (seen == turnIndex) return index
                seen++
            }
        }
        return -1
    }

    /** [fromEntryIndex] 起第一个 User 条目的下标；不存在返回 null。 */
    private fun List<ConversationEntry>.indexOfNextUserTurn(fromEntryIndex: Int): Int? {
        for (index in fromEntryIndex until size) {
            if (get(index).message is Message.User) return index
        }
        return null
    }

    private fun Message.User.text(): String =
        content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    private fun Message.User.attachments(): List<Attachment> =
        content.filterIsInstance<ContentBlock.Image>().map { Attachment(it.path, it.mimeType) }

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

    /**
     * 消息增量 + leaf + metadata 原子落盘（问题 5 修复，持久化器调用）：
     * 一个事务完成，进程死亡不留下中间态。
     */
    suspend fun appendEntriesAtomically(
        conversationId: String,
        entries: List<ConversationEntry>,
        leafId: String,
        updatedAt: Long,
        lastMessagePreview: String,
        turnCount: Int,
    ) {
        if (entries.isEmpty()) return
        dao().appendEntriesTransaction(
            conversationId = conversationId,
            entries = entries.map { it.toEntity(conversationId) },
            leafId = leafId,
            updatedAt = updatedAt,
            lastMessagePreview = lastMessagePreview,
            turnCount = turnCount,
        )
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

    /** Room 行 → OKIA 会话树快照（leafId null 由 OKIA 恢复为最后一条，§5.3）。 */
    private suspend fun readSnapshot(id: String, storedLeafId: String?): SessionSnapshot {
        val entries = dao().listEntries(id).mapNotNull { entity ->
            entity.toConversationEntry()
        }
        return SessionSnapshot(
            id = id,
            leafId = storedLeafId,
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
