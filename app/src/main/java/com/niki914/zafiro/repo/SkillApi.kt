package com.niki914.zafiro.repo

import com.niki914.zafiro.settings.model.RuntimeLoadedSkill
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata
import com.niki914.zafiro.settings.model.RuntimeSkillValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class SkillApi internal constructor(
    private val repo: XRepo,
) {
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val repoMutex = Mutex()
    private var repositoryInstance: SkillFileRepository? = null
    private var fileObserver: SkillsFileObserver? = null

    private fun bumpRevision() {
        _revision.value += 1L
    }

    internal fun resetForTest() {
        _revision.value = 0L
        fileObserver?.stopWatching()
        fileObserver = null
        repositoryInstance?.invalidateCache()
        repositoryInstance = null
    }

    suspend fun listAll(): List<RuntimeSkillMetadata> = withContext(Dispatchers.IO) {
        repository().listAll()
    }

    suspend fun listEnabled(): List<RuntimeSkillMetadata> = withContext(Dispatchers.IO) {
        repository().listEnabled()
    }

    suspend fun getDetail(id: String): RuntimeLoadedSkill? = withContext(Dispatchers.IO) {
        repository().load(id)
    }

    suspend fun saveContent(id: String, content: String): RuntimeSkillValidation? = withContext(Dispatchers.IO) {
        val result = repository().saveContent(id, content)
        if (result == null) {
            bumpRevision()
        }
        result
    }

    suspend fun setEnabled(id: String, enabled: Boolean): RuntimeSkillValidation? = withContext(Dispatchers.IO) {
        val result = repository().setEnabled(id, enabled)
        if (result == null) {
            bumpRevision()
        }
        result
    }

    suspend fun delete(id: String): RuntimeSkillValidation? = withContext(Dispatchers.IO) {
        val result = repository().delete(id)
        if (result == null) {
            bumpRevision()
        }
        result
    }

    suspend fun importSkill(sourceDir: File, overwrite: Boolean = false): SkillImportResult = withContext(Dispatchers.IO) {
        val result = repository().importSkill(sourceDir, overwrite)
        if (result is SkillImportResult.Success) {
            bumpRevision()
        }
        result
    }

    /**
     * Seeds default skills bundled in assets into the skills directory.
     *
     * Copies a skill when its target directory does not exist. When the target
     * already exists, the skill is updated only if the user has not modified
     * it: a `.seed` sidecar records the sha256 of the last seeded SKILL.md, and
     * the current file is overwritten only while its hash still matches.
     * A missing `.seed` marks a pre-sidecar install and is updated once.
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
                val assetDir = "$DEFAULT_SKILLS_ASSET_PATH/$skillId"
                val files = try {
                    context.assets.list(assetDir)?.toList().orEmpty()
                } catch (_: IOException) {
                    emptyList()
                }
                val openAsset: (String) -> InputStream? = { name ->
                    runCatching { context.assets.open("$assetDir/$name") }.getOrNull()
                }
                reseedSkill(targetDir, files, openAsset)
            }
            if (assetEntries.isNotEmpty()) {
                bumpRevision()
            }
        }
    }

    private suspend fun repository(): SkillFileRepository {
        repositoryInstance?.let { return it }
        return repoMutex.withLock {
            repositoryInstance?.let { return@withLock it }
            val context = repo.context()
            val skillsDir = File(context.filesDir, SKILLS_DIR_NAME)
            if (!skillsDir.exists()) {
                skillsDir.mkdirs()
            }
            val repository = SkillFileRepository(skillsDir)
            if (fileObserver == null) {
                val observer = SkillsFileObserver(
                    skillsRoot = skillsDir,
                    onChanged = {
                        if (repositoryInstance?.invalidateCacheIfModified() == true) {
                            bumpRevision()
                        }
                    }
                )
                observer.startWatching()
                fileObserver = observer
            }
            repositoryInstance = repository
            repository
        }
    }

    companion object {
        const val SKILLS_DIR_NAME = "skills"
        private const val DEFAULT_SKILLS_ASSET_PATH = "skills"
        private const val SEED_MARKER_FILE_NAME = ".seed"
        private const val SKILL_FILE_NAME = "SKILL.md"

        /**
         * Seeds or updates one default skill in [targetDir].
         *
         * Copies the skill when its target directory does not exist. When the target
         * already exists, the skill is updated only if the user has not modified
         * it: a `.seed` sidecar records the sha256 of the last seeded SKILL.md, and
         * the current file is overwritten only while its hash still matches.
         * A missing `.seed` marks a pre-sidecar install and is updated once.
         */
        internal fun reseedSkill(
            targetDir: File,
            assetFiles: List<String>,
            openAsset: (String) -> InputStream?,
        ) {
            val seedFile = File(targetDir, SEED_MARKER_FILE_NAME)
            val skillFile = File(targetDir, SKILL_FILE_NAME)

            if (targetDir.isDirectory) {
                // Update only an unmodified install: current content must still
                // match the last seeded hash (a missing marker counts as unmodified).
                val marker = seedFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()
                val currentHash = hashFile(skillFile)
                val unmodified = marker == null || currentHash != null && marker == currentHash
                if (!unmodified) return
            } else {
                targetDir.mkdirs()
            }

            try {
                for (fileName in assetFiles) {
                    val input = openAsset(fileName) ?: continue
                    input.use {
                        File(targetDir, fileName).outputStream().use { output ->
                            it.copyTo(output)
                        }
                    }
                }
                hashFile(skillFile)?.let { seedFile.writeText(it) }
            } catch (_: IOException) {
                // ponytail: partial copy stays — next seed run re-copies unmodified installs
            }
        }

        private fun hashFile(file: File): String? {
            return runCatching {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }
    }
}
