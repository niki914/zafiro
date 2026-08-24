package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import com.niki914.logging.Logger
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.SkillImportResult
import com.niki914.zafiro.repo.XRepo
import com.niki914.uikit.base.ComposeMVIViewModel
import kotlinx.coroutines.CancellationException
import java.io.File
import com.niki914.zafiro.settings.model.RuntimeLoadedSkill as LoadedSkill
import com.niki914.zafiro.settings.model.RuntimeSkillMetadata as SkillMetadata
import com.niki914.zafiro.settings.model.RuntimeSkillValidation as SkillValidation

interface SkillRepositoryProvider {
    suspend fun listAll(): List<SkillMetadata>
    suspend fun getDetail(id: String): LoadedSkill?
    suspend fun saveContent(id: String, content: String): SkillValidation?
    suspend fun setEnabled(id: String, enabled: Boolean): SkillValidation?
    suspend fun delete(id: String): SkillValidation?
    suspend fun importSkill(sourceDir: File, overwrite: Boolean = false): SkillImportResult
}

class XRepoSkillRepositoryProvider : SkillRepositoryProvider {
    override suspend fun listAll(): List<SkillMetadata> = XRepo.skills.listAll()

    override suspend fun getDetail(id: String): LoadedSkill? = XRepo.skills.getDetail(id)

    override suspend fun saveContent(id: String, content: String): SkillValidation? {
        return XRepo.skills.saveContent(id, content)
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): SkillValidation? {
        return XRepo.skills.setEnabled(id, enabled)
    }

    override suspend fun delete(id: String): SkillValidation? = XRepo.skills.delete(id)

    override suspend fun importSkill(sourceDir: File, overwrite: Boolean): SkillImportResult =
        XRepo.skills.importSkill(sourceDir, overwrite)
}

data class SkillListItem(
    val id: String,
    val title: String,
    val summary: String,
    val enabled: Boolean,
)

data class SkillDetailFormState(
    val skillId: String = "",
    val title: String = "",
    val content: String = "",
    val initialContent: String = "",
)

val SkillDetailFormState.hasUnsavedChanges: Boolean
    get() = content != initialContent

data class SkillDeleteConfirmationState(
    val skillId: String,
    val title: String,
)

data class ImportConflictState(
    val sourceDir: File,
    val skillName: String,
)

data class SkillSettingsUiState(
    val items: List<SkillListItem> = emptyList(),
    val formState: SkillDetailFormState = SkillDetailFormState(),
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val isImporting: Boolean = false,
    val inlineError: SkillInlineError? = null,
    val importConflict: ImportConflictState? = null,
    val deleteConfirmation: SkillDeleteConfirmationState? = null,
)

sealed interface SkillInlineError {
    data class LoadFailed(val message: String?, @StringRes val fallbackResId: Int) :
        SkillInlineError

    data class SaveFailed(val message: String?, @StringRes val fallbackResId: Int) :
        SkillInlineError

    data class DeleteFailed(val message: String?, @StringRes val fallbackResId: Int) :
        SkillInlineError
}

sealed interface SkillSettingsIntent {
    data object Load : SkillSettingsIntent
    data class ToggleEnabled(val id: String, val enabled: Boolean) : SkillSettingsIntent
    data class LoadDetail(val id: String, val fallbackTitle: String) : SkillSettingsIntent
    data class ContentChanged(val value: String) : SkillSettingsIntent
    data object Save : SkillSettingsIntent
    data object RequestDelete : SkillSettingsIntent
    data object DismissDeleteConfirmation : SkillSettingsIntent
    data object ConfirmDelete : SkillSettingsIntent
    data class Import(val sourceDir: File) : SkillSettingsIntent
    data object ConfirmImport : SkillSettingsIntent
    data object DismissImportConflict : SkillSettingsIntent
}

sealed interface SkillSettingsEffect {
    data object ExitDetail : SkillSettingsEffect
    data object ShowNoSkillFileToast : SkillSettingsEffect
    data class ShowImportErrorToast(val message: String?, @StringRes val fallbackResId: Int) :
        SkillSettingsEffect
}

