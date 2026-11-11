package com.niki914.zafiro.app.ui.content

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.dialog.ZafiroConfirmationDialog
import com.niki914.uikit.dialog.ZafiroSingleChoiceDialog
import com.niki914.uikit.infra.component.SettingNavigationItem
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
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
import com.niki914.zafiro.app.ui.model.AgentEditorEffect
import com.niki914.zafiro.app.ui.model.AgentEditorIntent
import com.niki914.zafiro.app.ui.model.AgentEditorViewModel
import com.niki914.zafiro.app.ui.model.AgentsIntent
import com.niki914.zafiro.app.ui.model.AgentsViewModel
import com.niki914.zafiro.app.ui.model.SavedConfigSummary
import com.niki914.zafiro.app.ui.nav.AgentEditorPage
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import com.niki914.zafiro.app.ui.settings.form.EditableSettingsDetailChrome
import com.niki914.zafiro.app.ui.settings.form.EditableSettingsDetailFormScaffold
import com.niki914.zafiro.app.ui.settings.form.SettingControlledExpandableTextItem

private const val ID_AGENT = "agent."

@Composable
fun AgentsPageContent(onOpenEditor: (agentId: String?) -> Unit) {
    val viewModel = pageViewModel<AgentsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val latestOpen by rememberUpdatedState(onOpenEditor)
    val chrome = remember {
        PageChromeContribution(
            rightAction = TopBarActionSpec(icon = Icons.Default.Add, onClick = { latestOpen(null) }, contentDescription = "Add Button"),
        )
    }
    RegisterPageChrome(chrome)
    LaunchedEffect(viewModel) { viewModel.sendIntent(AgentsIntent.Start) }

    val onText = stringResource(R.string.agents_state_on)
    val offText = stringResource(R.string.agents_state_off)
    val spec = SettingsPageSpec(
        description = stringResource(if (uiState.agents.isEmpty()) R.string.agents_empty else R.string.agents_page_description),
        sections = listOf(
            SettingsSectionSpec(
                title = stringResource(R.string.agents_section),
                layout = SettingsSectionLayout.GroupedCard,
                rows = uiState.agents.map { a ->
                    SettingsRowSpec.Navigation(
                        id = ID_AGENT + a.id,
                        title = a.name,
                        summary = a.instructions.lineSequence().first().take(90),
                        currentState = if (a.enabled) onText else offText,
                    )
                },
            ),
        ).filter { it.rows.isNotEmpty() },
    )
    SettingsSpecPageContent(
        spec = spec,
        onAction = { action ->
            if (action is SettingsRowAction.Navigate && action.id.startsWith(ID_AGENT)) latestOpen(action.id.removePrefix(ID_AGENT))
        },
    )
}

