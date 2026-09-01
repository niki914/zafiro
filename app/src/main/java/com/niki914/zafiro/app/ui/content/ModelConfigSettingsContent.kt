package com.niki914.zafiro.app.ui.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.component.SettingExpandableTextItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ConfigureIntent
import com.niki914.zafiro.app.ui.model.ConfigureScene
import com.niki914.zafiro.app.ui.model.ConfigureViewModel
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import kotlinx.coroutines.delay

/**
 * Model Configuration 一级页：System Prompt（防抖自动保存）+ Saved Configuration 列表。
 * 编辑/新建走 SavedConfigDetailPage 二级页。
 */
@Composable
fun ModelConfigSettingsContent(
    onBack: () -> Unit,
    onOpenProviderPick: () -> Unit,
    onOpenConfigDetail: (configId: String, configName: String) -> Unit,
) {
    val viewModel = pageViewModel<ConfigureViewModel>(
        key = "settings-configure",
    )
    val uiState by viewModel.uiStateFlow.collectAsState()
    var pendingDeleteConfigId by rememberSaveable { mutableStateOf<String?>(null) }
    // 首次加载 document.prompt 时不触发自动保存
    var promptLoadSettled by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                scene = ConfigureScene.SettingsEdit,
            ),
        )
    }

    // prompt 防抖自动保存：停止输入 500ms 后持久化
    LaunchedEffect(uiState.promptInput) {
        if (!promptLoadSettled) {
            promptLoadSettled = true
            return@LaunchedEffect
        }
        delay(PROMPT_AUTO_SAVE_DEBOUNCE_MILLIS)
        viewModel.sendIntent(ConfigureIntent.SavePrompt)
    }

    EditableSettingsDetailChrome(
        isCreating = false,
        hasUnsavedChanges = { false },
        onDiscardChanges = onBack,
        rightAction = TopBarActionSpec(
            icon = Icons.Default.Add,
            contentDescription = stringResource(R.string.ui_settings_configure_add),
            onClick = onOpenProviderPick,
        ),
    ) {
        SettingsListPageContent {
            SettingsGroupCard {
                var promptExpanded by rememberSaveable { mutableStateOf(false) }
                SettingExpandableTextItem(
                    title = stringResource(R.string.ui_settings_configure_prompt_label),
                    value = uiState.promptInput,
                    onValueChange = { value ->
                        viewModel.sendIntent(ConfigureIntent.UpdatePrompt(value))
                    },
                    placeholder = stringResource(R.string.ui_settings_configure_prompt_placeholder),
                    description = null,
                    minLines = 3,
                    maxLines = 8,
                    expanded = promptExpanded,
                    onExpandedChange = { promptExpanded = it },
                )
            }

            SavedConfigurationBlock(
                configs = uiState.savedConfigs.map { summary ->
                    summary.copy(isActive = summary.id == uiState.activeConfigId)
                },
                onEditClick = { configId ->
                    val summary = uiState.savedConfigs.firstOrNull { it.id == configId }
                    if (summary != null) {
                        onOpenConfigDetail(configId, summary.name)
                    }
                },
                onActivateClick = { configId ->
                    viewModel.sendIntent(ConfigureIntent.ActivateConfig(configId))
                },
                onDeleteRequest = { configId ->
                    pendingDeleteConfigId = configId
                },
            )
        }
    }

    ConfirmationLiquidDialog(
        visible = pendingDeleteConfigId != null,
        onDismissRequest = { pendingDeleteConfigId = null },
        title = stringResource(R.string.ui_settings_saved_configuration_delete_title),
        text = stringResource(R.string.ui_settings_saved_configuration_delete_text),
        negativeButtonText = stringResource(R.string.dialog_cancel),
        positiveButtonText = stringResource(R.string.dialog_confirm_delete),
        onNegativeClick = { pendingDeleteConfigId = null },
        onPositiveClick = {
            pendingDeleteConfigId?.let { configId ->
                viewModel.sendIntent(ConfigureIntent.DeleteConfig(configId))
            }
            pendingDeleteConfigId = null
        },
    )
}

private const val PROMPT_AUTO_SAVE_DEBOUNCE_MILLIS = 500L