class SkillSettingsViewModel(
    private val provider: SkillRepositoryProvider = XRepoSkillRepositoryProvider(),
) : ComposeMVIViewModel<
        SkillSettingsIntent,
        SkillSettingsUiState,
        SkillSettingsEffect,
        >() {

    override fun initUiState(): SkillSettingsUiState = SkillSettingsUiState()

    private companion object {
        private const val LOG_TAG = "niki914_nexus_SkillSettingsViewModel"
    }

    override suspend fun handleIntent(intent: SkillSettingsIntent) {
        when (intent) {
            SkillSettingsIntent.Load -> load()
            is SkillSettingsIntent.ToggleEnabled -> toggleEnabled(
                id = intent.id,
                enabled = intent.enabled,
            )

            is SkillSettingsIntent.LoadDetail -> loadDetail(
                id = intent.id,
                fallbackTitle = intent.fallbackTitle,
            )

            is SkillSettingsIntent.ContentChanged -> contentChanged(intent.value)
            SkillSettingsIntent.Save -> save()
            SkillSettingsIntent.RequestDelete -> requestDelete()
            SkillSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteConfirmation = null)
            }

            SkillSettingsIntent.ConfirmDelete -> confirmDelete()
            is SkillSettingsIntent.Import -> importSkill(intent.sourceDir)
            SkillSettingsIntent.ConfirmImport -> confirmImport()
            SkillSettingsIntent.DismissImportConflict -> updateState {
                copy(importConflict = null)
            }
        }
    }

    private suspend fun load() {
        updateState {
            copy(
                isLoading = true,
                inlineError = null,
            )
        }
        try {
            val loadedItems = provider.listAll().map { it.toListItem() }
            Logger.d(LOG_TAG, "load skills=${loadedItems.size}")
            updateState {
                copy(
                    items = loadedItems,
                    isLoading = false,
                    inlineError = null,
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "load failed reason=${throwable.message}")
            updateState {
                copy(
                    isLoading = false,
                    inlineError = SkillInlineError.LoadFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_skill_load_failed,
                    ),
                )
            }
        }
    }

    private suspend fun toggleEnabled(id: String, enabled: Boolean) {
        val previousItems = currentState.items
        val targetItem = previousItems.firstOrNull { it.id == id } ?: return
        val updatedItems = previousItems.map { item ->
            if (item.id == id) {
                item.copy(enabled = enabled)
            } else {
                item
            }
        }
        updateState {
            copy(
                items = updatedItems,
                isSaving = true,
                inlineError = null,
            )
        }
        try {
            val validation = provider.setEnabled(targetItem.id, enabled)
            if (validation != null) {
                Logger.w(
                    LOG_TAG,
                    "toggleEnabled rejected skillId=${targetItem.id} " +
                        "reason=${validationMessage(validation)}"
                )
                updateState {
                    copy(
                        items = previousItems,
                        isSaving = false,
                        inlineError = SkillInlineError.SaveFailed(
                            validationMessage(validation),
                            fallbackResId = R.string.error_skill_save_failed,
                        ),
                    )
                }
                return
            }
            Logger.i(LOG_TAG, "toggleEnabled succeeded skillId=${targetItem.id} enabled=$enabled")
            updateState {
                copy(
                    isSaving = false,
                    inlineError = null,
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(
                LOG_TAG,
                "toggleEnabled failed skillId=${targetItem.id} reason=${throwable.message}"
            )
            updateState {
                copy(
                    items = previousItems,
                    isSaving = false,
                    inlineError = SkillInlineError.SaveFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_skill_save_failed,
                    ),
                )
            }
        }
    }

    private suspend fun loadDetail(id: String, fallbackTitle: String) {
        updateState {
            copy(
                isLoading = true,
                inlineError = null,
            )
        }
        val startedAtMs = System.currentTimeMillis()
        try {
            val loadedSkill = provider.getDetail(id)
            if (loadedSkill == null) {
                Logger.w(LOG_TAG, "loadDetail notFound skillId=$id")
                updateState {
                    copy(
                        isLoading = false,
                        inlineError = SkillInlineError.LoadFailed(
                            "Skill not found.",
                            fallbackResId = R.string.error_skill_read_failed
                        ),
                    )
                }
                return
            }
            Logger.d(
                LOG_TAG,
                "loadDetail succeeded skillId=$id contentLength=${loadedSkill.content.length} " +
                    "elapsedMs=${System.currentTimeMillis() - startedAtMs}"
            )
            val title = loadedSkill.name.ifBlank { fallbackTitle.ifBlank { loadedSkill.id } }
            updateState {
                copy(
                    formState = SkillDetailFormState(
                        skillId = loadedSkill.id,
                        title = title,
                        content = loadedSkill.content,
                        initialContent = loadedSkill.content,
                    ),
                    isLoading = false,
                    inlineError = null,
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "loadDetail failed skillId=$id reason=${throwable.message}")
            updateState {
                copy(
                    isLoading = false,
                    inlineError = SkillInlineError.LoadFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_skill_read_failed,
                    ),
                )
            }
        }
    }

    private fun contentChanged(value: String) {
        updateState {
            copy(
                formState = formState.copy(content = value),
                inlineError = null,
            )
        }
    }

    private suspend fun save() {
        val formState = currentState.formState
        updateState {
            copy(
                isSaving = true,
                inlineError = null,
            )
        }
        try {
            val validation = provider.saveContent(formState.skillId, formState.content)
            if (validation != null) {
                Logger.w(
                    LOG_TAG,
                    "save rejected skillId=${formState.skillId} " +
                        "reason=${validationMessage(validation)}"
                )
                updateState {
                    copy(
                        isSaving = false,
                        inlineError = SkillInlineError.SaveFailed(
                            validationMessage(validation),
                            fallbackResId = R.string.error_skill_save_failed,
                        ),
                    )
                }
                return
            }
            Logger.i(
                LOG_TAG,
                "save succeeded skillId=${formState.skillId} " +
                    "contentLength=${formState.content.length}"
            )
            updateState {
                copy(
                    formState = formState.copy(initialContent = formState.content),
                    isSaving = false,
                    inlineError = null,
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(
                LOG_TAG,
                "save failed skillId=${formState.skillId} reason=${throwable.message}"
            )
            updateState {
                copy(
                    isSaving = false,
                    inlineError = SkillInlineError.SaveFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_skill_save_failed,
                    ),
                )
            }
        }
    }

    private fun requestDelete() {
        val formState = currentState.formState
        if (formState.skillId.isBlank()) return
        updateState {
            copy(
                deleteConfirmation = SkillDeleteConfirmationState(
                    skillId = formState.skillId,
                    title = formState.title.ifBlank { formState.skillId },
                ),
                inlineError = null,
            )
        }
    }

    private suspend fun confirmDelete() {
        val confirmation = currentState.deleteConfirmation ?: return
        updateState {
            copy(
                deleteConfirmation = null,
                isSaving = true,
                inlineError = null,
            )
        }
        try {
            val validation = provider.delete(confirmation.skillId)
            if (validation != null) {
                Logger.w(
                    LOG_TAG,
                    "confirmDelete rejected skillId=${confirmation.skillId} " +
                        "reason=${validationMessage(validation)}"
                )
                updateState {
                    copy(
                        isSaving = false,
                        inlineError = SkillInlineError.DeleteFailed(
                            validationMessage(validation),
                            fallbackResId = R.string.error_skill_delete_failed,
                        ),
                    )
                }
                return
            }
            Logger.i(LOG_TAG, "confirmDelete succeeded skillId=${confirmation.skillId}")
            updateState {
                copy(
                    isSaving = false,
                    inlineError = null,
                )
            }
            sendEffect(SkillSettingsEffect.ExitDetail)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(
                LOG_TAG,
                "confirmDelete failed skillId=${confirmation.skillId} reason=${throwable.message}"
            )
            updateState {
                copy(
                    isSaving = false,
                    inlineError = SkillInlineError.DeleteFailed(
                        message = throwable.message,
                        fallbackResId = R.string.error_skill_delete_failed,
                    ),
                )
            }
        }
    }

    private suspend fun importSkill(sourceDir: File) {
        updateState { copy(isImporting = true, inlineError = null) }
        try {
            when (val result = provider.importSkill(sourceDir, false)) {
                is SkillImportResult.Success -> {
                    Logger.i(LOG_TAG, "importSkill succeeded dir=${sourceDir.name}")
                    updateState { copy(isImporting = false) }
                    load()
                }

                is SkillImportResult.NoSkillFile -> {
                    Logger.w(LOG_TAG, "importSkill noSkillFile dir=${sourceDir.name}")
                    updateState { copy(isImporting = false) }
                    sendEffect(SkillSettingsEffect.ShowNoSkillFileToast)
                }

                is SkillImportResult.Conflict -> {
                    Logger.w(
                        LOG_TAG,
                        "importSkill conflict dir=${sourceDir.name} target=${result.targetName}"
                    )
                    updateState {
                        copy(
                            isImporting = false,
                            importConflict = ImportConflictState(sourceDir, result.targetName),
                        )
                    }
                }

                is SkillImportResult.Error -> {
                    Logger.w(LOG_TAG, "importSkill error dir=${sourceDir.name} reason=${result.message}")
                    updateState { copy(isImporting = false) }
                    sendEffect(
                        SkillSettingsEffect.ShowImportErrorToast(
                            message = result.message,
                            fallbackResId = R.string.error_skill_import_failed
                        )
                    )
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "importSkill failed dir=${sourceDir.name} reason=${throwable.message}")
            updateState { copy(isImporting = false) }
            sendEffect(
                SkillSettingsEffect.ShowImportErrorToast(
                    message = throwable.message,
                    fallbackResId = R.string.error_skill_import_failed
                )
            )
        }
    }

    private suspend fun confirmImport() {
        val conflict = currentState.importConflict ?: return
        updateState { copy(isImporting = true, importConflict = null, inlineError = null) }
        try {
            when (val result = provider.importSkill(conflict.sourceDir, true)) {
                is SkillImportResult.Success -> {
                    updateState { copy(isImporting = false) }
                    load()
                }

                else -> {
                    updateState { copy(isImporting = false) }
                    sendEffect(
                        SkillSettingsEffect.ShowImportErrorToast(
                            message = null,
                            fallbackResId = R.string.error_skill_import_failed
                        )
                    )
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            updateState { copy(isImporting = false) }
            sendEffect(
                SkillSettingsEffect.ShowImportErrorToast(
                    message = throwable.message,
                    fallbackResId = R.string.error_skill_import_failed
                )
            )
        }
    }

    private fun validationMessage(validation: SkillValidation): String {
        return validation.message
    }
}

private fun SkillMetadata.toListItem(): SkillListItem {
    return SkillListItem(
        id = id,
        title = name.ifBlank { id },
        summary = description.ifBlank { relativePath },
        enabled = enabled,
    )
}