@Composable
fun AgentEditorContent(page: AgentEditorPage, onBack: () -> Unit) {
    val viewModel = pageViewModel<AgentEditorViewModel>()
    val ui by viewModel.uiStateFlow.collectAsState()
    val context = LocalContext.current
    var showModel by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(page.routeKey) { viewModel.sendIntent(AgentEditorIntent.Load(page.agentId)) }
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { e ->
            when (e) {
                AgentEditorEffect.Exit -> onBack()
                is AgentEditorEffect.Message -> Toast.makeText(context, e.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    EditableSettingsDetailChrome(
        isCreating = page.agentId == null,
        hasUnsavedChanges = { ui.hasUnsavedChanges },
        onDelete = { viewModel.sendIntent(AgentEditorIntent.RequestDelete) },
        onDiscardChanges = onBack,
        hasDeleteConfirmation = { ui.deleteConfirmation != null },
        onDismissDeleteConfirmation = { viewModel.sendIntent(AgentEditorIntent.DismissDelete) },
    ) {
        if (!ui.isLoading) {
            val d = ui.draft
            val busy = ui.isSaving
            val activeLabel = stringResource(R.string.task_field_model_active)
            val options = remember(ui.configs, activeLabel) {
                listOf(SavedConfigSummary(id = "", name = activeLabel, modelId = "", isActive = false)) + ui.configs
            }
            val selected = options.firstOrNull { it.id == d.configId } ?: options.first()

            EditableSettingsDetailFormScaffold(
                actionText = stringResource(R.string.agent_editor_save),
                requestedFocusField = null as AgentFieldKey?,
                onRequestedFocusHandled = {},
                onActionClick = { viewModel.sendIntent(AgentEditorIntent.Save) },
                description = stringResource(R.string.agent_editor_description),
                inlineErrorText = ui.error,
                actionEnabled = !busy,
            ) { fc ->
                SettingsGroupCard {
                    SettingControlledExpandableTextItem(
                        field = AgentFieldKey.Name, controller = fc, title = stringResource(R.string.agent_field_name),
                        value = d.name, onValueChange = { viewModel.sendIntent(AgentEditorIntent.Update(d.copy(name = it))) },
                        placeholder = stringResource(R.string.agent_field_name_hint), enabled = !busy, minLines = 1, maxLines = 1,
                    )
                    SettingsItemDivider()
                    SettingToggleItem(
                        title = stringResource(R.string.task_field_enabled), checked = d.enabled, enabled = !busy,
                        onCheckedChange = { viewModel.sendIntent(AgentEditorIntent.Update(d.copy(enabled = it))) },
                    )
                }
                SettingsGroupCard {
                    SettingControlledExpandableTextItem(
                        field = AgentFieldKey.Instructions, controller = fc, title = stringResource(R.string.agent_field_instructions),
                        value = d.instructions, onValueChange = { viewModel.sendIntent(AgentEditorIntent.Update(d.copy(instructions = it))) },
                        placeholder = stringResource(R.string.agent_field_instructions_hint), enabled = !busy, minLines = 5, maxLines = 14,
                    )
                }
                SettingsGroupCard {
                    SettingNavigationItem(
                        title = stringResource(R.string.task_field_model), currentState = selected.name, enabled = !busy,
                        onClick = { showModel = true },
                    )
                    SettingsItemDivider()
                    SettingControlledExpandableTextItem(
                        field = AgentFieldKey.Tools, controller = fc, title = stringResource(R.string.agent_field_tools),
                        value = d.tools, onValueChange = { viewModel.sendIntent(AgentEditorIntent.Update(d.copy(tools = it))) },
                        placeholder = stringResource(R.string.agent_field_tools_hint), enabled = !busy, minLines = 1, maxLines = 4,
                    )
                    SettingsItemDivider()
                    SettingControlledExpandableTextItem(
                        field = AgentFieldKey.Skills, controller = fc, title = stringResource(R.string.agent_field_skills),
                        value = d.skills, onValueChange = { viewModel.sendIntent(AgentEditorIntent.Update(d.copy(skills = it))) },
                        placeholder = stringResource(R.string.agent_field_skills_hint), enabled = !busy, minLines = 1, maxLines = 4,
                    )
                    SettingsItemDivider()
                    SettingToggleItem(
                        title = stringResource(R.string.agent_field_uses_screen), checked = d.usesScreen, enabled = !busy,
                        onCheckedChange = { viewModel.sendIntent(AgentEditorIntent.Update(d.copy(usesScreen = it))) },
                    )
                }
                if (page.agentId != null) {
                    SettingsGroupCard {
                        SettingNavigationItem(
                            title = stringResource(R.string.agent_test_run), summary = stringResource(R.string.agent_test_run_summary),
                            enabled = !busy, onClick = { viewModel.sendIntent(AgentEditorIntent.TestRun) },
                        )
                    }
                }
            }

            ZafiroSingleChoiceDialog(
                visible = showModel, onDismissRequest = { showModel = false },
                title = stringResource(R.string.task_field_model_dialog_title), options = options, selectedId = selected.id,
                optionId = { it.id }, optionLabel = { if (it.modelId.isBlank()) it.name else "${it.name} (${it.modelId})" },
                onSelect = { showModel = false; viewModel.sendIntent(AgentEditorIntent.Update(d.copy(configId = it.id))) },
            )
        }
        ZafiroConfirmationDialog(
            visible = ui.deleteConfirmation != null,
            onDismissRequest = { viewModel.sendIntent(AgentEditorIntent.DismissDelete) },
            title = stringResource(R.string.agent_delete_title),
            text = stringResource(R.string.agent_delete_text, ui.deleteConfirmation.orEmpty()),
            negativeButtonText = stringResource(R.string.delete_dialog_cancel),
            positiveButtonText = stringResource(R.string.delete_dialog_confirm),
            onNegativeClick = { viewModel.sendIntent(AgentEditorIntent.DismissDelete) },
            onPositiveClick = { viewModel.sendIntent(AgentEditorIntent.ConfirmDelete) },
        )
    }
}

private enum class AgentFieldKey { Name, Instructions, Tools, Skills }
