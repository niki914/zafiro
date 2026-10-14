package com.niki914.zafiro.app.ui.model

import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.settings.model.RepetitionSensitivity

sealed interface RuntimeControlDialog {
    data object IdleTimeout : RuntimeControlDialog
    data object RetryAttempts : RuntimeControlDialog
    data object RepetitionSensitivity : RuntimeControlDialog
}

data class RuntimeControlUiState(
    val idleTimeoutSeconds: Long = 60L,
    val retryMaxAttempts: Int = 3,
    val keepScreenOn: Boolean = true,
    val repetitionSensitivity: RepetitionSensitivity = RepetitionSensitivity.DEFAULT,
    val activeDialog: RuntimeControlDialog? = null,
    val isLoading: Boolean = false,
)

sealed interface RuntimeControlIntent {
    data object Load : RuntimeControlIntent
    data class OpenDialog(val dialog: RuntimeControlDialog) : RuntimeControlIntent
    data object DismissDialog : RuntimeControlIntent
    data class SelectIdleTimeout(val seconds: Long) : RuntimeControlIntent
    data class SelectRetryMaxAttempts(val attempts: Int) : RuntimeControlIntent
    data class ToggleKeepScreenOn(val enabled: Boolean) : RuntimeControlIntent
    data class SelectRepetitionSensitivity(val sensitivity: RepetitionSensitivity) :
        RuntimeControlIntent
}

class RuntimeControlViewModel :
    ComposeMVIViewModel<RuntimeControlIntent, RuntimeControlUiState, Nothing>() {

    override fun initUiState(): RuntimeControlUiState = RuntimeControlUiState()

    override suspend fun handleIntent(intent: RuntimeControlIntent) {
        when (intent) {
            RuntimeControlIntent.Load -> loadSettings()
            is RuntimeControlIntent.OpenDialog -> updateState { copy(activeDialog = intent.dialog) }
            RuntimeControlIntent.DismissDialog -> updateState { copy(activeDialog = null) }
            is RuntimeControlIntent.SelectIdleTimeout -> selectIdleTimeout(intent.seconds)
            is RuntimeControlIntent.SelectRetryMaxAttempts ->
                selectRetryMaxAttempts(intent.attempts)

            is RuntimeControlIntent.ToggleKeepScreenOn -> toggleKeepScreenOn(intent.enabled)
            is RuntimeControlIntent.SelectRepetitionSensitivity ->
                selectRepetitionSensitivity(intent.sensitivity)
        }
    }

    private suspend fun loadSettings() {
        updateState { copy(isLoading = true) }
        runCatching {
            val idleTimeoutSeconds = XRepo.llmIdleTimeoutSeconds()
            val retryMaxAttempts = XRepo.llmRetryMaxAttempts()
            val keepScreenOn = XRepo.keepScreenOn()
            val repetitionSensitivity = XRepo.repetitionSensitivity()
            updateState {
                copy(
                    idleTimeoutSeconds = idleTimeoutSeconds,
                    retryMaxAttempts = retryMaxAttempts,
                    keepScreenOn = keepScreenOn,
                    repetitionSensitivity = repetitionSensitivity,
                    isLoading = false,
                )
            }
        }.onFailure {
            Logger.w(TAG, "load failed ${it.message}")
            updateState { copy(isLoading = false) }
        }
    }

    private suspend fun selectIdleTimeout(seconds: Long) {
        updateState { copy(idleTimeoutSeconds = seconds, activeDialog = null) }
        XRepo.setLlmIdleTimeoutSeconds(seconds)
    }

    private suspend fun selectRetryMaxAttempts(attempts: Int) {
        updateState { copy(retryMaxAttempts = attempts, activeDialog = null) }
        XRepo.setLlmRetryMaxAttempts(attempts)
    }

    private suspend fun toggleKeepScreenOn(enabled: Boolean) {
        updateState { copy(keepScreenOn = enabled) }
        XRepo.setKeepScreenOn(enabled)
    }

    private suspend fun selectRepetitionSensitivity(sensitivity: RepetitionSensitivity) {
        updateState { copy(repetitionSensitivity = sensitivity, activeDialog = null) }
        XRepo.setRepetitionSensitivity(sensitivity)
    }

    private companion object {
        private const val TAG = "niki914_zafiro_RuntimeControlViewModel"
    }
}
