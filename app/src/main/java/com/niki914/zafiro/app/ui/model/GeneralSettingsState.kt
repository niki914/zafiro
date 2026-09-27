package com.niki914.zafiro.app.ui.model

import com.niki914.logging.Logger
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService

sealed interface GeneralSettingsDialog {
    data object Language : GeneralSettingsDialog
    data object IdleTimeout : GeneralSettingsDialog
    data object RetryAttempts : GeneralSettingsDialog
}

data class GeneralSettingsUiState(
    val languageTag: String = "",
    val floatingBallEnabled: Boolean = false,
    val floatingBallAutoExpand: Boolean = true,
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
    data class ToggleFloatingBallAutoExpand(val enabled: Boolean) : GeneralSettingsIntent
    data class ToggleResidentNotification(val enabled: Boolean) : GeneralSettingsIntent
    data class ToggleLoadLastConversation(val enabled: Boolean) : GeneralSettingsIntent
    data class ToggleAlwaysShowMessageActions(val enabled: Boolean) : GeneralSettingsIntent
    data class SelectIdleTimeout(val seconds: Long) : GeneralSettingsIntent
    data class SelectRetryMaxAttempts(val attempts: Int) : GeneralSettingsIntent
    data class ToggleKeepScreenOn(val enabled: Boolean) : GeneralSettingsIntent
}

sealed interface GeneralSettingsEffect {
    data class ApplyApplicationLocales(val languageTag: String) : GeneralSettingsEffect
}

class GeneralSettingsViewModel : ComposeMVIViewModel<GeneralSettingsIntent, GeneralSettingsUiState, GeneralSettingsEffect>() {

    override fun initUiState(): GeneralSettingsUiState = GeneralSettingsUiState()

    override suspend fun handleIntent(intent: GeneralSettingsIntent) {
        when (intent) {
            GeneralSettingsIntent.Load -> loadSettings()
            is GeneralSettingsIntent.OpenDialog -> updateState { copy(activeDialog = intent.dialog) }
            GeneralSettingsIntent.DismissDialog -> updateState { copy(activeDialog = null) }
            is GeneralSettingsIntent.SelectLanguage -> selectLanguage(intent.tag)
            is GeneralSettingsIntent.ToggleFloatingBall -> toggleFloatingBall(intent.enabled)
            is GeneralSettingsIntent.ToggleFloatingBallAutoExpand ->
                toggleFloatingBallAutoExpand(intent.enabled)
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
            val floatingBallAutoExpand = XRepo.floatingBallAutoExpand()
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
                    floatingBallAutoExpand = floatingBallAutoExpand,
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

    /**
     * 权限门：先静默查，缺了才跑默认链。申请不需要 UI 层参与（Activity 由
     * ApplicationService 提供），所以它是个挂起端口，不是 effect。
     */
    private suspend fun ensurePermission(permission: Permission): Boolean {
        val pm = requireService<PermissionManager>()
        return pm.status(permission) == PermissionState.GRANTED ||
                pm.request(permission).finalState == PermissionState.GRANTED
    }

    private suspend fun toggleFloatingBall(enabled: Boolean) {
        if (!enabled) {
            updateState { copy(floatingBallEnabled = false) }
            XRepo.setFloatingBallEnabled(false)
            return
        }
        if (!ensurePermission(Permission.OVERLAY)) return
        updateState { copy(floatingBallEnabled = true) }
        XRepo.setFloatingBallEnabled(true)
    }

    private suspend fun toggleFloatingBallAutoExpand(enabled: Boolean) {
        updateState { copy(floatingBallAutoExpand = enabled) }
        XRepo.setFloatingBallAutoExpand(enabled)
    }

    private suspend fun toggleResidentNotification(enabled: Boolean) {
        if (!enabled) {
            updateState { copy(residentNotificationEnabled = false) }
            XRepo.setResidentNotificationEnabled(false)
            return
        }
        if (!ensurePermission(Permission.NOTIFICATION)) return
        updateState { copy(residentNotificationEnabled = true) }
        XRepo.setResidentNotificationEnabled(true)
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
