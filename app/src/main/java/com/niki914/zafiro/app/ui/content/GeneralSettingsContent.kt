package com.niki914.zafiro.app.ui.content

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import com.niki914.permission.Permission
import com.niki914.permission.PermissionState
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.PermissionHolder
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.GeneralSettingsDialog
import com.niki914.zafiro.app.ui.model.GeneralSettingsEffect
import com.niki914.zafiro.app.ui.model.GeneralSettingsIntent
import com.niki914.zafiro.app.ui.model.GeneralSettingsViewModel
import com.niki914.zafiro.app.ui.nav.ThemeSettingsPage
import com.niki914.zafiro.app.ui.nav.ZafiroPage

/**
 * General Settings：外观与语言、悬浮与通知、对话与交互、运行与控制 4 个分组。
 * 容器使用 SettingsSpecPageContent + GroupedCard，弹窗状态由 ViewModel 驱动。
 */
private const val LANGUAGE_ROW_ID = "general.language"
private const val APPEARANCE_ROW_ID = "general.appearance"
private const val FLOATING_BALL_ROW_ID = "general.floating_ball"
private const val RESIDENT_NOTIFICATION_ROW_ID = "general.resident_notification"
private const val LOAD_LAST_ROW_ID = "general.load_last"
private const val ALWAYS_SHOW_ACTIONS_ROW_ID = "general.always_show_message_actions"
private const val IDLE_TIMEOUT_ROW_ID = "general.idle_timeout"
private const val RETRY_ATTEMPTS_ROW_ID = "general.retry_attempts"
private const val KEEP_SCREEN_ON_ROW_ID = "general.keep_screen_on"

private const val LANGUAGE_TAG_ZH_CN = "zh-CN"
private const val LANGUAGE_TAG_ZH_TW = "zh-TW"
private const val LANGUAGE_TAG_EN = "en"
private const val LANGUAGE_TAG_ES = "es"
private const val LANGUAGE_TAG_JA = "ja"

data class LanguageOption(
    /** BCP-47 tag；空串 = 跟随系统。 */
    val tag: String,
    /** 选项自身语言的显示名（跟随系统项用 res）。 */
    val label: String,
)

@Composable
private fun languageOptions(): List<LanguageOption> {
    return listOf(
        LanguageOption(
            tag = "",
            label = stringResource(R.string.ui_settings_general_language_follow_system)
        ),
        LanguageOption(tag = LANGUAGE_TAG_ZH_CN, label = "简体中文"),
        LanguageOption(tag = LANGUAGE_TAG_ZH_TW, label = "繁體中文"),
        LanguageOption(tag = LANGUAGE_TAG_EN, label = "English"),
        LanguageOption(tag = LANGUAGE_TAG_ES, label = "Español"),
        LanguageOption(tag = LANGUAGE_TAG_JA, label = "日本語"),
    )
}

