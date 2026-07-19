package com.niki914.nexus.agentic.repo

import com.niki914.nexus.agentic.runtime.settings.model.RuntimeLoadedSkill
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeSkillMetadata
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeSkillValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class SkillApi internal constructor(
    private val repo: XRepo,
) {
    suspend fun listAll(): List<RuntimeSkillMetadata> {
        return repository().listAll()
    }

    suspend fun listEnabled(): List<RuntimeSkillMetadata> {
        return repository().listEnabled()
    }

    suspend fun getDetail(id: String): RuntimeLoadedSkill? {
        return repository().load(id)
    }

    suspend fun saveContent(id: String, content: String): RuntimeSkillValidation? {
        return repository().saveContent(id, content)
    }

    suspend fun setEnabled(id: String, enabled: Boolean): RuntimeSkillValidation? {
        return repository().setEnabled(id, enabled)
    }

    suspend fun delete(id: String): RuntimeSkillValidation? {
        return repository().delete(id)
    }

    suspend fun importSkill(sourceDir: File, overwrite: Boolean = false): SkillImportResult {
        return withContext(Dispatchers.IO) {
            repository().importSkill(sourceDir, overwrite)
        }
    }

    /**
     * Seeds default skills bundled in assets into the skills directory.
     *
     * Only copies skill directories that don't already exist in the target
     * skills root — user modifications are never overwritten.
     */
    suspend fun seedDefaults() {
        withContext(Dispatchers.IO) {
            val context = repo.context()
            val skillsTargetDir = File(context.filesDir, SKILLS_DIR_NAME)
            val assetEntries = try {
                context.assets.list(DEFAULT_SKILLS_ASSET_PATH)?.toList().orEmpty()
            } catch (_: IOException) {
                emptyList()
            }
            for (skillId in assetEntries) {
                val targetDir = File(skillsTargetDir, skillId)
                if (targetDir.exists()) continue
                val assetDir = "$DEFAULT_SKILLS_ASSET_PATH/$skillId"
                val files = try {
                    context.assets.list(assetDir)?.toList().orEmpty()
                } catch (_: IOException) {
                    emptyList()
                }
                targetDir.mkdirs()
                try {
                    for (fileName in files) {
                        context.assets.open("$assetDir/$fileName").use { input ->
                            File(targetDir, fileName).outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                } catch (_: IOException) {
                    targetDir.deleteRecursively()
                }
            }
        }
    }

    private suspend fun repository(): SkillFileRepository {
        val context = repo.context()
        return SkillFileRepository(File(context.filesDir, SKILLS_DIR_NAME))
    }

    private companion object {
        const val SKILLS_DIR_NAME = "skills"
        private const val DEFAULT_SKILLS_ASSET_PATH = "skills"
    }
}
