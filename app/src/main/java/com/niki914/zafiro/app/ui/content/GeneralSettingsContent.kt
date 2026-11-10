package com.niki914.zafiro.app.ui.content

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.niki914.uikit.dialog.ZafiroConfirmationDialog
import com.niki914.zafiro.backup.BackupArchive
import com.niki914.zafiro.backup.BackupManager
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.GeneralSettingsIntent
import com.niki914.zafiro.app.ui.model.GeneralSettingsViewModel
import com.niki914.zafiro.app.ui.nav.FloatingBallSettingsPage
import com.niki914.zafiro.app.ui.nav.ZafiroPage

/**
 * General Settings：悬浮与通知、对话与交互 2 个分组。
 * 容器使用 SettingsSpecPageContent + GroupedCard，开关状态由 ViewModel 驱动。
 */
private const val FLOATING_BALL_NAV_ROW_ID = "general.floating_ball_nav"
private const val RESIDENT_NOTIFICATION_ROW_ID = "general.resident_notification"
private const val TURN_NOTIFICATION_ROW_ID = "general.turn_notification"
private const val LOAD_LAST_ROW_ID = "general.load_last"
private const val ALWAYS_SHOW_ACTIONS_ROW_ID = "general.always_show_message_actions"
private const val ALWAYS_SHOW_DETAILED_TOOL_OUTPUT_ROW_ID =
    "general.always_show_detailed_tool_output"
private const val VOICE_ASSISTANT_ISOLATED_SESSION_ROW_ID =
    "general.voice_assistant_isolated_session"
private const val BACKUP_CREATE_ROW_ID = "general.backup_create"
private const val BACKUP_RESTORE_ROW_ID = "general.backup_restore"

@Composable
fun GeneralSettingsContent(
    viewModel: GeneralSettingsViewModel = pageViewModel(),
    onPush: (ZafiroPage) -> Unit = {},
) {
    val uiState by viewModel.uiStateFlow.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stagedRestore by remember { mutableStateOf<BackupArchive.Summary?>(null) }

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            runCatching { BackupManager.create(context, uri) }
                .onSuccess { Toast.makeText(context, context.getString(R.string.backup_done, it.fileCount), Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(context, context.getString(R.string.backup_failed, it.message ?: "unknown"), Toast.LENGTH_LONG).show() }
        }
    }
    val pickBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { BackupManager.stageRestore(context, uri) }
                .onSuccess { stagedRestore = it }
                .onFailure { Toast.makeText(context, context.getString(R.string.restore_failed, it.message ?: "unknown"), Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.sendIntent(GeneralSettingsIntent.Load)
    }

    val spec = SettingsPageSpec(
        description = null,
        sections = listOf(
            // 分组 1：悬浮与通知
            SettingsSectionSpec(
                title = stringResource(R.string.ui_settings_general_group_floating_notification),
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(
                        id = FLOATING_BALL_NAV_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_floating_ball),
                        summary = stringResource(R.string.ui_settings_general_floating_ball_summary),
                    ),
                    SettingsRowSpec.Toggle(
                        id = RESIDENT_NOTIFICATION_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_resident_notification),
                        summary = stringResource(R.string.ui_settings_general_resident_notification_summary),
                        checked = uiState.residentNotificationEnabled,
                    ),
                    SettingsRowSpec.Toggle(
                        id = TURN_NOTIFICATION_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_turn_notification),
                        summary = stringResource(R.string.ui_settings_general_turn_notification_summary),
                        checked = uiState.turnNotificationEnabled,
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
                    SettingsRowSpec.Toggle(
                        id = ALWAYS_SHOW_DETAILED_TOOL_OUTPUT_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_always_show_detailed_tool_output),
                        summary = stringResource(R.string.ui_settings_general_always_show_detailed_tool_output_summary),
                        checked = uiState.alwaysShowDetailedToolOutput,
                    ),
                    SettingsRowSpec.Toggle(
                        id = VOICE_ASSISTANT_ISOLATED_SESSION_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_voice_assistant_isolated_session),
                        summary = stringResource(R.string.ui_settings_general_voice_assistant_isolated_session_summary),
                        checked = uiState.voiceAssistantIsolatedSession,
                    ),
                ),
            ),
            SettingsSectionSpec(
                title = stringResource(R.string.backup_group_title),
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(
                        id = BACKUP_CREATE_ROW_ID,
                        title = stringResource(R.string.backup_create_title),
                        summary = stringResource(R.string.backup_create_summary),
                    ),
                    SettingsRowSpec.Navigation(
                        id = BACKUP_RESTORE_ROW_ID,
                        title = stringResource(R.string.backup_restore_title),
                        summary = stringResource(R.string.backup_restore_summary),
                    ),
                ),
            ),
            // 分组 4：运行与控制已迁至独立页（RuntimeControl）
        ),
    )

    SettingsSpecPageContent(
        spec = spec,
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate ->
                    when (action.id) {
                        FLOATING_BALL_NAV_ROW_ID -> onPush(FloatingBallSettingsPage)
                        BACKUP_CREATE_ROW_ID -> createBackup.launch(BackupManager.suggestedFileName())
                        BACKUP_RESTORE_ROW_ID -> pickBackup.launch(arrayOf("application/zip", "application/octet-stream"))
                    }

                is SettingsRowAction.ToggleChanged ->
                    when (action.id) {
                        RESIDENT_NOTIFICATION_ROW_ID -> viewModel.sendIntent(
                            GeneralSettingsIntent.ToggleResidentNotification(
                                action.checked
                            )
                        )

                        TURN_NOTIFICATION_ROW_ID -> viewModel.sendIntent(
                            GeneralSettingsIntent.ToggleTurnNotification(
                                action.checked
                            )
                        )

                        LOAD_LAST_ROW_ID -> viewModel.sendIntent(
                            GeneralSettingsIntent.ToggleLoadLastConversation(
                                action.checked
                            )
                        )

                        ALWAYS_SHOW_ACTIONS_ROW_ID -> viewModel.sendIntent(
                            GeneralSettingsIntent.ToggleAlwaysShowMessageActions(
                                action.checked
                            )
                        )

                        ALWAYS_SHOW_DETAILED_TOOL_OUTPUT_ROW_ID -> viewModel.sendIntent(
                            GeneralSettingsIntent.ToggleAlwaysShowDetailedToolOutput(
                                action.checked
                            )
                        )

                        VOICE_ASSISTANT_ISOLATED_SESSION_ROW_ID -> viewModel.sendIntent(
                            GeneralSettingsIntent.ToggleVoiceAssistantIsolatedSession(action.checked)
                        )
                    }

                else -> Unit
            }
        },
    )

    val staged = stagedRestore
    ZafiroConfirmationDialog(
        visible = staged != null,
        onDismissRequest = {
            BackupManager.cancelStagedRestore(context)
            stagedRestore = null
        },
        title = stringResource(R.string.restore_confirm_title),
        text = stringResource(R.string.restore_confirm_text, staged?.appVersion.orEmpty().ifBlank { "?" }, staged?.fileCount ?: 0),
        negativeButtonText = stringResource(R.string.restore_confirm_cancel),
        positiveButtonText = stringResource(R.string.restore_confirm_apply),
        onNegativeClick = {
            BackupManager.cancelStagedRestore(context)
            stagedRestore = null
        },
        onPositiveClick = {
            stagedRestore = null
            restartApp(context)
        },
    )
}

/** Relaunches the app; the staged restore is applied by App.onCreate in the fresh process. */
private fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    if (launch != null) context.startActivity(launch)
    Runtime.getRuntime().exit(0)
}