@Composable
fun GeneralSettingsContent(
    onPush: (ZafiroPage) -> Unit = {},
    viewModel: GeneralSettingsViewModel = pageViewModel(),
) {
    val uiState by viewModel.uiStateFlow.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.sendIntent(GeneralSettingsIntent.Load)
    }

    LaunchedEffect(viewModel, context) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                GeneralSettingsEffect.RequestOverlayPermission -> {
                    val result = PermissionHolder.get(context).request(Permission.OVERLAY)
                    val granted = result.finalState == PermissionState.GRANTED
                    viewModel.sendIntent(GeneralSettingsIntent.OnOverlayPermissionResult(granted = granted))
                }
                is GeneralSettingsEffect.ApplyApplicationLocales -> {
                    AppCompatDelegate.setApplicationLocales(
                        if (effect.languageTag.isBlank()) {
                            LocaleListCompat.getEmptyLocaleList()
                        } else {
                            LocaleListCompat.forLanguageTags(effect.languageTag)
                        }
                    )
                }
            }
        }
    }

    val spec = SettingsPageSpec(
        description = null,
        sections = listOf(
            // 分组 1：外观与语言
            SettingsSectionSpec(
                title = stringResource(R.string.ui_settings_general_group_appearance),
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(
                        id = LANGUAGE_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_language),
                    ),
                    SettingsRowSpec.Navigation(
                        id = APPEARANCE_ROW_ID,
                        title = stringResource(R.string.ui_settings_appearance),
                    ),
                ),
            ),
            // 分组 2：悬浮与通知
            SettingsSectionSpec(
                title = stringResource(R.string.ui_settings_general_group_floating_notification),
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Toggle(
                        id = FLOATING_BALL_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_floating_ball),
                        summary = stringResource(R.string.ui_settings_general_floating_ball_summary),
                        checked = uiState.floatingBallEnabled,
                    ),
                    SettingsRowSpec.Toggle(
                        id = RESIDENT_NOTIFICATION_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_resident_notification),
                        summary = stringResource(R.string.ui_settings_general_resident_notification_summary),
                        checked = uiState.residentNotificationEnabled,
                    ),
                ),
            ),
            // 分组 3：对话与交互
            SettingsSectionSpec(
                title = stringResource(R.string.ui_settings_general_group_conversation),
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Toggle(
                        id = LOAD_LAST_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_load_last_conversation),
                        checked = uiState.loadLastConversation,
                    ),
                    SettingsRowSpec.Toggle(
                        id = ALWAYS_SHOW_ACTIONS_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_always_show_message_actions),
                        checked = uiState.alwaysShowMessageActions,
                    ),
                ),
            ),
            // 分组 4：运行与控制
            SettingsSectionSpec(
                title = stringResource(R.string.ui_settings_general_group_runtime),
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(
                        id = IDLE_TIMEOUT_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_idle_timeout),
                        currentState = idleTimeoutLabel(uiState.idleTimeoutSeconds),
                    ),
                    SettingsRowSpec.Navigation(
                        id = RETRY_ATTEMPTS_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_retry_attempts),
                        currentState = retryAttemptsLabel(uiState.retryMaxAttempts),
                    ),
                    SettingsRowSpec.Toggle(
                        id = KEEP_SCREEN_ON_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_keep_screen_on),
                        checked = uiState.keepScreenOn,
                    ),
                ),
            ),
        ),
    )

    SettingsSpecPageContent(
        spec = spec,
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate ->
                    when (action.id) {
                        LANGUAGE_ROW_ID -> viewModel.sendIntent(GeneralSettingsIntent.OpenDialog(GeneralSettingsDialog.Language))
                        APPEARANCE_ROW_ID -> onPush(ThemeSettingsPage)
                        IDLE_TIMEOUT_ROW_ID -> viewModel.sendIntent(GeneralSettingsIntent.OpenDialog(GeneralSettingsDialog.IdleTimeout))
                        RETRY_ATTEMPTS_ROW_ID -> viewModel.sendIntent(GeneralSettingsIntent.OpenDialog(GeneralSettingsDialog.RetryAttempts))
                    }

                is SettingsRowAction.ToggleChanged ->
                    when (action.id) {
                        FLOATING_BALL_ROW_ID -> viewModel.sendIntent(GeneralSettingsIntent.ToggleFloatingBall(action.checked))
                        RESIDENT_NOTIFICATION_ROW_ID -> viewModel.sendIntent(GeneralSettingsIntent.ToggleResidentNotification(action.checked))
                        LOAD_LAST_ROW_ID -> viewModel.sendIntent(GeneralSettingsIntent.ToggleLoadLastConversation(action.checked))
                        ALWAYS_SHOW_ACTIONS_ROW_ID -> viewModel.sendIntent(GeneralSettingsIntent.ToggleAlwaysShowMessageActions(action.checked))
                        KEEP_SCREEN_ON_ROW_ID -> viewModel.sendIntent(GeneralSettingsIntent.ToggleKeepScreenOn(action.checked))
                    }

                else -> Unit
            }
        },
    )

    SingleChoiceLiquidDialog(
        visible = uiState.activeDialog == GeneralSettingsDialog.Language,
        onDismissRequest = { viewModel.sendIntent(GeneralSettingsIntent.DismissDialog) },
        title = stringResource(R.string.ui_settings_general_language),
        options = languageOptions(),
        selectedId = uiState.languageTag,
        optionId = LanguageOption::tag,
        optionLabel = LanguageOption::label,
        onSelect = { option ->
            viewModel.sendIntent(GeneralSettingsIntent.SelectLanguage(option.tag))
        },
    )

    SingleChoiceLiquidDialog(
        visible = uiState.activeDialog == GeneralSettingsDialog.IdleTimeout,
        onDismissRequest = { viewModel.sendIntent(GeneralSettingsIntent.DismissDialog) },
        title = stringResource(R.string.ui_settings_general_idle_timeout),
        hint = stringResource(R.string.ui_settings_general_idle_timeout_summary),
        options = idleTimeoutOptions(),
        selectedId = uiState.idleTimeoutSeconds.toString(),
        optionId = { it.seconds.toString() },
        optionLabel = { it.label },
        onSelect = { option ->
            viewModel.sendIntent(GeneralSettingsIntent.SelectIdleTimeout(option.seconds))
        },
    )

    SingleChoiceLiquidDialog(
        visible = uiState.activeDialog == GeneralSettingsDialog.RetryAttempts,
        onDismissRequest = { viewModel.sendIntent(GeneralSettingsIntent.DismissDialog) },
        title = stringResource(R.string.ui_settings_general_retry_attempts),
        hint = stringResource(R.string.ui_settings_general_retry_summary),
        options = retryAttemptsOptions(),
        selectedId = uiState.retryMaxAttempts.toString(),
        optionId = { it.toString() },
        optionLabel = { it.toString() },
        onSelect = { option ->
            viewModel.sendIntent(GeneralSettingsIntent.SelectRetryMaxAttempts(option))
        },
    )
}

data class IdleTimeoutOption(
    /** 持久化值：0 = 不超时。 */
    val seconds: Long,
    val label: String,
)

@Composable
private fun idleTimeoutLabel(seconds: Long): String {
    return idleTimeoutOptions().firstOrNull { it.seconds == seconds }?.label
        ?: "$seconds"
}

@Composable
private fun idleTimeoutOptions(): List<IdleTimeoutOption> {
    val offLabel = stringResource(R.string.ui_settings_general_idle_timeout_off)
    return listOf(
        IdleTimeoutOption(seconds = 0L, label = offLabel),
        IdleTimeoutOption(seconds = 30L, label = "30s"),
        IdleTimeoutOption(seconds = 60L, label = "60s"),
        IdleTimeoutOption(seconds = 90L, label = "90s"),
        IdleTimeoutOption(seconds = 120L, label = "120s"),
    )
}

private fun retryAttemptsLabel(attempts: Int): String = attempts.toString()

private fun retryAttemptsOptions(): List<Int> = listOf(0, 1, 2, 3, 5)
