package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.StorageApi
import com.niki914.zafiro.repo.StorageKind
import com.niki914.zafiro.repo.StorageUsage
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

enum class StorageCategory(
    val kind: StorageKind,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
) {
    ToolOutput(
        kind = StorageKind.ToolOutput,
        titleRes = R.string.ui_settings_storage_tool_output,
        descriptionRes = R.string.ui_settings_storage_tool_output_summary,
    ),
    ImageCache(
        kind = StorageKind.ImageCache,
        titleRes = R.string.ui_settings_storage_image_cache,
        descriptionRes = R.string.ui_settings_storage_image_cache_summary,
    ),
    Downloads(
        kind = StorageKind.Downloads,
        titleRes = R.string.ui_settings_storage_downloads,
        descriptionRes = R.string.ui_settings_storage_downloads_summary,
    ),
    OtherCache(
        kind = StorageKind.OtherCache,
        titleRes = R.string.ui_settings_storage_other_cache,
        descriptionRes = R.string.ui_settings_storage_other_cache_summary,
    ),
}

data class StorageCategoryUiState(
    val category: StorageCategory,
    val bytes: Long,
    val isClearing: Boolean = false,
)

data class StorageConfirmationState(
    val category: StorageCategory,
)

data class StorageSettingsUiState(
    val categories: List<StorageCategoryUiState> = emptyList(),
    val isLoading: Boolean = true,
    val confirmation: StorageConfirmationState? = null,
    val inlineError: StorageInlineError? = null,
)

sealed interface StorageInlineError {
    data class LoadFailed(val message: String?) : StorageInlineError
    data class ClearFailed(val message: String?) : StorageInlineError
}

sealed interface StorageSettingsIntent {
    data object Load : StorageSettingsIntent
    data class RequestClear(val category: StorageCategory) : StorageSettingsIntent
    data object DismissConfirmation : StorageSettingsIntent
    data object ConfirmClear : StorageSettingsIntent
}

interface StorageUsageProvider {
    suspend fun usage(): List<StorageUsage>
    suspend fun clear(kind: StorageKind)
}

class XRepoStorageUsageProvider : StorageUsageProvider {
    private val api: StorageApi
        get() = XRepo.storage

    override suspend fun usage(): List<StorageUsage> = api.usage()
    override suspend fun clear(kind: StorageKind) = api.clear(kind)
}

class StorageSettingsViewModel(
    private val provider: StorageUsageProvider = XRepoStorageUsageProvider(),
) : ComposeMVIViewModel<StorageSettingsIntent, StorageSettingsUiState, Nothing>() {

    override fun initUiState(): StorageSettingsUiState = StorageSettingsUiState()

    override suspend fun handleIntent(intent: StorageSettingsIntent) {
        when (intent) {
            StorageSettingsIntent.Load -> load()
            is StorageSettingsIntent.RequestClear -> updateState {
                copy(confirmation = StorageConfirmationState(intent.category), inlineError = null)
            }
            StorageSettingsIntent.DismissConfirmation -> updateState {
                copy(confirmation = null, inlineError = null)
            }
            StorageSettingsIntent.ConfirmClear -> confirmClear()
        }
    }

    private suspend fun load() {
        updateState { copy(isLoading = true) }
        try {
            val usage = provider.usage().associateBy { it.kind }
            updateState {
                copy(
                    categories = StorageCategory.entries.map { category ->
                        StorageCategoryUiState(
                            category = category,
                            bytes = usage[category.kind]?.bytes ?: 0L,
                        )
                    },
                    isLoading = false,
                    confirmation = null,
                    inlineError = null,
                )
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "load failed reason=${throwable.message}")
            updateState {
                copy(
                    isLoading = false,
                    inlineError = StorageInlineError.LoadFailed(throwable.message),
                )
            }
        }
    }

    private suspend fun confirmClear() {
        val category = currentState.confirmation?.category ?: return
        updateState {
            copy(
                confirmation = null,
                categories = categories.map {
                    if (it.category == category) it.copy(isClearing = true) else it
                },
                inlineError = null,
            )
        }
        try {
            provider.clear(category.kind)
            Logger.i(LOG_TAG, "clear succeeded category=${category.kind}")
            load()
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            Logger.w(LOG_TAG, "clear failed category=${category.kind} reason=${throwable.message}")
            load()
            updateState {
                copy(inlineError = StorageInlineError.ClearFailed(throwable.message))
            }
        }
    }

    private companion object {
        private const val LOG_TAG = "niki914_nexus_StorageSettingsViewModel"
    }
}

internal fun formatStorageBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        "%.1f %s".format(value, units[unitIndex])
    }
}
