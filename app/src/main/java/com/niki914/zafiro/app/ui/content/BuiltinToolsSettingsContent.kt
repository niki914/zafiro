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
import com.niki914.zafiro.app.ui.model.BuiltinToolSettingsIntent
import com.niki914.zafiro.app.ui.model.BuiltinToolSettingsUiState
import com.niki914.zafiro.app.ui.model.BuiltinToolSettingsViewModel
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolSettingItem

@Composable
fun BuiltinToolsSettingsContent() {
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
    )
}

@Composable
private fun BuiltinToolsSettingsContentBody(
    uiState: BuiltinToolSettingsUiState,
    onItemEnabledChange: (String, Boolean) -> Unit,
) {
    SettingsSpecPageContent(
        spec = builtinToolsSettingsSpec(uiState),
        onAction = { action ->
            when (action) {
                is SettingsRowAction.ToggleChanged -> onItemEnabledChange(action.id, action.checked)
                is SettingsRowAction.Click -> Unit
                is SettingsRowAction.Navigate -> Unit
            }
        },
    )
}

@Composable
private fun builtinToolsSettingsSpec(uiState: BuiltinToolSettingsUiState): SettingsPageSpec {
    val sections = if (!uiState.isLoading && uiState.items.isNotEmpty()) {
        listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = uiState.items.map { item ->
                    SettingsRowSpec.Toggle(
                        id = item.name,
                        title = item.name,
                        summary = item.description,
                        checked = item.enabled,
                        enabled = !uiState.isSaving,
                    )
                },
            )
        )
    } else {
        emptyList()
    }

    return SettingsPageSpec(
        description = builtinToolDescription(uiState),
        sections = sections,
    )
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

@Preview(name = "Builtin Tools Empty", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun BuiltinToolsSettingsContentEmptyPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            BuiltinToolsSettingsContentBody(
                uiState = BuiltinToolSettingsUiState(
                    isLoading = false,
                    descriptionResId = R.string.builtin_tool_empty,
                ),
                onItemEnabledChange = { _, _ -> },
            )
        }
    }
}

@Preview(name = "Builtin Tools Long List", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun BuiltinToolsSettingsContentLongListPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            BuiltinToolsSettingsContentBody(
                uiState = BuiltinToolSettingsUiState(
                    items = List(20) { index ->
                        val displayIndex = index + 1
                        BuiltinToolSettingItem(
                            name = "builtin_tool_$displayIndex",
                            description = "用于预览滚动列表的内置工具说明 $displayIndex",
                            enabled = index % 2 == 0,
                        )
                    },
                    isLoading = false,
                    descriptionResId = R.string.builtin_tool_page_description,
                ),
                onItemEnabledChange = { _, _ -> },
            )
        }
    }
}
