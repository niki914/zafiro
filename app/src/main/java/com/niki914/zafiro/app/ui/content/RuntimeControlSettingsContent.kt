package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.RuntimeControlDialog
import com.niki914.zafiro.app.ui.model.RuntimeControlIntent
import com.niki914.zafiro.app.ui.model.RuntimeControlViewModel
import com.niki914.zafiro.settings.model.RepetitionSensitivity

/**
 * 运行控制页：运行上限（空闲超时 / 重试次数）+ 复读检测（灵敏度）+ 运行偏好（屏幕常亮）。
 */
private const val IDLE_TIMEOUT_ROW_ID = "runtime.idle_timeout"
private const val RETRY_ATTEMPTS_ROW_ID = "runtime.retry_attempts"
private const val REPETITION_SENSITIVITY_ROW_ID = "runtime.repetition_sensitivity"
private const val KEEP_SCREEN_ON_ROW_ID = "runtime.keep_screen_on"

@Composable
fun RuntimeControlSettingsContent(
    viewModel: RuntimeControlViewModel = pageViewModel(),
) {
    val uiState by viewModel.uiStateFlow.collectAsState()
    val sensitivityLabels = repetitionSensitivityLabels()

    LaunchedEffect(Unit) {
        viewModel.sendIntent(RuntimeControlIntent.Load)
    }

    val spec = SettingsPageSpec(
        description = null,
        sections = listOf(
            SettingsSectionSpec(
                title = stringResource(R.string.ui_settings_runtime_group_network),
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
                ),
            ),
            SettingsSectionSpec(
                title = stringResource(R.string.ui_settings_runtime_group_stability),
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Navigation(
                        id = REPETITION_SENSITIVITY_ROW_ID,
                        title = stringResource(R.string.ui_settings_runtime_repetition_detection),
                        currentState = sensitivityLabels.getValue(uiState.repetitionSensitivity),
                    ),
                    SettingsRowSpec.Toggle(
                        id = KEEP_SCREEN_ON_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_keep_screen_on),
                        summary = stringResource(R.string.ui_settings_general_keep_screen_on_summary),
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
                        IDLE_TIMEOUT_ROW_ID -> viewModel.sendIntent(
                            RuntimeControlIntent.OpenDialog(RuntimeControlDialog.IdleTimeout)
                        )

                        RETRY_ATTEMPTS_ROW_ID -> viewModel.sendIntent(
                            RuntimeControlIntent.OpenDialog(RuntimeControlDialog.RetryAttempts)
                        )

                        REPETITION_SENSITIVITY_ROW_ID -> viewModel.sendIntent(
                            RuntimeControlIntent.OpenDialog(
                                RuntimeControlDialog.RepetitionSensitivity
                            )
                        )
                    }

                is SettingsRowAction.ToggleChanged ->
                    when (action.id) {
                        KEEP_SCREEN_ON_ROW_ID -> viewModel.sendIntent(
                            RuntimeControlIntent.ToggleKeepScreenOn(action.checked)
                        )
                    }

                else -> Unit
            }
        },
    )

    SingleChoiceLiquidDialog(
        visible = uiState.activeDialog == RuntimeControlDialog.IdleTimeout,
        onDismissRequest = { viewModel.sendIntent(RuntimeControlIntent.DismissDialog) },
        title = stringResource(R.string.ui_settings_general_idle_timeout),
        hint = stringResource(R.string.ui_settings_general_idle_timeout_summary),
        options = idleTimeoutOptions(),
        selectedId = uiState.idleTimeoutSeconds.toString(),
        optionId = { it.seconds.toString() },
        optionLabel = { it.label },
        onSelect = { option ->
            viewModel.sendIntent(RuntimeControlIntent.SelectIdleTimeout(option.seconds))
        },
    )

    SingleChoiceLiquidDialog(
        visible = uiState.activeDialog == RuntimeControlDialog.RetryAttempts,
        onDismissRequest = { viewModel.sendIntent(RuntimeControlIntent.DismissDialog) },
        title = stringResource(R.string.ui_settings_general_retry_attempts),
        hint = stringResource(R.string.ui_settings_general_retry_summary),
        options = retryAttemptsOptions(),
        selectedId = uiState.retryMaxAttempts.toString(),
        optionId = { it.toString() },
        optionLabel = { it.toString() },
        onSelect = { option ->
            viewModel.sendIntent(RuntimeControlIntent.SelectRetryMaxAttempts(option))
        },
    )

    SingleChoiceLiquidDialog(
        visible = uiState.activeDialog == RuntimeControlDialog.RepetitionSensitivity,
        onDismissRequest = { viewModel.sendIntent(RuntimeControlIntent.DismissDialog) },
        title = stringResource(R.string.ui_settings_runtime_repetition_detection),
        hint = stringResource(R.string.ui_settings_runtime_repetition_summary),
        options = RepetitionSensitivity.entries,
        selectedId = uiState.repetitionSensitivity.wireValue,
        optionId = { it.wireValue },
        optionLabel = { sensitivityLabels.getValue(it) },
        onSelect = { option ->
            viewModel.sendIntent(RuntimeControlIntent.SelectRepetitionSensitivity(option))
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

@Composable
private fun repetitionSensitivityLabels(): Map<RepetitionSensitivity, String> = mapOf(
    RepetitionSensitivity.Off to stringResource(R.string.ui_settings_runtime_sensitivity_off),
    RepetitionSensitivity.Relaxed to stringResource(R.string.ui_settings_runtime_sensitivity_relaxed),
    RepetitionSensitivity.Loose to stringResource(R.string.ui_settings_runtime_sensitivity_loose),
    RepetitionSensitivity.Standard to stringResource(R.string.ui_settings_runtime_sensitivity_standard),
    RepetitionSensitivity.Strict to stringResource(R.string.ui_settings_runtime_sensitivity_strict),
    RepetitionSensitivity.Stricter to stringResource(R.string.ui_settings_runtime_sensitivity_stricter),
    RepetitionSensitivity.Strictest to stringResource(R.string.ui_settings_runtime_sensitivity_strictest),
)
