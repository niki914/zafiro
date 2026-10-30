package com.niki914.zafiro.app.ui.model

import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService
import kotlinx.coroutines.launch

data class FloatingBallSettingsUiState(
    val enabled: Boolean = false,
    val autoExpand: Boolean = true,
    val opacityPercent: Int = 60,
    val submergedOffsetDp: Int = 8,
    val isLoading: Boolean = false,
)

sealed interface FloatingBallSettingsIntent {
    data object Load : FloatingBallSettingsIntent
    data class ToggleEnabled(val enabled: Boolean) : FloatingBallSettingsIntent
    data class ToggleAutoExpand(val enabled: Boolean) : FloatingBallSettingsIntent
    data class ChangeOpacity(val percent: Int) : FloatingBallSettingsIntent
    data class SelectOpacity(val percent: Int) : FloatingBallSettingsIntent
    data class ChangeSubmergedOffset(val offsetDp: Int) : FloatingBallSettingsIntent
    data class SelectSubmergedOffset(val offsetDp: Int) : FloatingBallSettingsIntent
}

class FloatingBallSettingsViewModel :
    ComposeMVIViewModel<FloatingBallSettingsIntent, FloatingBallSettingsUiState, Nothing>() {

    init {
        viewModelScope.launch {
            XRepo.floatingBallEnabledSetting.collect { enabled ->
                updateState { copy(enabled = enabled) }
            }
        }
    }

    override fun initUiState(): FloatingBallSettingsUiState = FloatingBallSettingsUiState()

    override suspend fun handleIntent(intent: FloatingBallSettingsIntent) {
        when (intent) {
            FloatingBallSettingsIntent.Load -> loadSettings()
            is FloatingBallSettingsIntent.ToggleEnabled -> toggleEnabled(intent.enabled)
            is FloatingBallSettingsIntent.ToggleAutoExpand -> toggleAutoExpand(intent.enabled)
            is FloatingBallSettingsIntent.ChangeOpacity -> changeOpacity(intent.percent)
            is FloatingBallSettingsIntent.SelectOpacity -> selectOpacity(intent.percent)
            is FloatingBallSettingsIntent.ChangeSubmergedOffset -> changeSubmergedOffset(intent.offsetDp)
            is FloatingBallSettingsIntent.SelectSubmergedOffset -> selectSubmergedOffset(intent.offsetDp)
        }
    }

    private suspend fun loadSettings() {
        updateState { copy(isLoading = true) }
        runCatchingCancellable {
            val enabled = XRepo.floatingBallEnabled()
            val autoExpand = XRepo.floatingBallAutoExpand()
            val opacity = XRepo.floatingBallOpacityPercent()
            val offset = XRepo.floatingBallSubmergedOffsetDp()
            updateState {
                copy(
                    enabled = enabled,
                    autoExpand = autoExpand,
                    opacityPercent = opacity,
                    submergedOffsetDp = offset,
                    isLoading = false,
                )
            }
        }.onFailure { e ->
            Logger.w(TAG, "load failed ${e.message}")
            updateState { copy(isLoading = false) }
        }
    }

    private suspend fun ensurePermission(permission: Permission): Boolean {
        val pm = requireService<PermissionManager>()
        return pm.status(permission) == PermissionState.GRANTED ||
                pm.request(permission).finalState == PermissionState.GRANTED
    }

    private suspend fun toggleEnabled(enabled: Boolean) {
        if (enabled && !ensurePermission(Permission.OVERLAY)) {
            updateState { copy(enabled = false) }
            XRepo.setFloatingBallEnabled(false)
            return
        }
        updateState { copy(enabled = enabled) }
        XRepo.setFloatingBallEnabled(enabled)
    }

    private suspend fun toggleAutoExpand(enabled: Boolean) {
        updateState { copy(autoExpand = enabled) }
        XRepo.setFloatingBallAutoExpand(enabled)
    }

    private fun changeOpacity(percent: Int) {
        val sanitized = percent.coerceIn(10, 100)
        updateState { copy(opacityPercent = sanitized) }
        XRepo.floatingBallOpacityPercentSetting.value = sanitized
    }

    private suspend fun selectOpacity(percent: Int) {
        val sanitized = percent.coerceIn(10, 100)
        updateState { copy(opacityPercent = sanitized) }
        XRepo.setFloatingBallOpacityPercent(sanitized)
    }

    private fun changeSubmergedOffset(offsetDp: Int) {
        val sanitized = offsetDp.coerceIn(0, 35)
        updateState { copy(submergedOffsetDp = sanitized) }
        XRepo.floatingBallSubmergedOffsetDpSetting.value = sanitized
    }

    private suspend fun selectSubmergedOffset(offsetDp: Int) {
        val sanitized = offsetDp.coerceIn(0, 35)
        updateState { copy(submergedOffsetDp = sanitized) }
        XRepo.setFloatingBallSubmergedOffsetDp(sanitized)
    }

    companion object {
        private const val TAG = "niki914_zafiro_FloatingBallSettingsViewModel"
    }
}
