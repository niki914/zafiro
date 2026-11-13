package com.niki914.zafiro.backup

import android.content.Context
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.api.util.tryOrNull
import com.niki914.zafiro.app.conversation.ConversationEntity
import com.niki914.zafiro.app.conversation.ConversationEntryEntity
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.repo.SkillApi
import com.niki914.zafiro.repo.XRepo
import java.io.File
import java.nio.file.Files

/**
 * 导入要写的存储。测试用内存实现，真机用 [LiveBackupStore]。
 * 读数和导出共用 [BackupSnapshot]，这样快照和应用前看到的是同一份数据。
 */
internal interface BackupStore {
    suspend fun read(): BackupSnapshot
    fun imageDir(): File
    fun scratchDir(): File
    suspend fun writeDocument(partition: BackupPartition, json: String)
    suspend fun writeConversations(
        conversations: List<ConversationBackup>,
        entries: List<EntryBackup>,
        deleteIds: List<String>,
        appStateJson: String,
        imageFiles: Map<String, ByteArray>,
    )
    suspend fun writeSkills(
        statusJson: String,
        skills: List<SkillSnapshot>,
        deleteIds: List<String>,
    )
}

internal fun BackupPartition.storeId(): String? {
    return when (this) {
        BackupPartition.Memory -> StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID
        BackupPartition.Models -> StoreDescriptorRegistry.LLM_CONFIGS_ID
        BackupPartition.Preferences -> StoreDescriptorRegistry.APP_STATE_ID
        BackupPartition.McpServers -> StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID
        BackupPartition.McpService -> StoreDescriptorRegistry.TOOLS_MCP_SERVICE_ID
        BackupPartition.BuiltinTools -> StoreDescriptorRegistry.TOOLS_BUILTIN_ID
        BackupPartition.PyTools -> StoreDescriptorRegistry.TOOLS_PY_ID
        BackupPartition.TextActions -> StoreDescriptorRegistry.TOOLS_TEXT_ACTIONS_ID
        BackupPartition.ExecutionRules -> StoreDescriptorRegistry.RULES_EXECUTION_ID
        BackupPartition.TakeoverRules -> StoreDescriptorRegistry.RULES_TAKEOVER_ID
        BackupPartition.MaskingRules -> StoreDescriptorRegistry.RULES_MASKING_ID
        BackupPartition.Conversations, BackupPartition.Skills -> null
    }
}

/** 同内容的图片缓存已经在就跳过。文件名沿用图片管线的前 8 位哈希。 */
internal fun writeImageIfAbsent(imageDir: File, path: String, bytes: ByteArray): Boolean {
    val root = imageDir.canonicalFile
    root.mkdirs()
    val file = File(path).canonicalFile
    if (!staysInside(root, file)) error("image path escapes cache")
    if (file.isFile) return false
    file.parentFile?.mkdirs()
    file.writeBytes(bytes)
    return true
}

/** 把技能目录和 status.json 写回 files/skills。每个要写入的技能先清空目录再落盘，.seed 原样保留。 */
internal fun writeSkillTree(
    skillsRoot: File,
    statusJson: String,
    skills: List<SkillSnapshot>,
    deleteIds: List<String>,
) {
    val root = skillsRoot.canonicalFile
    root.mkdirs()
    deleteIds.forEach { id -> deleteSkillDir(root, id) }
    skills.forEach { skill ->
        if (!safeSkillId(skill.id)) return@forEach
        deleteSkillDir(root, skill.id)
        skill.files.forEach { (relative, bytes) ->
            if (!safeRelative(relative)) return@forEach
            val target = File(root, "${skill.id}/$relative").canonicalFile
            if (!staysInside(root, target)) return@forEach
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
        }
    }
    val statusFile = tryOrNull { File(root, "status.json").canonicalFile } ?: error("status")
    if (statusFile.parentFile?.path != root.path) error("status escapes skills root")
    statusFile.writeText(statusJson, Charsets.UTF_8)
}

internal class LiveBackupStore(
    private val context: Context,
) : BackupStore {
    override suspend fun read(): BackupSnapshot = BackupLiveSource.read()

    override fun imageDir(): File = File(context.filesDir, IMAGE_CACHE_DIR)

    override fun scratchDir(): File = context.cacheDir

    override suspend fun writeDocument(partition: BackupPartition, json: String) {
        val storeId = partition.storeId() ?: error(partition.id)
        XRepo.writeJson(storeId, json)
    }

    override suspend fun writeConversations(
        conversations: List<ConversationBackup>,
        entries: List<EntryBackup>,
        deleteIds: List<String>,
        appStateJson: String,
        imageFiles: Map<String, ByteArray>,
    ) {
        imageFiles.forEach { (path, bytes) -> writeImageIfAbsent(imageDir(), path, bytes) }
        ConversationRepo.applyBackup(
            deleteIds = deleteIds,
            conversations = conversations.map { it.toEntity() },
            entries = entries.map { it.toEntity() },
        )
        XRepo.writeJson(StoreDescriptorRegistry.APP_STATE_ID, appStateJson)
    }

    override suspend fun writeSkills(
        statusJson: String,
        skills: List<SkillSnapshot>,
        deleteIds: List<String>,
    ) {
        val root = File(context.filesDir, SkillApi.SKILLS_DIR_NAME)
        writeSkillTree(root, statusJson, skills, deleteIds)
    }
}

/** 偏好里的响应式开关和主题内存快照，走现成的冷启动回填。 */
internal suspend fun syncPreferenceRuntime() {
    XRepo.hydrateSettings()
    ThemeController.load()
}

private fun ConversationBackup.toEntity(): ConversationEntity {
    return ConversationEntity(
        id = id,
        title = title,
        titleEdited = titleEdited,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessagePreview = lastMessagePreview,
        turnCount = turnCount,
        leafId = leafId,
        configId = configId,
        hasUnread = hasUnread,
        rootId = rootId.ifBlank { id },
        parentId = parentId,
        forkTurn = forkTurn,
    )
}

private fun EntryBackup.toEntity(): ConversationEntryEntity {
    return ConversationEntryEntity(
        conversationId = conversationId,
        id = id,
        parentId = parentId,
        timestamp = timestamp,
        messageJson = messageJson,
    )
}

private fun deleteSkillDir(root: File, id: String) {
    if (!safeSkillId(id)) return
    val dir = File(root, id)
    if (Files.isSymbolicLink(dir.toPath())) {
        dir.delete()
        return
    }
    val canonical = tryOrNull { dir.canonicalFile } ?: return
    if (!staysInside(root, canonical) || canonical.path == root.path) return
    if (canonical.isDirectory) canonical.deleteRecursively()
}

private fun staysInside(root: File, file: File): Boolean {
    val canonical = tryOrNull { file.canonicalFile } ?: return false
    return canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)
}

private const val IMAGE_CACHE_DIR = "image_cache"
