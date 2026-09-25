package com.niki914.zafiro.app.ui.model

import com.niki914.logging.Logger
import com.niki914.permission.Permission
import com.niki914.permission.PermissionState
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.app.PermissionHolder
import com.niki914.zafiro.repo.XRepo

sealed interface GeneralSettingsDialog {
    data object Language : GeneralSettingsDialog
    data object IdleTimeout : GeneralSettingsDialog
    data object RetryAttempts : GeneralSettingsDialog
}

data class GeneralSettingsUiState(
    val languageTag: String = "",
    val floatingBallEnabled: Boolean = false,
    val residentNotificationEnabled: Boolean = false,
    val loadLastConversation: Boolean = false,
    val alwaysShowMessageActions: Boolean = true,
    val idleTimeoutSeconds: Long = 60L,
    val retryMaxAttempts: Int = 3,
    val keepScreenOn: Boolean = true,
    val activeDialog: GeneralSettingsDialog? = null,
    val isLoading: Boolean = false,
)

sealed interface GeneralSettingsIntent {
    data object Load : GeneralSettingsIntent
    data class OpenDialog(val dialog: GeneralSettingsDialog) : GeneralSettingsIntent
    data object DismissDialog : GeneralSettingsIntent
    data class SelectLanguage(val tag: String) : GeneralSettingsIntent
    data class ToggleFloatingBall(val enabled: Boolean) : GeneralSettingsIntent
    data class OnOverlayPermissionResult(val granted: Boolean) : GeneralSettingsIntent
    data class ToggleResidentNotification(val enabled: Boolean) : GeneralSettingsIntent
    data class ToggleLoadLastConversation(val enabled: Boolean) : GeneralSettingsIntent
    data class ToggleAlwaysShowMessageActions(val enabled: Boolean) : GeneralSettingsIntent
    data class SelectIdleTimeout(val seconds: Long) : GeneralSettingsIntent
    data class SelectRetryMaxAttempts(val attempts: Int) : GeneralSettingsIntent
    data class ToggleKeepScreenOn(val enabled: Boolean) : GeneralSettingsIntent
}

sealed interface GeneralSettingsEffect {
    data object RequestOverlayPermission : GeneralSettingsEffect
    data class ApplyApplicationLocales(val languageTag: String) : GeneralSettingsEffect
}

