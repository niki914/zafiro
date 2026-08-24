package com.niki914.zafiro.app.ui.nexus.content.mcp

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.niki914.zafiro.app.R
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.ui.nexus.PageChromeContribution
import com.niki914.zafiro.app.ui.nexus.RegisterPageChrome
import com.niki914.zafiro.app.ui.nexus.model.McpSettingsIntent
import com.niki914.zafiro.app.ui.nexus.model.McpSettingsUiState
import com.niki914.zafiro.app.ui.nexus.model.McpSettingsViewModel
import com.niki914.zafiro.app.ui.nexus.nav.TopBarActionSpec

private const val MCP_SERVER_ROW_ID_PREFIX = "mcp.server."

@Composable
fun McpSettingsContent(
    onOpenServerDetail: (serverName: String, serverIndex: Int, isCreating: Boolean) -> Unit,
) {
    val viewModel = pageViewModel<McpSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val createTitle = stringResource(R.string.mcp_editor_title_create)
    val latestOnOpenServerDetail by rememberUpdatedState(onOpenServerDetail)
    val pageChromeContribution = remember(createTitle) {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = {
                    latestOnOpenServerDetail(createTitle, -1, true)
                },
                contentDescription = createTitle,
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    LaunchedEffect(Unit) {
        viewModel.sendIntent(McpSettingsIntent.Load)
    }
    val pageDescription = when {
        uiState.isLoading || uiState.items.isNotEmpty() -> stringResource(R.string.mcp_page_description)
        else -> stringResource(R.string.mcp_page_empty_description)
    }
    val loadingText = stringResource(R.string.mcp_loading)
    val spec = mcpSettingsSpec(
        uiState = uiState,
        pageDescription = pageDescription,
        loadingText = loadingText,
    )

    SettingsSpecPageContent(spec = spec) { action ->
        when (action) {
            is SettingsRowAction.Navigate -> {
                val index = mcpServerIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                val item = uiState.items.getOrNull(index) ?: return@SettingsSpecPageContent
                onOpenServerDetail(item.name, index, false)
            }

            is SettingsRowAction.ToggleChanged -> {
                val index = mcpServerIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                viewModel.sendIntent(
                    McpSettingsIntent.ItemEnabledChanged(
                        index = index,
                        value = action.checked,
                    )
                )
            }

            is SettingsRowAction.Click -> Unit
        }
    }
}

private fun mcpSettingsSpec(
    uiState: McpSettingsUiState,
    pageDescription: String,
    loadingText: String,
): SettingsPageSpec {
    val sections = when {
        uiState.isLoading -> listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(SettingsRowSpec.Message(title = loadingText)),
            )
        )

        uiState.items.isNotEmpty() -> listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.CardList,
                rows = uiState.items.mapIndexed { index, item ->
                    SettingsRowSpec.ToggleNavigation(
                        id = mcpServerRowId(index),
                        title = item.name,
                        checked = item.enabled,
                        enabled = !uiState.isSaving,
                    )
                }
            )
        )

        else -> emptyList()
    }

    return SettingsPageSpec(
        description = pageDescription,
        sections = sections,
    )
}

private fun mcpServerRowId(index: Int): String = "$MCP_SERVER_ROW_ID_PREFIX$index"

private fun mcpServerIndexFromRowId(id: String): Int? {
    if (!id.startsWith(MCP_SERVER_ROW_ID_PREFIX)) return null
    return id.removePrefix(MCP_SERVER_ROW_ID_PREFIX).toIntOrNull()
}
