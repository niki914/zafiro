package com.niki914.nexus.agentic.repo

import com.niki914.nexus.agentic.runtime.settings.model.RuntimeSkillValidation
import java.io.File
import java.nio.file.Files

sealed class SkillPathResolution {
    data class Resolved(
        val id: String,
        val relativePath: String,
        val skillFile: File,
        val skillDir: File,
    ) : SkillPathResolution()

    data class Invalid(
        val validation: RuntimeSkillValidation,
    ) : SkillPathResolution()
}

class SkillPathResolver(
    private val skillsRoot: File,
) {
    fun validateId(id: String): RuntimeSkillValidation? {
        val normalized = id.trim()
        if (normalized.isBlank()) {
            return invalidId()
        }
        if (normalized.startsWith("/") || File(normalized).isAbsolute) {
            return invalidId()
        }
        if (normalized.contains('\\')) {
            return invalidId()
        }

        val segments = normalized.split('/')
        if (segments.size !in 1..2) {
            return invalidId()
        }
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            return invalidId()
        }
        if (segments.first() == STATUS_FILE_NAME) {
            return invalidId()
        }
        return null
    }

    fun resolveSkillFile(id: String): SkillPathResolution {
        val normalized = id.trim()
        validateId(normalized)?.let { return SkillPathResolution.Invalid(it) }

        val skillDir = normalized.split('/').fold(skillsRoot) { parent, segment ->
            File(parent, segment)
        }
        val skillFile = File(skillDir, SKILL_FILE_NAME)
        if (!isUnderSkillsRoot(skillFile)) {
            return SkillPathResolution.Invalid(
                RuntimeSkillValidation(
                    "id",
                    "Skill path escapes skills root."
                )
            )
        }
        if (containsSymbolicLink(skillFile)) {
            return SkillPathResolution.Invalid(
                RuntimeSkillValidation(
                    "id",
                    "Skill path uses symbolic link."
                )
            )
        }

        return SkillPathResolution.Resolved(
            id = normalized,
            relativePath = "$normalized/$SKILL_FILE_NAME",
            skillFile = skillFile,
            skillDir = skillDir,
        )
    }

    fun scanSkillFiles(): List<SkillPathResolution.Resolved> {
        val firstLevelDirs = skillsRoot.listFiles()
            ?.filter { it.isDirectory }
            .orEmpty()

        val candidates = buildList {
            firstLevelDirs.forEach { first ->
                val oneLevelSkill = File(first, SKILL_FILE_NAME)
                if (oneLevelSkill.isFile) {
                    add(first.name)
                }

                first.listFiles()
                    ?.filter { it.isDirectory }
                    .orEmpty()
                    .forEach { second ->
                        val twoLevelSkill = File(second, SKILL_FILE_NAME)
                        if (twoLevelSkill.isFile) {
                            add("${first.name}/${second.name}")
                        }
                    }
            }
        }

        return candidates
            .mapNotNull { id -> resolveSkillFile(id) as? SkillPathResolution.Resolved }
            .sortedBy { it.id }
    }

    private fun isUnderSkillsRoot(file: File): Boolean {
        val root = skillsRoot.canonicalFile
        val target = file.canonicalFile
        return target.path == root.path || target.path.startsWith(root.path + File.separator)
    }

    private fun containsSymbolicLink(file: File): Boolean {
        val root = skillsRoot.canonicalFile
        var current: File? = file.absoluteFile
        while (current != null && current.canonicalPath != root.path) {
            if (Files.isSymbolicLink(current.toPath())) {
                return true
            }
            val parent = current.parentFile ?: return false
            if (!isUnderSkillsRoot(parent) && parent.canonicalPath != root.path) {
                return false
            }
            current = parent
        }
        return false
    }

    private fun invalidId(): RuntimeSkillValidation {
        return RuntimeSkillValidation("id", "Invalid skill id.")
    }

    companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        private const val STATUS_FILE_NAME = "status.json"
    }
}