class GeneralSettingsViewModel(
    private val isOverlayPermissionGranted: suspend () -> Boolean = {
        val context = ContextProvider.awaitIfAvailable()
        if (context != null) {
            PermissionHolder.get(context).status(Permission.OVERLAY) == PermissionState.GRANTED
        } else {
            false
        }
    },
) : ComposeMVIViewModel<GeneralSettingsIntent, GeneralSettingsUiState, GeneralSettingsEffect>() {

    override fun initUiState(): GeneralSettingsUiState = GeneralSettingsUiState()

    override suspend fun handleIntent(intent: GeneralSettingsIntent) {
        when (intent) {
            GeneralSettingsIntent.Load -> loadSettings()
            is GeneralSettingsIntent.OpenDialog -> updateState { copy(activeDialog = intent.dialog) }
            GeneralSettingsIntent.DismissDialog -> updateState { copy(activeDialog = null) }
            is GeneralSettingsIntent.SelectLanguage -> selectLanguage(intent.tag)
            is GeneralSettingsIntent.ToggleFloatingBall -> toggleFloatingBall(intent.enabled)
            is GeneralSettingsIntent.OnOverlayPermissionResult -> onOverlayPermissionResult(intent.granted)
            is GeneralSettingsIntent.ToggleResidentNotification -> toggleResidentNotification(intent.enabled)
            is GeneralSettingsIntent.ToggleLoadLastConversation -> toggleLoadLastConversation(intent.enabled)
            is GeneralSettingsIntent.ToggleAlwaysShowMessageActions -> toggleAlwaysShowMessageActions(intent.enabled)
            is GeneralSettingsIntent.SelectIdleTimeout -> selectIdleTimeout(intent.seconds)
            is GeneralSettingsIntent.SelectRetryMaxAttempts -> selectRetryMaxAttempts(intent.attempts)
            is GeneralSettingsIntent.ToggleKeepScreenOn -> toggleKeepScreenOn(intent.enabled)
        }
    }

    private suspend fun loadSettings() {
        updateState { copy(isLoading = true) }
        runCatching {
            val languageTag = XRepo.languageTag()
            val loadLastConversation = XRepo.loadLastConversationOnStartup()
            val alwaysShowMessageActions = XRepo.alwaysShowMessageActions()
            val idleTimeoutSeconds = XRepo.llmIdleTimeoutSeconds()
            val retryMaxAttempts = XRepo.llmRetryMaxAttempts()
            val keepScreenOn = XRepo.keepScreenOn()
            val floatingBallEnabled = XRepo.floatingBallEnabled()
            val residentNotificationEnabled = XRepo.residentNotificationEnabled()
            updateState {
                copy(
                    languageTag = languageTag,
                    loadLastConversation = loadLastConversation,
                    alwaysShowMessageActions = alwaysShowMessageActions,
                    idleTimeoutSeconds = idleTimeoutSeconds,
                    retryMaxAttempts = retryMaxAttempts,
                    keepScreenOn = keepScreenOn,
                    floatingBallEnabled = floatingBallEnabled,
                    residentNotificationEnabled = residentNotificationEnabled,
                    isLoading = false,
                )
            }
        }.onFailure {
            Logger.w(TAG, "load failed ${it.message}")
            updateState { copy(isLoading = false) }
        }
    }

    private suspend fun selectLanguage(tag: String) {
        updateState { copy(languageTag = tag, activeDialog = null) }
        try {
            XRepo.setLanguageTag(tag)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
        sendEffect(GeneralSettingsEffect.ApplyApplicationLocales(tag))
    }

    private suspend fun toggleFloatingBall(enabled: Boolean) {
        if (enabled) {
            val granted = isOverlayPermissionGranted()
            if (!granted) {
                sendEffect(GeneralSettingsEffect.RequestOverlayPermission)
                return
            }
            updateState { copy(floatingBallEnabled = true) }
            XRepo.setFloatingBallEnabled(true)
        } else {
            updateState { copy(floatingBallEnabled = false) }
            XRepo.setFloatingBallEnabled(false)
        }
    }

    private suspend fun onOverlayPermissionResult(granted: Boolean) {
        if (granted) {
            updateState { copy(floatingBallEnabled = true) }
            XRepo.setFloatingBallEnabled(true)
        } else {
            updateState { copy(floatingBallEnabled = false) }
        }
    }

    private suspend fun toggleResidentNotification(enabled: Boolean) {
        updateState { copy(residentNotificationEnabled = enabled) }
        try {
            XRepo.setResidentNotificationEnabled(enabled)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    private suspend fun toggleLoadLastConversation(enabled: Boolean) {
        updateState { copy(loadLastConversation = enabled) }
        try {
            XRepo.setLoadLastConversationOnStartup(enabled)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    private suspend fun toggleAlwaysShowMessageActions(enabled: Boolean) {
        updateState { copy(alwaysShowMessageActions = enabled) }
        try {
            XRepo.setAlwaysShowMessageActions(enabled)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    private suspend fun selectIdleTimeout(seconds: Long) {
        updateState { copy(idleTimeoutSeconds = seconds, activeDialog = null) }
        try {
            XRepo.setLlmIdleTimeoutSeconds(seconds)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    private suspend fun selectRetryMaxAttempts(attempts: Int) {
        updateState { copy(retryMaxAttempts = attempts, activeDialog = null) }
        try {
            XRepo.setLlmRetryMaxAttempts(attempts)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    private suspend fun toggleKeepScreenOn(enabled: Boolean) {
        updateState { copy(keepScreenOn = enabled) }
        try {
            XRepo.setKeepScreenOn(enabled)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    private companion object {
        private const val TAG = "GeneralSettingsViewModel"
    }
}
