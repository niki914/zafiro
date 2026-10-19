package com.niki914.zafiro.app.ui.model

import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService

data class GeneralSettingsUiState(
    val floatingBallEnabled: Boolean = false,
    val floatingBallAutoExpand: Boolean = true,
    val residentNotificationEnabled: Boolean = false,
    val turnNotificationEnabled: Boolean = false,
    val loadLastConversation: Boolean = true,
    val alwaysShowMessageActions: Boolean = true,
    val alwaysShowDetailedToolOutput: Boolean = false,
    val voiceAssistantIsolatedSession: Boolean = true,
    val isLoading: Boolean = false,
)

sealed interface GeneralSettingsIntent {
    data object Load : GeneralSettingsIntent
    data class ToggleFloatingBall(val enabled: Boolean) : GeneralSettingsIntent
    data class ToggleFloatingBallAutoExpand(val enabled: Boolean) : GeneralSettingsIntent
    data class ToggleResidentNotification(val enabled: Boolean) : GeneralSettingsIntent
    data class ToggleTurnNotification(val enabled: Boolean) : GeneralSettingsIntent
    data class ToggleLoadLastConversation(val enabled: Boolean) : GeneralSettingsIntent
    data class ToggleAlwaysShowMessageActions(val enabled: Boolean) : GeneralSettingsIntent
    data class ToggleAlwaysShowDetailedToolOutput(val enabled: Boolean) : GeneralSettingsIntent
    data class ToggleVoiceAssistantIsolatedSession(val enabled: Boolean) : GeneralSettingsIntent
}

class GeneralSettingsViewModel :
    ComposeMVIViewModel<GeneralSettingsIntent, GeneralSettingsUiState, Nothing>() {

    override fun initUiState(): GeneralSettingsUiState = GeneralSettingsUiState()

    override suspend fun handleIntent(intent: GeneralSettingsIntent) {
        when (intent) {
            GeneralSettingsIntent.Load -> loadSettings()
            is GeneralSettingsIntent.ToggleFloatingBall -> toggleFloatingBall(intent.enabled)
            is GeneralSettingsIntent.ToggleFloatingBallAutoExpand ->
                toggleFloatingBallAutoExpand(intent.enabled)

            is GeneralSettingsIntent.ToggleResidentNotification -> toggleResidentNotification(intent.enabled)
            is GeneralSettingsIntent.ToggleTurnNotification -> toggleTurnNotification(intent.enabled)
            is GeneralSettingsIntent.ToggleLoadLastConversation -> toggleLoadLastConversation(intent.enabled)
            is GeneralSettingsIntent.ToggleAlwaysShowMessageActions -> toggleAlwaysShowMessageActions(
                intent.enabled
            )

            is GeneralSettingsIntent.ToggleAlwaysShowDetailedToolOutput ->
                toggleAlwaysShowDetailedToolOutput(intent.enabled)

            is GeneralSettingsIntent.ToggleVoiceAssistantIsolatedSession ->
                toggleVoiceAssistantIsolatedSession(intent.enabled)
        }
    }

    private suspend fun loadSettings() {
        updateState { copy(isLoading = true) }
        runCatching {
            val loadLastConversation = XRepo.loadLastConversationOnStartup()
            val alwaysShowMessageActions = XRepo.alwaysShowMessageActions()
            val alwaysShowDetailedToolOutput = XRepo.alwaysShowDetailedToolOutput()
            val voiceAssistantIsolatedSession = XRepo.voiceAssistantIsolatedSession()
            val floatingBallEnabled = XRepo.floatingBallEnabled()
            val floatingBallAutoExpand = XRepo.floatingBallAutoExpand()
            val residentNotificationEnabled = XRepo.residentNotificationEnabled()
            val turnNotificationEnabled = XRepo.turnNotificationEnabled()
            updateState {
                copy(
                    loadLastConversation = loadLastConversation,
                    alwaysShowMessageActions = alwaysShowMessageActions,
                    alwaysShowDetailedToolOutput = alwaysShowDetailedToolOutput,
                    voiceAssistantIsolatedSession = voiceAssistantIsolatedSession,
                    floatingBallEnabled = floatingBallEnabled,
                    floatingBallAutoExpand = floatingBallAutoExpand,
                    residentNotificationEnabled = residentNotificationEnabled,
                    turnNotificationEnabled = turnNotificationEnabled,
                    isLoading = false,
                )
            }
        }.onFailure {
            Logger.w(TAG, "load failed ${it.message}")
            updateState { copy(isLoading = false) }
        }
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

    private suspend fun toggleTurnNotification(enabled: Boolean) {
        if (!enabled) {
            updateState { copy(turnNotificationEnabled = false) }
            XRepo.setTurnNotificationEnabled(false)
            return
        }
        if (!ensurePermission(Permission.NOTIFICATION)) return
        updateState { copy(turnNotificationEnabled = true) }
        XRepo.setTurnNotificationEnabled(true)
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

    private suspend fun toggleAlwaysShowDetailedToolOutput(enabled: Boolean) {
        updateState { copy(alwaysShowDetailedToolOutput = enabled) }
        XRepo.setAlwaysShowDetailedToolOutput(enabled)
    }

    private suspend fun toggleVoiceAssistantIsolatedSession(enabled: Boolean) {
        updateState { copy(voiceAssistantIsolatedSession = enabled) }
        try {
            XRepo.setVoiceAssistantIsolatedSession(enabled)
        } catch (e: Throwable) {
            e.printStackTrace()
            throw e
        }
    }

    private companion object {
        private const val TAG = "niki914_zafiro_GeneralSettingsViewModel"
    }
}
