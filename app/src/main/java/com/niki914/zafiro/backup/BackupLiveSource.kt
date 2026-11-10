package com.niki914.zafiro.backup

import android.content.Context
import com.niki914.store.StoreDescriptorRegistry
import com.niki914.zafiro.api.util.tryOrNull
import com.niki914.zafiro.app.conversation.ConversationEntity
import com.niki914.zafiro.app.conversation.ConversationEntryEntity
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.repo.SkillApi
import com.niki914.zafiro.repo.SkillPathResolver
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 从现网存储读出导出用的快照。规则类分区保持原文，显式空标记不被重新编码。
 * 文本动作与智能体注册表不在六组里，这里不读。
 */
internal object BackupLiveSource {

    suspend fun read(): BackupSnapshot = withContext(Dispatchers.IO) {
        val context = XRepo.context()
        val table = ConversationRepo.readConsistentForBackup()
        val skillsRoot = File(context.filesDir, SkillApi.SKILLS_DIR_NAME)
        val imageRoot = File(context.filesDir, IMAGE_CACHE_DIR)
        BackupSnapshot(
            appStateJson = XRepo.readJson(StoreDescriptorRegistry.APP_STATE_ID),
            modelsJson = XRepo.readJson(StoreDescriptorRegistry.LLM_CONFIGS_ID),
            memoryJson = XRepo.readJson(StoreDescriptorRegistry.AGENT_MAIN_MEMORY_ID),
            mcpServersJson = XRepo.readJson(StoreDescriptorRegistry.TOOLS_MCP_SERVERS_ID),
            mcpServiceJson = XRepo.readJson(StoreDescriptorRegistry.TOOLS_MCP_SERVICE_ID),
            builtinToolsJson = XRepo.readJson(StoreDescriptorRegistry.TOOLS_BUILTIN_ID),
            pyToolsJson = XRepo.readJson(StoreDescriptorRegistry.TOOLS_PY_ID),
            executionRulesJson = XRepo.readJson(StoreDescriptorRegistry.RULES_EXECUTION_ID),
            takeoverRulesJson = XRepo.readJson(StoreDescriptorRegistry.RULES_TAKEOVER_ID),
            maskingRulesJson = XRepo.readJson(StoreDescriptorRegistry.RULES_MASKING_ID),
            skillsStatusJson = readStatus(skillsRoot),
            skills = readSkills(context, skillsRoot),
            conversations = table.conversations.map { it.toBackup() },
            entries = table.entries.map { it.toBackup() },
            readImage = { path -> readCachedImage(imageRoot, path) },
            pyInstallApkDir = File(context.filesDir, "downloads/py_install_apk").absolutePath,
            pyDownloadFileDir = File(context.filesDir, "downloads/py_download_file").absolutePath,
        )
    }

    private fun readStatus(skillsRoot: File): String {
        val root = tryOrNull { skillsRoot.canonicalFile } ?: return EMPTY_STATUS
        val file = tryOrNull { File(root, STATUS_FILE).canonicalFile } ?: return EMPTY_STATUS
        if (file.parentFile?.path != root.path || !file.isFile) return EMPTY_STATUS
        return tryOrNull { file.readText(Charsets.UTF_8) } ?: EMPTY_STATUS
    }

    private fun readSkills(context: Context, skillsRoot: File): List<SkillSnapshot> {
        return SkillPathResolver(skillsRoot).scanSkillFiles().map { resolved ->
            SkillSnapshot(
                id = resolved.id,
                files = readSkillFiles(resolved.skillDir),
                bundledSkillMd = bundledSkill(context, resolved.id),
            )
        }
    }

    private fun bundledSkill(context: Context, id: String): ByteArray? {
        if (id.contains('/')) return null
        return tryOrNull {
            context.assets.open("$SKILL_ASSET_DIR/$id/SKILL.md").use { it.readBytes() }
        }
    }

    private fun readSkillFiles(dir: File): Map<String, ByteArray> {
        val root = tryOrNull { dir.canonicalFile } ?: return emptyMap()
        val out = linkedMapOf<String, ByteArray>()
        val files = tryOrNull {
            root.walkTopDown()
                .onEnter { entered -> staysInside(root, entered) }
                .filter { it.isFile }
                .toList()
        } ?: return emptyMap()
        files.forEach { file ->
            val canonical = tryOrNull { file.canonicalFile } ?: return@forEach
            if (!staysInside(root, canonical)) return@forEach
            val relative = canonical.relativeTo(root).invariantSeparatorsPath
            if (!safeRelative(relative)) return@forEach
            val bytes = tryOrNull { canonical.readBytes() } ?: return@forEach
            out[relative] = bytes
        }
        return out
    }

    private fun readCachedImage(imageRoot: File, path: String): ByteArray? {
        val root = tryOrNull { imageRoot.canonicalFile } ?: return null
        val file = tryOrNull { File(path).canonicalFile } ?: return null
        if (!file.isFile || !staysInside(root, file)) return null
        return tryOrNull { file.readBytes() }
    }

    private fun staysInside(root: File, file: File): Boolean {
        val canonical = tryOrNull { file.canonicalFile } ?: return false
        return canonical.path == root.path || canonical.path.startsWith(root.path + File.separator)
    }
}

private const val IMAGE_CACHE_DIR = "image_cache"
private const val SKILL_ASSET_DIR = "skills"
private const val STATUS_FILE = "status.json"
private const val EMPTY_STATUS = """{"enabled":{}}"""

private fun ConversationEntity.toBackup(): ConversationBackup {
    return ConversationBackup(
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
        rootId = rootId,
        parentId = parentId,
        forkTurn = forkTurn,
    )
}

private fun ConversationEntryEntity.toBackup(): EntryBackup {
    return EntryBackup(
        conversationId = conversationId,
        id = id,
        parentId = parentId,
        timestamp = timestamp,
        messageJson = messageJson,
    )
}
