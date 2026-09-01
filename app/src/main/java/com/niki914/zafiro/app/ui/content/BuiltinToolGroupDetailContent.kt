package com.niki914.zafiro.app.ui.content

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.BuiltinToolGroupDetailIntent
import com.niki914.zafiro.app.ui.model.BuiltinToolGroupDetailUiState
import com.niki914.zafiro.app.ui.model.BuiltinToolGroupDetailViewModel
import com.niki914.zafiro.app.ui.nav.BuiltinToolGroupDetailPage
import com.niki914.zafiro.app.ui.nav.CustomPyToolsPage
import com.niki914.zafiro.app.ui.nav.ZafiroPage

private const val CUSTOM_PY_TOOLS_ENTRY_ROW_ID = "custom-py-tools-entry"

@Composable
fun BuiltinToolGroupDetailContent(
    page: BuiltinToolGroupDetailPage,
    onBack: () -> Unit,
    onPush: (ZafiroPage) -> Unit,
) {
    val viewModel = pageViewModel<BuiltinToolGroupDetailViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()

    LaunchedEffect(page.groupId) {
        viewModel.sendIntent(BuiltinToolGroupDetailIntent.Load(page.groupId))
    }

    SettingsSpecPageContent(
        spec = builtinToolGroupDetailSpec(uiState),
        onAction = { action ->
            when (action) {
                is SettingsRowAction.ToggleChanged -> viewModel.sendIntent(
                    BuiltinToolGroupDetailIntent.ItemEnabledChanged(
                        name = action.id,
                        value = action.checked,
                    )
                )

                is SettingsRowAction.Navigate -> {
                    if (action.id == CUSTOM_PY_TOOLS_ENTRY_ROW_ID) {
                        onPush(CustomPyToolsPage)
                    }
                }

                else -> Unit
            }
        },
    )
}

@Composable
private fun builtinToolGroupDetailSpec(uiState: BuiltinToolGroupDetailUiState): SettingsPageSpec {
    val sections = if (!uiState.isLoading && uiState.members.isNotEmpty()) {
        listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = uiState.members.map { item ->
                    SettingsRowSpec.Toggle(
                        id = item.name,
                        title = item.name,
                        summary = item.description,
                        checked = item.enabled,
                        enabled = !uiState.isSaving,
                    )
                } + if (uiState.groupId == "dev_tools") {
                    listOf(
                        SettingsRowSpec.Navigation(
                            id = CUSTOM_PY_TOOLS_ENTRY_ROW_ID,
                            title = stringResource(R.string.builtin_tool_custom_py_tools_entry),
                            summary = stringResource(R.string.builtin_tool_custom_py_tools_entry_summary),
                        ),
                    )
                } else {
                    emptyList()
                },
            ),
        )
    } else {
        emptyList()
    }

    return SettingsPageSpec(
        description = stringResource(R.string.builtin_tool_page_description),
        sections = sections,
    )
}

@Preview(name = "Builtin Tool Group Detail", showBackground = true, widthDp = 420, heightDp = 700)
@Composable
private fun BuiltinToolGroupDetailContentPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            SettingsSpecPageContent(
                spec = SettingsPageSpec(
                    description = stringResource(R.string.builtin_tool_group_dev_tools_summary),
                    sections = listOf(
                        SettingsSectionSpec(
                            layout = SettingsSectionLayout.GroupedCard,
                            rows = listOf(
                                SettingsRowSpec.Toggle(
                                    id = "terminal",
                                    title = "terminal",
                                    summary = "Execute shell commands in an Android terminal environment.",
                                    checked = true,
                                ),
                                SettingsRowSpec.Toggle(
                                    id = "execute_python",
                                    title = "execute_python",
                                    summary = "Execute Python code in an Android environment.",
                                    checked = true,
                                ),
                            ),
                        ),
                    ),
                ),
                onAction = {},
            )
        }
    }
}
