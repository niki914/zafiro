package com.niki914.zafiro.app.ui.model

import androidx.annotation.StringRes
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.business.permission.Permission
import com.niki914.zafiro.business.permission.PermissionManager
import com.niki914.zafiro.business.permission.PermissionState
import com.niki914.zafiro.repo.LocalSettingsDefaults
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService
import com.niki914.zafiro.settings.model.RuntimeExecutionRule
import com.niki914.zafiro.settings.model.RuntimeExecutionRuleEnabledMode

/**
 * onboarding 配置页的默认高危命令列表：进了这个页面并完成，就用这份列表覆盖内置规则；
 * 没进过页面才保留 [LocalSettingsDefaults] 的默认规则。两者是同一个 rule（共用 id 与名字）。
 */
private val DefaultSecurityPatterns = listOf("rm", "pm", "su", "dd")

data class OnboardingPreferencesUiState(
    val accessibilityGranted: Boolean = false,
    val overlayGranted: Boolean = false,
    val notificationGranted: Boolean = false,
    val storageGranted: Boolean = false,
    val securityPatterns: List<String> = DefaultSecurityPatterns,
    val addRuleDialogVisible: Boolean = false,
    val newRuleText: String = "",
    val isRequesting: Boolean = false,
) {
    /** Phone Use 行 = 无障碍 + 悬浮窗都就绪。 */
    val phoneUseGranted: Boolean
        get() = accessibilityGranted && overlayGranted

    /** 四项全就绪：底部主按钮从「一键开启」切成「进入 Zafiro」。 */
    val allGranted: Boolean
        get() = phoneUseGranted && notificationGranted && storageGranted
}

sealed interface OnboardingPreferencesIntent {
    data object Load : OnboardingPreferencesIntent
    data object RequestPhoneUse : OnboardingPreferencesIntent
    data object RequestFloatingBall : OnboardingPreferencesIntent
    data object RequestResidentNotification : OnboardingPreferencesIntent
    data object RequestFileAccess : OnboardingPreferencesIntent
    data object RequestAll : OnboardingPreferencesIntent
    data object ShowAddRuleDialog : OnboardingPreferencesIntent
    data object DismissAddRuleDialog : OnboardingPreferencesIntent
    data class SetNewRuleText(val text: String) : OnboardingPreferencesIntent
    data object ConfirmAddRule : OnboardingPreferencesIntent
    data class RemoveRule(val pattern: String) : OnboardingPreferencesIntent
    data object Complete : OnboardingPreferencesIntent
}

sealed interface OnboardingPreferencesEffect {
    data class ShowToast(@StringRes val messageRes: Int) : OnboardingPreferencesEffect
    data object NavigateToHome : OnboardingPreferencesEffect
}

/**
 * onboarding 权限配置：每行都是「点一下 → 申请 → 结果决定行状态」，不建模任何前置依赖。
 * 权限已就绪时 [ensure] 静默短路，不发请求。
 */
