package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.settings.form.buildFormSpec
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.settings.model.RuntimeEnvContextConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

data class CustomVariableFormSnapshot(
    val key: String,
    val value: String,
    val enabled: Boolean,
)

val CustomVariableFormSpec = buildFormSpec<CustomVariableFormSnapshot, String> {
    field("key", R.string.ui_settings_env_context_field_name) { it.key }
    field("value", R.string.ui_settings_env_context_field_content) { it.value }
    field("enabled", R.string.ui_settings_env_context_field_enabled) { it.enabled }
}

data class CustomVariableFormState(
    val previousKey: String? = null,
    val key: String = "",
    val value: String = "",
    val enabled: Boolean = true,
    val initialSnapshot: CustomVariableFormSnapshot? = null,
    @param:StringRes val keyErrorResId: Int? = null,
    @param:StringRes val valueErrorResId: Int? = null,
) {
    fun toSnapshot(): CustomVariableFormSnapshot = CustomVariableFormSnapshot(
        key = key.trim(),
        value = value.trim(),
        enabled = enabled,
    )

    fun withCurrentSnapshotAsInitial(): CustomVariableFormState = copy(
        initialSnapshot = toSnapshot()
    )
}

val CustomVariableFormState.hasUnsavedChanges: Boolean
    get() = CustomVariableFormSpec.hasChanges(initialSnapshot, toSnapshot())

fun CustomVariableFormState.unsavedFieldLabelRes(): List<Int> {
    return CustomVariableFormSpec.dirtyFieldLabelRes(initialSnapshot, toSnapshot())
}

data class VariableDeleteConfirmationState(
    val key: String,
)

sealed interface EnvContextInlineError {
    data class SaveFailed(val causeMessage: String?) : EnvContextInlineError
    data class DeleteFailed(val causeMessage: String?) : EnvContextInlineError
}

data class EnvContextSettingsUiState(
    val enabled: Boolean = false,
    val keys: Set<String> = emptySet(),
    val variables: Map<String, String> = emptyMap(),
    val formState: CustomVariableFormState = CustomVariableFormState(),
    val deleteConfirmation: VariableDeleteConfirmationState? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val inlineError: EnvContextInlineError? = null,
)

sealed interface EnvContextSettingsIntent {
    data object Load : EnvContextSettingsIntent
    data class MasterToggleChanged(val enabled: Boolean) : EnvContextSettingsIntent
    data class KeyToggleChanged(val key: String, val checked: Boolean) : EnvContextSettingsIntent

    data object StartCreateVariable : EnvContextSettingsIntent
    data class StartEditVariable(val key: String) : EnvContextSettingsIntent

    data class KeyInputChanged(val value: String) : EnvContextSettingsIntent
    data class ValueInputChanged(val value: String) : EnvContextSettingsIntent
    data class EnabledInputChanged(val value: Boolean) : EnvContextSettingsIntent

    data object SaveVariable : EnvContextSettingsIntent
    data object RequestDeleteVariable : EnvContextSettingsIntent
    data object DismissDeleteConfirmation : EnvContextSettingsIntent
    data object ConfirmDeleteVariable : EnvContextSettingsIntent
}

sealed interface EnvContextSettingsEffect {
    data object ExitDetail : EnvContextSettingsEffect
    data object FocusKey : EnvContextSettingsEffect
    data object FocusValue : EnvContextSettingsEffect
}

