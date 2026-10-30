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
import com.niki914.zafiro.app.ui.model.FloatingBallSettingsIntent
import com.niki914.zafiro.app.ui.model.FloatingBallSettingsViewModel
import kotlin.math.roundToInt

private const val FLOATING_BALL_ENABLE_ROW_ID = "floating_ball.enable"
private const val FLOATING_BALL_AUTO_EXPAND_ROW_ID = "floating_ball.auto_expand"
private const val FLOATING_BALL_OPACITY_ROW_ID = "floating_ball.opacity"
private const val FLOATING_BALL_SUBMERGED_OFFSET_ROW_ID = "floating_ball.submerged_offset"

@Composable
fun FloatingBallSettingsContent(
    viewModel: FloatingBallSettingsViewModel = pageViewModel(),
) {
    val uiState by viewModel.uiStateFlow.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.sendIntent(FloatingBallSettingsIntent.Load)
    }

    val spec = SettingsPageSpec(
        description = null,
        sections = listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Toggle(
                        id = FLOATING_BALL_ENABLE_ROW_ID,
                        title = stringResource(R.string.ui_settings_floating_ball_enable),
                        summary = stringResource(R.string.ui_settings_general_floating_ball_summary),
                        checked = uiState.enabled,
                    ),
                    SettingsRowSpec.Toggle(
                        id = FLOATING_BALL_AUTO_EXPAND_ROW_ID,
                        title = stringResource(R.string.ui_settings_floating_ball_auto_expand),
                        summary = stringResource(R.string.ui_settings_general_floating_ball_auto_expand_summary),
                        checked = uiState.autoExpand,
                        enabled = uiState.enabled,
                    ),
                    SettingsRowSpec.Slider(
                        id = FLOATING_BALL_OPACITY_ROW_ID,
                        title = stringResource(R.string.ui_settings_floating_ball_opacity),
                        summary = stringResource(R.string.ui_settings_floating_ball_opacity_summary),
                        value = uiState.opacityPercent.toFloat(),
                        valueRange = 10f..100f,
                        steps = 0,
                        valueLabel = "${uiState.opacityPercent}%",
                        startLabel = "10%",
                        endLabel = "100%",
                        enabled = uiState.enabled,
                    ),
                    SettingsRowSpec.Slider(
                        id = FLOATING_BALL_SUBMERGED_OFFSET_ROW_ID,
                        title = stringResource(R.string.ui_settings_floating_ball_submerged_offset),
                        summary = stringResource(R.string.ui_settings_floating_ball_submerged_offset_summary),
                        value = uiState.submergedOffsetDp.toFloat(),
                        valueRange = 0f..35f,
                        steps = 0,
                        valueLabel = "${uiState.submergedOffsetDp} dp",
                        startLabel = "0 dp",
                        endLabel = "35 dp",
                        enabled = uiState.enabled,
                    ),
                ),
            ),
        ),
    )

    SettingsSpecPageContent(
        spec = spec,
        onAction = { action ->
            when (action) {
                is SettingsRowAction.ToggleChanged -> when (action.id) {
                    FLOATING_BALL_ENABLE_ROW_ID ->
                        viewModel.sendIntent(FloatingBallSettingsIntent.ToggleEnabled(action.checked))
                    FLOATING_BALL_AUTO_EXPAND_ROW_ID ->
                        viewModel.sendIntent(FloatingBallSettingsIntent.ToggleAutoExpand(action.checked))
                }
                is SettingsRowAction.SliderChanged -> when (action.id) {
                    FLOATING_BALL_OPACITY_ROW_ID ->
                        viewModel.sendIntent(FloatingBallSettingsIntent.ChangeOpacity(action.value.roundToInt()))
                    FLOATING_BALL_SUBMERGED_OFFSET_ROW_ID ->
                        viewModel.sendIntent(FloatingBallSettingsIntent.ChangeSubmergedOffset(action.value.roundToInt()))
                }
                is SettingsRowAction.SliderCommit -> when (action.id) {
                    FLOATING_BALL_OPACITY_ROW_ID ->
                        viewModel.sendIntent(FloatingBallSettingsIntent.SelectOpacity(action.value.roundToInt()))
                    FLOATING_BALL_SUBMERGED_OFFSET_ROW_ID ->
                        viewModel.sendIntent(FloatingBallSettingsIntent.SelectSubmergedOffset(action.value.roundToInt()))
                }
                else -> Unit
            }
        },
    )
}
