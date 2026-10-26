package com.niki914.zafiro.app.conversation

import android.content.Context
import com.niki914.logging.Logger
import com.niki914.okia.conversation.ConversationEntry
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.api.model.Attachment
import com.niki914.zafiro.api.model.FileRef
import com.niki914.zafiro.api.text.FilesBlock
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.conversation.ConversationRepo.getConversation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
 * 文本与图片（[Attachment] 是契约类型，okia 的 ContentBlock.Image 不出仓储层）。
 * [files] 是从落盘文本里切出来的文件引用：回填草稿时既要把
 * 注入块从输入框里拿掉，又不能把用户附的文件弄丢。
 */
data class ForkResult(
    val newConversationId: String,
    val promptText: String = "",
    val images: List<Attachment> = emptyList(),
    val files: List<FileRef> = emptyList(),
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
                rewindTitleFormat = runCatching {
                    context.getString(R.string.conversation_rewind_title)
                }.getOrDefault(rewindTitleFormat)
            }
        }
    }

    fun observeConversations(): Flow<List<ConversationSummary>> {
        return dao().observeLatestBranches().map { rows ->
            rows.map { it.toSummary() }
        }
    }

    /**
     * 分支组信息（供对话页的 `<n/N>` 翻页器与族谱推导）：组内全部成员。
     */
    suspend fun branchGroup(conversationId: String): List<ConversationSummary> {
        val row = dao().getConversation(conversationId) ?: return emptyList()
        val group = dao().listBranchGroup(row.rootId.ifBlank { row.id })
        return if (group.size <= 1) emptyList() else group.map { it.toSummary() }
    }

    /** 组内最新分支的 id（冷启动 / 列表点击都落在这里）。 */
    suspend fun latestBranchId(rootId: String): String? = dao().latestBranch(rootId)?.id

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
            draftText = "", // 死字段：不读取旧库会话草稿，改由 XRepo 全局接管
            snapshot = snapshot,
            configId = conversation.configId,
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
     * 新建会话自成一个分支组的根（root_id = 自身，branch_index = 1）。
     */
    suspend fun createConversation(
        id: String,
        firstUserInput: String,
        title: String? = null,
        configId: String = "",
        now: Long = System.currentTimeMillis(),
    ): String {
        val startedAtMs = System.currentTimeMillis()
        val finalTitle = title?.takeIf { it.isNotBlank() }
            ?: ConversationFormatter.titleFromFirstInput(firstUserInput)
        val titleEdited = title?.isNotBlank() == true
        dao().insertConversation(
            ConversationEntity(
                id = id,
                title = finalTitle,
                titleEdited = titleEdited,
                createdAt = now,
                updatedAt = now,
                lastMessagePreview = ConversationFormatter.previewFromText(firstUserInput),
                turnCount = 0,
                draftText = "",
                leafId = null,
                configId = configId,
                rootId = id,
                parentId = null,
                forkTurn = null,
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
        val isRegenerate = kind == ForkKind.Regenerate
        // 重新生成的分支留在同一组内：共享原始标题，「重新生成过」由 branchIndex > 1 表达。
        val titleFormat = when (kind) {
            ForkKind.Fork -> forkTitleFormat
            ForkKind.Regenerate, ForkKind.Rewind -> rewindTitleFormat
        }
        // 只有 Regenerate 聚成一组；Fork 另起一线，Rewind 仍沿用旧行为（不入组）。
        val rootId = if (isRegenerate) source.rootId.ifBlank { source.id } else newId
        val parentId = if (isRegenerate) source.id else null
        val forkTurn = if (isRegenerate) turnIndex else null
        val preview = ConversationFormatter.previewFromEntries(truncated)

        dao().forkConversationTransaction(
            conversation = ConversationEntity(
                id = newId,
                title = if (isRegenerate) {
                    source.title
                } else {
                    String.format(titleFormat, source.title)
                },
                titleEdited = source.titleEdited || !isRegenerate,
                createdAt = now,
                updatedAt = now,
                lastMessagePreview = preview,
                turnCount = truncated.size,
                draftText = "",
                leafId = truncated.lastOrNull()?.id,
                configId = source.configId,
                rootId = rootId,
                parentId = parentId,
                forkTurn = forkTurn,
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
        // 注入块不进输入框，但里面的文件引用要回填：两者都从同一次切头里拿
        val stripped = FilesBlock.strip(userMessage.text())
        return ForkResult(
            newConversationId = newId,
            promptText = stripped.text,
            images = userMessage.images(),
            files = stripped.files,
        )
    }

    /**
     * 会话级完整派生：在当前会话的全部历史基础上复制出一条新分支会话。
     *
     * @param sourceId 源会话 id
     * @return 新生成的派生会话 id；源会话不存在时返回 null
     */
    suspend fun forkConversation(
        sourceId: String,
        now: Long = System.currentTimeMillis(),
    ): String? {
        val startedAtMs = System.currentTimeMillis()
        val source = dao().getConversation(sourceId) ?: return null

        val entries = dao().listEntries(sourceId)
        val newId = UUID.randomUUID().toString()
        val cleanTitle =
            ConversationFormatter.sanitizeDisplayTitle(source.title).ifBlank { source.title }
        val newTitle = String.format(forkTitleFormat, cleanTitle)

        dao().forkConversationTransaction(
            conversation = source.copy(
                id = newId,
                title = newTitle,
                titleEdited = true,
                createdAt = now,
                updatedAt = now,
                // 整体派生另起一条线：自己就是新组的根
                rootId = newId,
                parentId = null,
                forkTurn = null,
            ),
            entries = entries.map { it.copy(conversationId = newId) },
        )
        Logger.i(
            LOG_TAG,
            "fork conversation sourceId=$sourceId newId=$newId entries=${entries.size} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
        return newId
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

    private fun Message.User.images(): List<Attachment> =
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
        lastMessagePreview: String,
        turnCount: Int,
    ) {
        if (entries.isEmpty()) return
        dao().appendEntriesTransaction(
            conversationId = conversationId,
            entries = entries.map { it.toEntity(conversationId) },
            leafId = leafId,
            lastMessagePreview = lastMessagePreview,
            turnCount = turnCount,
        )
    }

    suspend fun touchConversation(
        conversationId: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = dao().updateConversationUpdatedAt(conversationId, now) > 0

    suspend fun setHasUnread(
        conversationId: String,
        hasUnread: Boolean,
    ): Boolean = dao().updateHasUnread(conversationId, hasUnread) > 0

    suspend fun countEntries(conversationId: String): Int = dao().countEntries(conversationId)

    suspend fun updateLeafId(conversationId: String, leafId: String) {
        dao().updateLeafId(conversationId, leafId)
    }

    /**
     * 死字段操作（T3 遗留）：保留仅防破坏旧调用编译，业务层严禁再调用。草稿已改用 XRepo 全局管理。
     */
    @Deprecated("Dead method: draft has migrated to XRepo global storage.")
    suspend fun updateDraft(conversationId: String, draftText: String) {
        dao().updateDraft(conversationId = conversationId, draftText = draftText)
    }

    suspend fun updateConfigId(conversationId: String, configId: String) {
        dao().updateConfigId(conversationId = conversationId, configId = configId)
    }

    /** 删除会话及其整个重新生成分支组（组内所有版本一起消失）。 */
    suspend fun deleteConversation(id: String) {
        val row = dao().getConversation(id)
        val rootId = row?.rootId?.takeIf { it.isNotBlank() } ?: id
        dao().deleteBranchGroup(rootId)
    }

    /** 分支组内全部会话 id（删除前需逐个销毁运行中的 Agent）。 */
    suspend fun branchGroupIds(id: String): List<String> {
        val row = dao().getConversation(id) ?: return listOf(id)
        val rootId = row.rootId.ifBlank { id }
        return dao().listBranchGroupIds(rootId)
    }

    suspend fun renameConversation(id: String, title: String) {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return
        val conversation = dao().getConversation(id) ?: return
        val rootId = conversation.rootId.ifBlank { id }
        dao().renameBranchGroup(rootId = rootId, title = trimmedTitle)
    }

    internal suspend fun closeForTest() {
        synchronized(this) {
            database?.close()
            database = null
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    internal fun dao(): ConversationDao {
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

    internal fun ConversationEntryEntity.toConversationEntry(): ConversationEntry? {
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

    internal fun ConversationEntity.toSummary(): ConversationSummary {
        return ConversationSummary(
            id = id,
            title = title,
            titleEdited = titleEdited,
            createdAt = createdAt,
            updatedAt = updatedAt,
            lastMessagePreview = lastMessagePreview,
            turnCount = turnCount,
            hasUnread = hasUnread,
            rootId = rootId.ifBlank { id },
            parentId = parentId,
            forkTurn = forkTurn,
        )
    }
}
