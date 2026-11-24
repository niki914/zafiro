package com.niki914.zafiro.app.ui.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.model.EnvContextSettingsIntent
import com.niki914.zafiro.app.ui.model.EnvContextSettingsUiState
import com.niki914.zafiro.app.ui.model.EnvContextSettingsViewModel
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import com.niki914.zafiro.settings.model.RuntimeEnvContextConfig

private const val MASTER_TOGGLE_ROW_ID = "env_context.master_toggle"
private const val BUILTIN_DATE_ROW_ID = "env_context.builtin.date"
private const val BUILTIN_TIME_ROW_ID = "env_context.builtin.time"
private const val BUILTIN_DEVICE_ROW_ID = "env_context.builtin.device"
private const val BUILTIN_VIRTUAL_DISPLAY_ROW_ID = "env_context.builtin.virtual_display"
private const val CUSTOM_VAR_ROW_ID_PREFIX = "env_context.var."

@Composable
fun EnvContextSettingsContent(
    onOpenVariableDetail: (variableKey: String?, isCreating: Boolean) -> Unit,
) {
    val viewModel = pageViewModel<EnvContextSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()

    val pageChromeContribution = remember(onOpenVariableDetail) {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = { onOpenVariableDetail(null, true) },
                contentDescription = "Add Button",
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    LaunchedEffect(Unit) {
        viewModel.sendIntent(EnvContextSettingsIntent.Load)
    }

    EnvContextSettingsContentBody(
        uiState = uiState,
        onOpenVariableDetail = onOpenVariableDetail,
        onMasterToggle = { checked ->
            viewModel.sendIntent(EnvContextSettingsIntent.MasterToggleChanged(checked))
        },
        onKeyToggle = { key, checked ->
            viewModel.sendIntent(EnvContextSettingsIntent.KeyToggleChanged(key, checked))
        },
    )
}

@Composable
private fun EnvContextSettingsContentBody(
    uiState: EnvContextSettingsUiState,
    onOpenVariableDetail: (variableKey: String?, isCreating: Boolean) -> Unit,
    onMasterToggle: (Boolean) -> Unit,
    onKeyToggle: (String, Boolean) -> Unit,
) {
    val spec = envContextSettingsSpec(uiState)

    SettingsSpecPageContent(
        spec = spec,
        onAction = { action ->
            when (action) {
                is SettingsRowAction.ToggleChanged -> {
                    when (action.id) {
                        MASTER_TOGGLE_ROW_ID -> onMasterToggle(action.checked)
                        BUILTIN_DATE_ROW_ID -> onKeyToggle(RuntimeEnvContextConfig.KEY_DATE, action.checked)
                        BUILTIN_TIME_ROW_ID -> onKeyToggle(RuntimeEnvContextConfig.KEY_TIME, action.checked)
                        BUILTIN_DEVICE_ROW_ID -> onKeyToggle(RuntimeEnvContextConfig.KEY_DEVICE, action.checked)
                        BUILTIN_VIRTUAL_DISPLAY_ROW_ID -> onKeyToggle(RuntimeEnvContextConfig.KEY_VIRTUAL_DISPLAY, action.checked)
                        else -> {
                            if (action.id.startsWith(CUSTOM_VAR_ROW_ID_PREFIX)) {
                                val key = action.id.removePrefix(CUSTOM_VAR_ROW_ID_PREFIX)
                                onKeyToggle(key, action.checked)
                            }
                        }
                    }
                }

                is SettingsRowAction.Navigate -> {
                    if (action.id.startsWith(CUSTOM_VAR_ROW_ID_PREFIX)) {
                        val key = action.id.removePrefix(CUSTOM_VAR_ROW_ID_PREFIX)
                        onOpenVariableDetail(key, false)
                    }
                }

                else -> Unit
            }
        },
    )
}

@Composable
private fun envContextSettingsSpec(
    uiState: EnvContextSettingsUiState,
): SettingsPageSpec {
    val masterSection = SettingsSectionSpec(
        layout = SettingsSectionLayout.GroupedCard,
        rows = listOf(
            SettingsRowSpec.Toggle(
                id = MASTER_TOGGLE_ROW_ID,
                title = stringResource(R.string.ui_settings_env_context_master_toggle),
                summary = stringResource(R.string.ui_settings_env_context_master_summary),
                checked = uiState.enabled,
                enabled = !uiState.isLoading,
            )
        ),
    )

    val builtinSection = SettingsSectionSpec(
        title = stringResource(R.string.ui_settings_env_context_group_builtin),
        layout = SettingsSectionLayout.GroupedCard,
        rows = listOf(
            SettingsRowSpec.Toggle(
                id = BUILTIN_DATE_ROW_ID,
                title = stringResource(R.string.ui_settings_env_context_key_date),
                summary = null,
                checked = uiState.keys.contains(RuntimeEnvContextConfig.KEY_DATE),
                enabled = uiState.enabled && !uiState.isLoading,
            ),
            SettingsRowSpec.Toggle(
                id = BUILTIN_TIME_ROW_ID,
                title = stringResource(R.string.ui_settings_env_context_key_time),
                summary = null,
                checked = uiState.keys.contains(RuntimeEnvContextConfig.KEY_TIME),
                enabled = uiState.enabled && !uiState.isLoading,
            ),
            SettingsRowSpec.Toggle(
                id = BUILTIN_DEVICE_ROW_ID,
                title = stringResource(R.string.ui_settings_env_context_key_device),
                summary = null,
                checked = uiState.keys.contains(RuntimeEnvContextConfig.KEY_DEVICE),
                enabled = uiState.enabled && !uiState.isLoading,
            ),
            SettingsRowSpec.Toggle(
                id = BUILTIN_VIRTUAL_DISPLAY_ROW_ID,
                title = stringResource(R.string.ui_settings_env_context_key_virtual_display),
                summary = null,
                checked = uiState.keys.contains(RuntimeEnvContextConfig.KEY_VIRTUAL_DISPLAY),
                enabled = uiState.enabled && !uiState.isLoading,
            ),
        ),
    )

    val customSection = if (uiState.variables.isEmpty()) {
        SettingsSectionSpec(
            title = stringResource(R.string.ui_settings_env_context_group_custom),
            layout = SettingsSectionLayout.GroupedCard,
            rows = listOf(
                SettingsRowSpec.Message(
                    title = stringResource(R.string.ui_settings_env_context_empty_custom),
                    verticalPadding = 12.dp,
                )
            ),
        )
    } else {
        SettingsSectionSpec(
            title = stringResource(R.string.ui_settings_env_context_group_custom),
            layout = SettingsSectionLayout.CardList,
            rows = uiState.variables.entries.map { (key, value) ->
                SettingsRowSpec.ToggleNavigation(
                    id = "$CUSTOM_VAR_ROW_ID_PREFIX$key",
                    title = key,
                    summary = value,
                    checked = key in uiState.keys,
                    enabled = uiState.enabled && !uiState.isLoading,
                )
            },
        )
    }

    return SettingsPageSpec(
        description = stringResource(R.string.ui_settings_env_context_description),
        sections = listOf(
            masterSection,
            builtinSection,
            customSection,
        ),
    )
}
