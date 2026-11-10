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
import com.niki914.uikit.dialog.ZafiroConfirmationDialog
import com.niki914.uikit.dialog.ZafiroSingleChoiceDialog
import com.niki914.uikit.infra.component.SettingNavigationItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ConfigureIntent
import com.niki914.zafiro.app.ui.model.ConfigureScene
import com.niki914.zafiro.app.ui.model.ConfigureViewModel
import com.niki914.zafiro.app.ui.model.FallbackChainIntent
import com.niki914.zafiro.app.ui.model.FallbackChainViewModel
import com.niki914.zafiro.app.ui.model.SavedConfigSummary
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import com.niki914.zafiro.app.ui.settings.form.EditableSettingsDetailChrome

/**
 * Model Configuration 一级页：System Prompt 卡片（点击进整页编辑）+ Saved Configuration 列表。
 * 编辑/新建走 SavedConfigDetailPage 二级页，Prompt 编辑走 PromptEditPage 二级页。
 */
@Composable
fun ModelConfigSettingsContent(
    onBack: () -> Unit,
    onOpenProviderPick: () -> Unit,
    onOpenConfigDetail: (configId: String, configName: String) -> Unit,
    onOpenPromptEdit: () -> Unit,
) {
    val viewModel = pageViewModel<ConfigureViewModel>(
        key = "settings-configure",
    )
    val uiState by viewModel.uiStateFlow.collectAsState()
    var pendingDeleteConfigId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.sendIntent(
            ConfigureIntent.Initialize(
                scene = ConfigureScene.SettingsEdit,
            ),
        )
    }

    EditableSettingsDetailChrome(
        isCreating = false,
        hasUnsavedChanges = { false },
        onDiscardChanges = onBack,
        rightAction = TopBarActionSpec(
            icon = Icons.Default.Add,
            contentDescription = "Add Button",
            onClick = onOpenProviderPick,
        ),
    ) {
        SettingsListPageContent {
            SettingsGroupCard {
                SettingNavigationItem(
                    title = stringResource(R.string.ui_settings_configure_prompt_label),
                    summary = null,
                    onClick = onOpenPromptEdit,
                )
            }

            FallbackModelsBlock()

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

    ZafiroConfirmationDialog(
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

@Composable
private fun FallbackModelsBlock() {
    val viewModel = pageViewModel<FallbackChainViewModel>(key = "settings-fallback")
    val uiState by viewModel.uiStateFlow.collectAsState()
    var editingSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    val noneLabel = stringResource(R.string.fallback_slot_none)
    val options = remember(uiState.configs, noneLabel) {
        listOf(SavedConfigSummary(id = "", name = noneLabel, modelId = "", isActive = false)) + uiState.configs
    }

    LaunchedEffect(viewModel) { viewModel.sendIntent(FallbackChainIntent.Load) }

    SettingsGroupCard {
        uiState.slots.forEachIndexed { index, configId ->
            if (index > 0) SettingsItemDivider()
            SettingNavigationItem(
                title = stringResource(R.string.fallback_slot_title, index + 1),
                summary = if (index == 0) stringResource(R.string.fallback_section_summary) else null,
                currentState = options.firstOrNull { it.id == configId }?.name ?: noneLabel,
                onClick = { editingSlot = index },
            )
        }
    }

    val slot = editingSlot
    ZafiroSingleChoiceDialog(
        visible = slot != null,
        onDismissRequest = { editingSlot = null },
        title = stringResource(R.string.fallback_dialog_title, (slot ?: 0) + 1),
        options = options,
        selectedId = slot?.let { uiState.slots.getOrNull(it) }.orEmpty(),
        optionId = { it.id },
        optionLabel = { if (it.modelId.isBlank()) it.name else "${it.name} (${it.modelId})" },
        onSelect = {
            slot?.let { index -> viewModel.sendIntent(FallbackChainIntent.SetSlot(index, it.id)) }
            editingSlot = null
        },
    )
}
