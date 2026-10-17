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
import kotlin.math.roundToInt

/**
 * 运行控制页：运行上限（空闲超时 / 重试次数）+ 复读检测（灵敏度）+ 运行偏好（屏幕常亮）。
 */
private const val IDLE_TIMEOUT_ROW_ID = "runtime.idle_timeout"
private const val RETRY_ATTEMPTS_ROW_ID = "runtime.retry_attempts"
private const val REPETITION_SENSITIVITY_ROW_ID = "runtime.repetition_sensitivity"
private const val KEEP_SCREEN_ON_ROW_ID = "runtime.keep_screen_on"
private const val COMPACTION_THRESHOLD_ROW_ID = "runtime.compaction_threshold"

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
                    SettingsRowSpec.Slider(
                        id = IDLE_TIMEOUT_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_idle_timeout),
                        summary = stringResource(R.string.ui_settings_general_idle_timeout_summary),
                        value = uiState.idleTimeoutSeconds.toFloat(),
                        valueRange = 0f..120f,
                        steps = 0,
                        valueLabel = idleTimeoutLabel(uiState.idleTimeoutSeconds),
                        startLabel = stringResource(R.string.ui_settings_general_idle_timeout_off),
                        endLabel = "120s",
                    ),
                    SettingsRowSpec.Slider(
                        id = RETRY_ATTEMPTS_ROW_ID,
                        title = stringResource(R.string.ui_settings_general_retry_attempts),
                        summary = stringResource(R.string.ui_settings_general_retry_summary),
                        value = uiState.retryMaxAttempts.toFloat(),
                        valueRange = 0f..5f,
                        steps = 0,
                        valueLabel = "${uiState.retryMaxAttempts}",
                        startLabel = "0",
                        endLabel = "5",
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
            SettingsSectionSpec(
                title = stringResource(R.string.ui_settings_runtime_group_compaction),
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Slider(
                        id = COMPACTION_THRESHOLD_ROW_ID,
                        title = stringResource(R.string.ui_settings_runtime_compaction_threshold),
                        summary = stringResource(R.string.ui_settings_runtime_compaction_threshold_summary),
                        value = uiState.compactionThresholdPercent.toFloat(),
                        valueRange = 0f..95f,
                        steps = 0,
                        valueLabel = compactionThresholdLabel(uiState.compactionThresholdPercent),
                        startLabel = stringResource(R.string.ui_settings_runtime_compaction_off),
                        endLabel = "95%",
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

                is SettingsRowAction.SliderChanged ->
                    when (action.id) {
                        IDLE_TIMEOUT_ROW_ID -> {
                            val rounded = action.value.roundToInt().coerceIn(0, 120).toLong()
                            viewModel.sendIntent(RuntimeControlIntent.ChangeIdleTimeout(rounded))
                        }

                        RETRY_ATTEMPTS_ROW_ID -> {
                            val attempts = action.value.roundToInt().coerceIn(0, 5)
                            viewModel.sendIntent(RuntimeControlIntent.ChangeRetryMaxAttempts(attempts))
                        }

                        COMPACTION_THRESHOLD_ROW_ID -> {
                            val percent = sanitizeCompactionThreshold(action.value)
                            viewModel.sendIntent(RuntimeControlIntent.ChangeCompactionThreshold(percent))
                        }
                    }

                is SettingsRowAction.SliderCommit ->
                    when (action.id) {
                        IDLE_TIMEOUT_ROW_ID -> {
                            val rounded = action.value.roundToInt().coerceIn(0, 120).toLong()
                            viewModel.sendIntent(RuntimeControlIntent.SelectIdleTimeout(rounded))
                        }

                        RETRY_ATTEMPTS_ROW_ID -> {
                            val attempts = action.value.roundToInt().coerceIn(0, 5)
                            viewModel.sendIntent(RuntimeControlIntent.SelectRetryMaxAttempts(attempts))
                        }

                        COMPACTION_THRESHOLD_ROW_ID -> {
                            val percent = sanitizeCompactionThreshold(action.value)
                            viewModel.sendIntent(RuntimeControlIntent.SelectCompactionThreshold(percent))
                        }
                    }

                else -> Unit
            }
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

private fun sanitizeCompactionThreshold(value: Float): Int {
    val rounded = value.roundToInt()
    return if (rounded < 15) 0 else rounded.coerceIn(30, 95)
}

@Composable
private fun idleTimeoutLabel(seconds: Long): String {
    return if (seconds == 0L) {
        stringResource(R.string.ui_settings_general_idle_timeout_off)
    } else {
        "${seconds}s"
    }
}

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

@Composable
private fun compactionThresholdLabel(percent: Int): String {
    return if (percent == 0) {
        stringResource(R.string.ui_settings_runtime_compaction_off)
    } else {
        "$percent%"
    }
}

