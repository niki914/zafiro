package com.niki914.zafiro.app.ui.content

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.ui.model.BuiltinToolGroupUiItem
import com.niki914.zafiro.app.ui.model.BuiltinToolSettingsIntent
import com.niki914.zafiro.app.ui.model.BuiltinToolSettingsUiState
import com.niki914.zafiro.app.ui.model.BuiltinToolSettingsViewModel
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolSettingItem
import com.niki914.zafiro.repo.BuiltinToolGroupMode

private const val GROUP_ID_PREFIX = "group:"

@Composable
fun BuiltinToolsSettingsContent(
    onOpenGroupDetail: (groupId: String) -> Unit,
) {
    val viewModel = pageViewModel<BuiltinToolSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.sendIntent(BuiltinToolSettingsIntent.Load)
    }

    BuiltinToolsSettingsContentBody(
        uiState = uiState,
        onItemEnabledChange = { name, checked ->
            viewModel.sendIntent(
                BuiltinToolSettingsIntent.ItemEnabledChanged(
                    name = name,
                    value = checked,
                )
            )
        },
        onGroupToggled = { groupId, checked ->
            viewModel.sendIntent(
                BuiltinToolSettingsIntent.GroupToggled(
                    groupId = groupId,
                    value = checked,
                )
            )
        },
        onOpenGroupDetail = onOpenGroupDetail,
    )
}

@Composable
private fun BuiltinToolsSettingsContentBody(
    uiState: BuiltinToolSettingsUiState,
    onItemEnabledChange: (String, Boolean) -> Unit,
    onGroupToggled: (String, Boolean) -> Unit,
    onOpenGroupDetail: (String) -> Unit,
) {
    SettingsSpecPageContent(
        spec = builtinToolsSettingsSpec(uiState),
        onAction = { action ->
            when (action) {
                is SettingsRowAction.ToggleChanged -> {
                    if (action.id.startsWith(GROUP_ID_PREFIX)) {
                        onGroupToggled(action.id.removePrefix(GROUP_ID_PREFIX), action.checked)
                    } else {
                        onItemEnabledChange(action.id, action.checked)
                    }
                }
                is SettingsRowAction.Navigate -> {
                    val groupId = action.id.removePrefix(GROUP_ID_PREFIX)
                    if (action.id.startsWith(GROUP_ID_PREFIX)) {
                        onOpenGroupDetail(groupId)
                    }
                }
                is SettingsRowAction.Click -> Unit
            }
        },
    )
}

@Composable
private fun builtinToolsSettingsSpec(uiState: BuiltinToolSettingsUiState): SettingsPageSpec {
    val rows = uiState.groups.map { group ->
        group.toRow(enabled = !uiState.isSaving)
    } + uiState.standaloneTools.map { item ->
        SettingsRowSpec.Toggle(
            id = item.name,
            title = item.name,
            summary = item.description,
            checked = item.enabled,
            enabled = !uiState.isSaving,
        )
    }

    return SettingsPageSpec(
        description = builtinToolDescription(uiState),
        sections = if (rows.isNotEmpty()) {
            listOf(
                SettingsSectionSpec(
                    layout = SettingsSectionLayout.GroupedCard,
                    rows = rows,
                ),
            )
        } else {
            emptyList()
        },
    )
}

@Composable
private fun BuiltinToolGroupUiItem.toRow(enabled: Boolean): SettingsRowSpec {    return when (mode) {
        // 绑定式：与单独工具同款 Switch，点击写穿全组成员，无二级页
        BuiltinToolGroupMode.WHOLE -> SettingsRowSpec.Toggle(
            id = "$GROUP_ID_PREFIX$id",
            title = stringResource(titleRes),
            summary = stringResource(summaryRes),
            checked = checked,
            enabled = enabled,
        )
        // 不绑定：导航行进二级页逐工具开关
        BuiltinToolGroupMode.PER_TOOL -> SettingsRowSpec.Navigation(
            id = "$GROUP_ID_PREFIX$id",
            title = stringResource(titleRes),
            summary = stringResource(summaryRes),
            enabled = enabled,
        )
    }
}

@Composable
private fun builtinToolDescription(uiState: BuiltinToolSettingsUiState): String {
    val arg = uiState.descriptionArg
    return if (arg == null) {
        stringResource(uiState.descriptionResId)
    } else {
        stringResource(uiState.descriptionResId, arg)
    }
}

@Preview(name = "Builtin Tools Grouped", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun BuiltinToolsSettingsContentGroupedPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            BuiltinToolsSettingsContentBody(
                uiState = previewUiState(),
                onItemEnabledChange = { _, _ -> },
                onGroupToggled = { _, _ -> },
                onOpenGroupDetail = {},
            )
        }
    }
}

private fun previewUiState(): BuiltinToolSettingsUiState = BuiltinToolSettingsUiState(
    groups = listOf(
        BuiltinToolGroupUiItem(
            id = "dev_tools",
            titleRes = R.string.builtin_tool_group_dev_tools,
            summaryRes = R.string.builtin_tool_group_dev_tools_summary,
            mode = BuiltinToolGroupMode.PER_TOOL,
            checked = true,
        ),
        BuiltinToolGroupUiItem(
            id = "android_native",
            titleRes = R.string.builtin_tool_group_android_native,
            summaryRes = R.string.builtin_tool_group_android_native_summary,
            mode = BuiltinToolGroupMode.PER_TOOL,
            checked = true,
        ),
        BuiltinToolGroupUiItem(
            id = "screen_operation",
            titleRes = R.string.builtin_tool_group_screen_operation,
            summaryRes = R.string.builtin_tool_group_screen_operation_summary,
            mode = BuiltinToolGroupMode.WHOLE,
            checked = true,
        ),
        BuiltinToolGroupUiItem(
            id = "custom_tool",
            titleRes = R.string.builtin_tool_group_custom_tool,
            summaryRes = R.string.builtin_tool_group_custom_tool_summary,
            mode = BuiltinToolGroupMode.WHOLE,
            checked = true,
        ),
    ),
    standaloneTools = listOf(
        BuiltinToolSettingItem(
            name = "load_skill",
            description = "Load a skill by id when its full SKILL.md is needed.",
            enabled = false,
        ),
        BuiltinToolSettingItem(
            name = "memory",
            description = "Save durable facts to persistent memory.",
            enabled = true,
        ),
    ),
    isLoading = false,
    descriptionResId = R.string.builtin_tool_page_description,
)
