package com.niki914.zafiro.app.ui.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
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
import com.niki914.zafiro.app.ui.model.TextActionSettingsIntent
import com.niki914.zafiro.app.ui.model.TextActionSettingsUiState
import com.niki914.zafiro.app.ui.model.TextActionSettingsViewModel
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec

private const val TEXT_ACTION_ROW_ID_PREFIX = "text.action."

@Composable
fun TextActionsSettingsContent(
    onOpenActionDetail: (title: String, index: Int, isCreating: Boolean) -> Unit,
) {
    val viewModel = pageViewModel<TextActionSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val createTitle = stringResource(R.string.text_action_editor_title_create)
    val latestOnOpenActionDetail by rememberUpdatedState(onOpenActionDetail)
    val pageChromeContribution = remember(createTitle) {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = {
                    latestOnOpenActionDetail(createTitle, -1, true)
                },
                contentDescription = "Add Button",
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    LaunchedEffect(Unit) {
        viewModel.sendIntent(TextActionSettingsIntent.Load)
    }
    val pageDescription = when {
        uiState.isLoading || uiState.items.isNotEmpty() -> stringResource(R.string.text_action_page_description)
        else -> stringResource(R.string.text_action_page_empty_description)
    }
    val loadingText = stringResource(R.string.text_action_loading)
    val spec = textActionsSettingsSpec(
        uiState = uiState,
        pageDescription = pageDescription,
        loadingText = loadingText,
    )

    SettingsSpecPageContent(spec = spec) { action ->
        when (action) {
            is SettingsRowAction.Navigate -> {
                val index = textActionIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                val item = uiState.items.getOrNull(index) ?: return@SettingsSpecPageContent
                onOpenActionDetail(item.name, index, false)
            }

            is SettingsRowAction.ToggleChanged -> {
                val index = textActionIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                viewModel.sendIntent(
                    TextActionSettingsIntent.ItemEnabledChanged(
                        index = index,
                        value = action.checked,
                    )
                )
            }

            else -> Unit
        }
    }
}

private fun textActionsSettingsSpec(
    uiState: TextActionSettingsUiState,
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
                        id = textActionRowId(index),
                        title = item.name,
                        summary = item.prompt,
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

private fun textActionRowId(index: Int): String = "$TEXT_ACTION_ROW_ID_PREFIX$index"

private fun textActionIndexFromRowId(id: String): Int? {
    if (!id.startsWith(TEXT_ACTION_ROW_ID_PREFIX)) return null
    return id.removePrefix(TEXT_ACTION_ROW_ID_PREFIX).toIntOrNull()
}