class OnboardingPreferencesViewModel :
    ComposeMVIViewModel<OnboardingPreferencesIntent, OnboardingPreferencesUiState, OnboardingPreferencesEffect>() {

    override fun initUiState(): OnboardingPreferencesUiState = OnboardingPreferencesUiState()

    override suspend fun handleIntent(intent: OnboardingPreferencesIntent) {
        when (intent) {
            OnboardingPreferencesIntent.Load -> refreshPermissions()
            OnboardingPreferencesIntent.RequestPhoneUse -> requestPhoneUse()
            OnboardingPreferencesIntent.RequestFloatingBall -> requestFloatingBall()
            OnboardingPreferencesIntent.RequestResidentNotification -> requestResidentNotification()
            OnboardingPreferencesIntent.RequestFileAccess -> requestFileAccess()
            OnboardingPreferencesIntent.RequestAll -> requestAll()
            OnboardingPreferencesIntent.ShowAddRuleDialog ->
                updateState { copy(addRuleDialogVisible = true, newRuleText = "") }
            OnboardingPreferencesIntent.DismissAddRuleDialog ->
                updateState { copy(addRuleDialogVisible = false, newRuleText = "") }
            is OnboardingPreferencesIntent.SetNewRuleText ->
                updateState { copy(newRuleText = intent.text) }
            OnboardingPreferencesIntent.ConfirmAddRule -> confirmAddRule()
            is OnboardingPreferencesIntent.RemoveRule ->
                updateState { copy(securityPatterns = securityPatterns - intent.pattern) }
            OnboardingPreferencesIntent.Complete -> complete()
        }
    }

    private suspend fun refreshPermissions() {
        val pm = requireService<PermissionManager>()
        updateState {
            copy(
                accessibilityGranted = pm.status(Permission.ACCESSIBILITY) == PermissionState.GRANTED,
                overlayGranted = pm.status(Permission.OVERLAY) == PermissionState.GRANTED,
                notificationGranted = pm.status(Permission.NOTIFICATION) == PermissionState.GRANTED,
                storageGranted = pm.status(Permission.STORAGE) == PermissionState.GRANTED,
            )
        }
    }

    /** 已授权直接返回 true（不发请求）；否则跑默认链，结果为准。 */
    private suspend fun ensure(permission: Permission): Boolean {
        val pm = requireService<PermissionManager>()
        if (pm.status(permission) == PermissionState.GRANTED) return true
        return pm.request(permission).finalState == PermissionState.GRANTED
    }

    private suspend fun requestPhoneUse() {
        updateState { copy(isRequesting = true) }
        val accessibility = ensure(Permission.ACCESSIBILITY)
        val overlay = ensure(Permission.OVERLAY)
        refreshPermissions()
        updateState { copy(isRequesting = false) }
        if (!accessibility || !overlay) toastDenied()
    }

    private suspend fun requestFloatingBall() {
        updateState { copy(isRequesting = true) }
        val granted = ensure(Permission.OVERLAY)
        if (granted) XRepo.setFloatingBallEnabled(true)
        refreshPermissions()
        updateState { copy(isRequesting = false) }
        if (!granted) toastDenied()
    }

    private suspend fun requestResidentNotification() {
        updateState { copy(isRequesting = true) }
        val granted = ensure(Permission.NOTIFICATION)
        if (granted) XRepo.setResidentNotificationEnabled(true)
        refreshPermissions()
        updateState { copy(isRequesting = false) }
        if (!granted) toastDenied()
    }

    private suspend fun requestFileAccess() {
        updateState { copy(isRequesting = true) }
        val granted = ensure(Permission.STORAGE)
        refreshPermissions()
        updateState { copy(isRequesting = false) }
        if (!granted) toastDenied()
    }

    /** 一键开启：顺序跑四项的默认链，完事重查。 */
    private suspend fun requestAll() {
        updateState { copy(isRequesting = true) }
        val pm = requireService<PermissionManager>()
        listOf(
            Permission.ACCESSIBILITY,
            Permission.OVERLAY,
            Permission.NOTIFICATION,
            Permission.STORAGE,
        ).forEach { permission ->
            if (pm.status(permission) != PermissionState.GRANTED) {
                pm.request(permission)
            }
        }
        refreshPermissions()
        updateState { copy(isRequesting = false) }
        if (!uiStateFlow.value.allGranted) toastDenied()
    }

    private fun confirmAddRule() {
        val text = uiStateFlow.value.newRuleText.trim()
        updateState {
            if (text.isEmpty() || text in securityPatterns) {
                copy(addRuleDialogVisible = false, newRuleText = "")
            } else {
                copy(
                    securityPatterns = securityPatterns + text,
                    addRuleDialogVisible = false,
                    newRuleText = "",
                )
            }
        }
    }

    private suspend fun complete() {
        XRepo.executionRules.save(
            RuntimeExecutionRule(
                id = LocalSettingsDefaults.DEFAULT_EXECUTION_RULE_ID,
                name = LocalSettingsDefaults.DEFAULT_EXECUTION_RULE_NAME,
                enabledMode = RuntimeExecutionRuleEnabledMode.CONFIRM,
                patterns = uiStateFlow.value.securityPatterns,
            )
        )
        if (!XRepo.onboardingCompleted()) {
            XRepo.setOnboardingCompleted(true)
        }
        sendEffect(OnboardingPreferencesEffect.NavigateToHome)
    }

    private suspend fun toastDenied() {
        sendEffect(OnboardingPreferencesEffect.ShowToast(R.string.ui_onboard_preferences_toast_denied))
    }
}