class EnvContextSettingsViewModel :
    ComposeMVIViewModel<EnvContextSettingsIntent, EnvContextSettingsUiState, EnvContextSettingsEffect>() {

    init {
        viewModelScope.launch {
            settingsChanges.collect {
                loadSettings()
            }
        }
    }

    override fun initUiState(): EnvContextSettingsUiState = EnvContextSettingsUiState()

    override suspend fun handleIntent(intent: EnvContextSettingsIntent) {
        when (intent) {
            EnvContextSettingsIntent.Load -> loadSettings()
            is EnvContextSettingsIntent.MasterToggleChanged -> toggleMaster(intent.enabled)
            is EnvContextSettingsIntent.KeyToggleChanged -> toggleKey(intent.key, intent.checked)

            EnvContextSettingsIntent.StartCreateVariable -> startCreateVariable()
            is EnvContextSettingsIntent.StartEditVariable -> startEditVariable(intent.key)

            is EnvContextSettingsIntent.KeyInputChanged -> updateState {
                copy(
                    formState = formState.copy(key = intent.value, keyErrorResId = null),
                    inlineError = null,
                )
            }

            is EnvContextSettingsIntent.ValueInputChanged -> updateState {
                copy(
                    formState = formState.copy(value = intent.value, valueErrorResId = null),
                    inlineError = null,
                )
            }

            is EnvContextSettingsIntent.EnabledInputChanged -> updateState {
                copy(
                    formState = formState.copy(enabled = intent.value),
                    inlineError = null,
                )
            }

            EnvContextSettingsIntent.SaveVariable -> saveVariable()
            EnvContextSettingsIntent.RequestDeleteVariable -> {
                val currentKey = currentState.formState.previousKey ?: return
                updateState { copy(deleteConfirmation = VariableDeleteConfirmationState(currentKey)) }
            }

            EnvContextSettingsIntent.DismissDeleteConfirmation -> updateState {
                copy(deleteConfirmation = null)
            }

            EnvContextSettingsIntent.ConfirmDeleteVariable -> confirmDeleteVariable()
        }
    }

    private suspend fun loadSettings() {
        updateState { copy(isLoading = true) }
        runCatchingCancellable {
            val config = XRepo.llmConfigs.envContext()
            updateState {
                copy(
                    enabled = config.enabled,
                    keys = config.keys,
                    variables = config.variables,
                    isLoading = false,
                )
            }
        }.onFailure { throwable ->
            Logger.w(TAG, "loadSettings failed: ${throwable.message}")
            updateState { copy(isLoading = false) }
        }
    }

    private suspend fun toggleMaster(enabled: Boolean) {
        updateState { copy(enabled = enabled) }
        runCatchingCancellable {
            val baseConfig = XRepo.llmConfigs.envContext()
            val newConfig = baseConfig.copy(enabled = enabled)
            XRepo.llmConfigs.saveEnvContext(newConfig)
            notifySettingsChanged()
        }.onFailure { throwable ->
            Logger.w(TAG, "toggleMaster failed: ${throwable.message}")
        }
    }

    private suspend fun toggleKey(key: String, checked: Boolean) {
        val updatedKeys = currentState.keys.toMutableSet().apply {
            if (checked) add(key) else remove(key)
        }
        updateState { copy(keys = updatedKeys) }
        runCatchingCancellable {
            val baseConfig = XRepo.llmConfigs.envContext()
            val newConfig = baseConfig.copy(
                keys = if (checked) baseConfig.keys + key else baseConfig.keys - key,
            )
            XRepo.llmConfigs.saveEnvContext(newConfig)
            notifySettingsChanged()
        }.onFailure { throwable ->
            Logger.w(TAG, "toggleKey failed: ${throwable.message}")
        }
    }

    private fun startCreateVariable() {
        val snapshot = CustomVariableFormSnapshot(key = "", value = "", enabled = true)
        updateState {
            copy(
                formState = CustomVariableFormState(
                    previousKey = null,
                    key = "",
                    value = "",
                    enabled = true,
                    initialSnapshot = snapshot,
                ),
                inlineError = null,
            )
        }
    }

    private suspend fun startEditVariable(key: String) {
        val config = runCatchingCancellable { XRepo.llmConfigs.envContext() }.getOrNull()
        val value = currentState.variables[key] ?: config?.variables?.get(key).orEmpty()
        val isEnabled = if (currentState.keys.isNotEmpty()) {
            key in currentState.keys
        } else {
            config?.keys?.contains(key) ?: true
        }
        val snapshot = CustomVariableFormSnapshot(key = key, value = value, enabled = isEnabled)
        updateState {
            copy(
                formState = CustomVariableFormState(
                    previousKey = key,
                    key = key,
                    value = value,
                    enabled = isEnabled,
                    initialSnapshot = snapshot,
                ),
                inlineError = null,
            )
        }
    }

    private suspend fun saveVariable() {
        val formState = currentState.formState
        val normalizedKey = formState.key.trim()
        val normalizedValue = formState.value.trim()

        val baseConfig = runCatchingCancellable { XRepo.llmConfigs.envContext() }.getOrDefault(RuntimeEnvContextConfig())

        val keyErrorResId = when {
            normalizedKey.isBlank() -> R.string.ui_settings_env_context_error_key_required
            isReservedKey(normalizedKey) -> R.string.ui_settings_env_context_error_key_reserved
            !KEY_FORMAT_REGEX.matches(normalizedKey) -> R.string.ui_settings_env_context_error_key_invalid_format
            (formState.previousKey == null || formState.previousKey != normalizedKey) &&
                    (currentState.variables.containsKey(normalizedKey) || baseConfig.variables.containsKey(normalizedKey)) ->
                R.string.ui_settings_env_context_error_key_duplicate

            else -> null
        }

        val valueErrorResId = when {
            normalizedValue.isBlank() -> R.string.ui_settings_env_context_error_value_required
            else -> null
        }

        if (keyErrorResId != null || valueErrorResId != null) {
            updateState {
                copy(
                    formState = formState.copy(
                        key = normalizedKey,
                        value = normalizedValue,
                        keyErrorResId = keyErrorResId,
                        valueErrorResId = valueErrorResId,
                    ),
                    isSaving = false,
                    inlineError = null,
                )
            }
            sendEffect(
                if (keyErrorResId != null) EnvContextSettingsEffect.FocusKey
                else EnvContextSettingsEffect.FocusValue
            )
            return
        }

        updateState { copy(isSaving = true) }
        runCatchingCancellable {
            val updatedVariables = baseConfig.variables.toMutableMap()
            val updatedKeys = baseConfig.keys.toMutableSet()

            if (formState.previousKey != null && formState.previousKey != normalizedKey) {
                updatedVariables.remove(formState.previousKey)
                updatedKeys.remove(formState.previousKey)
            }

            updatedVariables[normalizedKey] = normalizedValue
            if (formState.enabled) {
                updatedKeys.add(normalizedKey)
            } else {
                updatedKeys.remove(normalizedKey)
            }

            val newConfig = baseConfig.copy(
                keys = updatedKeys,
                variables = updatedVariables,
            )

            XRepo.llmConfigs.saveEnvContext(newConfig)
            updateState {
                copy(
                    keys = updatedKeys,
                    variables = updatedVariables,
                    formState = formState.copy(
                        previousKey = normalizedKey,
                        key = normalizedKey,
                        value = normalizedValue,
                    ).withCurrentSnapshotAsInitial(),
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(EnvContextSettingsEffect.ExitDetail)
        }.onFailure { throwable ->
            Logger.w(TAG, "saveVariable failed: ${throwable.message}")
            updateState {
                copy(
                    isSaving = false,
                    inlineError = EnvContextInlineError.SaveFailed(throwable.message),
                )
            }
        }
    }

    private suspend fun confirmDeleteVariable() {
        val keyToDelete = currentState.deleteConfirmation?.key
            ?: currentState.formState.previousKey
            ?: return

        updateState { copy(isSaving = true) }
        runCatchingCancellable {
            val baseConfig = XRepo.llmConfigs.envContext()
            val updatedVariables = baseConfig.variables.toMutableMap().apply { remove(keyToDelete) }
            val updatedKeys = baseConfig.keys.toMutableSet().apply { remove(keyToDelete) }
            val newConfig = baseConfig.copy(
                keys = updatedKeys,
                variables = updatedVariables,
            )

            XRepo.llmConfigs.saveEnvContext(newConfig)
            updateState {
                copy(
                    keys = updatedKeys,
                    variables = updatedVariables,
                    deleteConfirmation = null,
                    isSaving = false,
                    inlineError = null,
                )
            }
            notifySettingsChanged()
            sendEffect(EnvContextSettingsEffect.ExitDetail)
        }.onFailure { throwable ->
            Logger.w(TAG, "deleteVariable failed: ${throwable.message}")
            updateState {
                copy(
                    deleteConfirmation = null,
                    isSaving = false,
                    inlineError = EnvContextInlineError.DeleteFailed(throwable.message),
                )
            }
        }
    }

    companion object {
        private const val TAG = "niki914_zafiro_EnvContextSettingsViewModel"
        private val KEY_FORMAT_REGEX = Regex("^[a-zA-Z0-9_-]+$")

        val settingsChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        fun notifySettingsChanged() {
            settingsChanges.tryEmit(Unit)
        }

        private fun isReservedKey(key: String): Boolean {
            return key.equals(RuntimeEnvContextConfig.KEY_DATE, ignoreCase = true) ||
                    key.equals(RuntimeEnvContextConfig.KEY_TIME, ignoreCase = true) ||
                    key.equals(RuntimeEnvContextConfig.KEY_DEVICE, ignoreCase = true) ||
                    key.equals(RuntimeEnvContextConfig.KEY_VIRTUAL_DISPLAY, ignoreCase = true)
        }
    }
}
