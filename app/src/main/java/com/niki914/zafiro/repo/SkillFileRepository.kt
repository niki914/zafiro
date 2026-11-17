package com.niki914.zafiro.repo

import com.niki914.zafiro.api.util.tryOrDefault
import com.niki914.zafiro.api.util.tryOrNull
import com.niki914.zafiro.settings.model.RuntimeLoadedSkill
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata
import com.niki914.zafiro.settings.model.RuntimeSkillValidation
import java.io.File

sealed class SkillImportResult {
    object Success : SkillImportResult()
    object NoSkillFile : SkillImportResult()
    data class Conflict(val targetName: String) : SkillImportResult()
    data class Error(val message: String) : SkillImportResult()
}

class SkillFileRepository(
    private val skillsRoot: File,
) {
    private val resolver = SkillPathResolver(skillsRoot)
    private val parser = SkillFrontmatterParser()
    private val stateStore = SkillEnabledStateStore(File(skillsRoot, STATUS_FILE_NAME))

    private data class CachedSkillsSnapshot(
        val signature: Long,
        val skills: List<RuntimeSkillMetadata>,
    )

    @Volatile
    private var cache: CachedSkillsSnapshot? = null

    fun invalidateCache() {
        cache = null
    }

    /**
     * 计算 skills 目录树的轻量时间戳签名。
     * 仅做 stat 系统调用，不读文件内容与解析 frontmatter。
     */
    fun calculateMtimeSignature(): Long {
        var sig = tryOrDefault(0L) { skillsRoot.lastModified() }
        val statusFile = File(skillsRoot, STATUS_FILE_NAME)
        if (tryOrDefault(false) { statusFile.isFile }) {
            sig = 31 * sig + statusFile.lastModified()
        }
        val firstLevel = tryOrNull { skillsRoot.listFiles() } ?: return sig
        for (first in firstLevel) {
            if (tryOrDefault(false) { first.isDirectory }) {
                sig = 31 * sig + first.lastModified()
                val skillFile = File(first, SkillPathResolver.SKILL_FILE_NAME)
                if (tryOrDefault(false) { skillFile.isFile }) {
                    sig = 31 * sig + skillFile.lastModified()
                }
                val secondLevel = tryOrNull { first.listFiles() } ?: continue
                for (second in secondLevel) {
                    if (tryOrDefault(false) { second.isDirectory }) {
                        sig = 31 * sig + second.lastModified()
                        val nestedSkillFile = File(second, SkillPathResolver.SKILL_FILE_NAME)
                        if (tryOrDefault(false) { nestedSkillFile.isFile }) {
                            sig = 31 * sig + nestedSkillFile.lastModified()
                        }
                    }
                }
            }
        }
        return sig
    }

    fun invalidateCacheIfModified(): Boolean {
        val currentSig = calculateMtimeSignature()
        val currentCache = cache
        if (currentCache == null || currentCache.signature != currentSig) {
            cache = null
            return true
        }
        return false
    }

    fun listAll(): List<RuntimeSkillMetadata> {
        val currentSig = calculateMtimeSignature()
        val currentCache = cache
        if (currentCache != null && currentCache.signature == currentSig) {
            return currentCache.skills
        }
        val resolvedList = tryOrNull { resolver.scanSkillFiles() }.orEmpty()
        val fresh = resolvedList.mapNotNull { resolved ->
            tryOrNull { metadataFor(resolved) }
        }
        cache = CachedSkillsSnapshot(currentSig, fresh)
        return fresh
    }

    fun listEnabled(): List<RuntimeSkillMetadata> {
        return listAll().filter { it.enabled }
    }

    fun load(id: String): RuntimeLoadedSkill? = tryOrNull {
        val resolved = resolver.resolveSkillFile(id) as? SkillPathResolution.Resolved ?: return@tryOrNull null
        if (!resolved.skillFile.isFile) {
            return@tryOrNull null
        }
        val content = resolved.skillFile.readText(Charsets.UTF_8)
        val metadata = parser.parse(resolved.id, content)
        RuntimeLoadedSkill(
            id = resolved.id,
            name = metadata.name,
            description = metadata.description,
            relativePath = resolved.relativePath,
            absolutePath = resolved.skillFile.canonicalPath,
            absoluteDir = resolved.skillDir.canonicalPath,
            content = content,
            enabled = tryOrDefault(true) { stateStore.isEnabled(resolved.id) },
        )
    }

    fun saveContent(id: String, content: String): RuntimeSkillValidation? {
        val resolved = resolveExisting(id) ?: return validationForMissingOrInvalid(id)
        resolved.skillFile.writeText(content, Charsets.UTF_8)
        invalidateCache()
        return null
    }

    fun setEnabled(id: String, enabled: Boolean): RuntimeSkillValidation? {
        val resolved = resolveExisting(id) ?: return validationForMissingOrInvalid(id)
        stateStore.setEnabled(resolved.id, enabled)
        invalidateCache()
        return null
    }

    fun delete(id: String): RuntimeSkillValidation? {
        val resolved = resolveExisting(id) ?: return validationForMissingOrInvalid(id)
        if (!resolved.skillFile.delete() && resolved.skillFile.exists()) {
            return RuntimeSkillValidation("id", "Failed to delete skill.")
        }
        stateStore.remove(resolved.id)
        cleanEmptySkillDirs(resolved.skillDir)
        invalidateCache()
        return null
    }

    fun importSkill(sourceDir: File, overwrite: Boolean = false): SkillImportResult {
        val skillFile = File(sourceDir, SkillPathResolver.SKILL_FILE_NAME)
        if (!skillFile.isFile) return SkillImportResult.NoSkillFile

        val targetName = sourceDir.name
        if (targetName.isBlank() || targetName.contains("/") || targetName.contains("\\") || targetName == "." || targetName == "..") {
            return SkillImportResult.Error("Invalid directory name: $targetName")
        }

        val targetDir = File(skillsRoot, targetName)
        if (targetDir.exists() && !overwrite) {
            return SkillImportResult.Conflict(targetName)
        }
        if (targetDir.exists() && overwrite) {
            targetDir.deleteRecursively()
        }

        return try {
            val copied = sourceDir.copyRecursively(targetDir, overwrite = true)
            if (!copied || !File(targetDir, SkillPathResolver.SKILL_FILE_NAME).isFile) {
                targetDir.deleteRecursively()
                SkillImportResult.Error("Copy failed: unable to read source directory")
            } else {
                invalidateCache()
                SkillImportResult.Success
            }
        } catch (e: Exception) {
            if (targetDir.exists()) targetDir.deleteRecursively()
            SkillImportResult.Error(e.message ?: "Copy failed")
        }
    }

    private fun metadataFor(resolved: SkillPathResolution.Resolved): RuntimeSkillMetadata {
        val content = resolved.skillFile.readText(Charsets.UTF_8)
        val metadata = parser.parse(resolved.id, content)
        return RuntimeSkillMetadata(
            id = resolved.id,
            name = metadata.name,
            description = metadata.description,
            relativePath = resolved.relativePath,
            absolutePath = resolved.skillFile.canonicalPath,
            absoluteDir = resolved.skillDir.canonicalPath,
            enabled = tryOrDefault(true) { stateStore.isEnabled(resolved.id) },
        )
    }

    private fun resolveExisting(id: String): SkillPathResolution.Resolved? {
        val resolved = resolver.resolveSkillFile(id) as? SkillPathResolution.Resolved ?: return null
        return resolved.takeIf { it.skillFile.isFile }
    }

    private fun validationForMissingOrInvalid(id: String): RuntimeSkillValidation {
        val resolution = resolver.resolveSkillFile(id)
        return when (resolution) {
            is SkillPathResolution.Invalid -> resolution.validation
            is SkillPathResolution.Resolved -> RuntimeSkillValidation("id", "Skill not found.")
        }
    }

    private fun cleanEmptySkillDirs(startDir: File) {
        val root = skillsRoot.canonicalFile
        var current: File? = startDir.canonicalFile
        while (current != null && current.path != root.path && isUnderRoot(current, root)) {
            val files = current.listFiles()
            if (files == null || files.isNotEmpty()) {
                return
            }
            if (!current.delete()) {
                return
            }
            current = current.parentFile?.canonicalFile
        }
    }

    private fun isUnderRoot(file: File, root: File): Boolean {
        return file.path.startsWith(root.path + File.separator)
    }

    private companion object {
        const val STATUS_FILE_NAME = "status.json"
    }
}
