package com.niki914.zafiro.app.ui.model

import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.settings.model.RepetitionSensitivity

sealed interface RuntimeControlDialog {
    data object IdleTimeout : RuntimeControlDialog
    data object RetryAttempts : RuntimeControlDialog
    data object RepetitionSensitivity : RuntimeControlDialog
    data object CompactionThreshold : RuntimeControlDialog
}

data class RuntimeControlUiState(
    val idleTimeoutSeconds: Long = 60L,
    val retryMaxAttempts: Int = 3,
    val keepScreenOn: Boolean = true,
    val repetitionSensitivity: RepetitionSensitivity = RepetitionSensitivity.DEFAULT,
    val compactionThresholdPercent: Int = 70,
    val activeDialog: RuntimeControlDialog? = null,
    val isLoading: Boolean = false,
)

sealed interface RuntimeControlIntent {
    data object Load : RuntimeControlIntent
    data class OpenDialog(val dialog: RuntimeControlDialog) : RuntimeControlIntent
    data object DismissDialog : RuntimeControlIntent
    data class SelectIdleTimeout(val seconds: Long) : RuntimeControlIntent
    data class ChangeIdleTimeout(val seconds: Long) : RuntimeControlIntent
    data class SelectRetryMaxAttempts(val attempts: Int) : RuntimeControlIntent
    data class ChangeRetryMaxAttempts(val attempts: Int) : RuntimeControlIntent
    data class ToggleKeepScreenOn(val enabled: Boolean) : RuntimeControlIntent
    data class SelectRepetitionSensitivity(val sensitivity: RepetitionSensitivity) :
        RuntimeControlIntent
    data class SelectCompactionThreshold(val percent: Int) : RuntimeControlIntent
    data class ChangeCompactionThreshold(val percent: Int) : RuntimeControlIntent
}

class RuntimeControlViewModel :
    ComposeMVIViewModel<RuntimeControlIntent, RuntimeControlUiState, Nothing>() {

    override fun initUiState(): RuntimeControlUiState = RuntimeControlUiState()

    override suspend fun handleIntent(intent: RuntimeControlIntent) {
        when (intent) {
            RuntimeControlIntent.Load -> loadSettings()
            is RuntimeControlIntent.OpenDialog -> updateState { copy(activeDialog = intent.dialog) }
            RuntimeControlIntent.DismissDialog -> updateState { copy(activeDialog = null) }
            is RuntimeControlIntent.ChangeIdleTimeout ->
                updateState { copy(idleTimeoutSeconds = intent.seconds) }
            is RuntimeControlIntent.SelectIdleTimeout -> selectIdleTimeout(intent.seconds)
            is RuntimeControlIntent.ChangeRetryMaxAttempts ->
                updateState { copy(retryMaxAttempts = intent.attempts) }
            is RuntimeControlIntent.SelectRetryMaxAttempts ->
                selectRetryMaxAttempts(intent.attempts)

            is RuntimeControlIntent.ToggleKeepScreenOn -> toggleKeepScreenOn(intent.enabled)
            is RuntimeControlIntent.SelectRepetitionSensitivity ->
                selectRepetitionSensitivity(intent.sensitivity)
            is RuntimeControlIntent.ChangeCompactionThreshold ->
                updateState { copy(compactionThresholdPercent = sanitizeCompactionThreshold(intent.percent)) }
            is RuntimeControlIntent.SelectCompactionThreshold ->
                selectCompactionThreshold(intent.percent)
        }
    }

    private suspend fun loadSettings() {
        updateState { copy(isLoading = true) }
        runCatching {
            val idleTimeoutSeconds = XRepo.llmIdleTimeoutSeconds()
            val retryMaxAttempts = XRepo.llmRetryMaxAttempts()
            val keepScreenOn = XRepo.keepScreenOn()
            val repetitionSensitivity = XRepo.repetitionSensitivity()
            val compactionThresholdPercent = XRepo.contextCompactionThresholdPercent()
            updateState {
                copy(
                    idleTimeoutSeconds = idleTimeoutSeconds,
                    retryMaxAttempts = retryMaxAttempts,
                    keepScreenOn = keepScreenOn,
                    repetitionSensitivity = repetitionSensitivity,
                    compactionThresholdPercent = compactionThresholdPercent,
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

    private suspend fun selectCompactionThreshold(percent: Int) {
        val sanitized = sanitizeCompactionThreshold(percent)
        updateState { copy(compactionThresholdPercent = sanitized, activeDialog = null) }
        XRepo.setContextCompactionThresholdPercent(sanitized)
    }

    private fun sanitizeCompactionThreshold(percent: Int): Int {
        return if (percent < 15) 0 else percent.coerceIn(30, 95)
    }

    private companion object {
        private const val TAG = "niki914_zafiro_RuntimeControlViewModel"
    }
}
