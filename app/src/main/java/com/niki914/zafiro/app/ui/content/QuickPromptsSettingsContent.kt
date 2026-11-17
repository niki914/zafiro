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
import com.niki914.zafiro.app.ui.model.QuickPromptsSettingsIntent
import com.niki914.zafiro.app.ui.model.QuickPromptsSettingsUiState
import com.niki914.zafiro.app.ui.model.QuickPromptsSettingsViewModel
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec

private const val QUICK_PROMPT_ROW_ID_PREFIX = "quick.prompt."

@Composable
fun QuickPromptsSettingsContent(
    onOpenPromptDetail: (title: String, index: Int, isCreating: Boolean) -> Unit,
) {
    val viewModel = pageViewModel<QuickPromptsSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val createTitle = stringResource(R.string.quick_prompt_editor_title_create)
    val latestOnOpenPromptDetail by rememberUpdatedState(onOpenPromptDetail)
    val pageChromeContribution = remember(createTitle) {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = {
                    latestOnOpenPromptDetail(createTitle, -1, true)
                },
                contentDescription = "Add Button",
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    LaunchedEffect(Unit) {
        viewModel.sendIntent(QuickPromptsSettingsIntent.Load)
    }
    val pageDescription = when {
        uiState.isLoading || uiState.items.isNotEmpty() -> stringResource(R.string.quick_prompt_page_description)
        else -> stringResource(R.string.quick_prompt_page_empty_description)
    }
    val loadingText = stringResource(R.string.quick_prompt_loading)
    val spec = quickPromptsSettingsSpec(
        uiState = uiState,
        pageDescription = pageDescription,
        loadingText = loadingText,
    )

    SettingsSpecPageContent(spec = spec) { action ->
        when (action) {
            is SettingsRowAction.Navigate -> {
                val index = quickPromptIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                val item = uiState.items.getOrNull(index) ?: return@SettingsSpecPageContent
                onOpenPromptDetail("/${item.name}", index, false)
            }

            is SettingsRowAction.ToggleChanged -> {
                val index = quickPromptIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                viewModel.sendIntent(
                    QuickPromptsSettingsIntent.ItemEnabledChanged(
                        index = index,
                        value = action.checked,
                    )
                )
            }

            else -> Unit
        }
    }
}

private fun quickPromptsSettingsSpec(
    uiState: QuickPromptsSettingsUiState,
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
                        id = quickPromptRowId(index),
                        title = "/${item.name}",
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

private fun quickPromptRowId(index: Int): String = "$QUICK_PROMPT_ROW_ID_PREFIX$index"

private fun quickPromptIndexFromRowId(id: String): Int? {
    if (!id.startsWith(QUICK_PROMPT_ROW_ID_PREFIX)) return null
    return id.removePrefix(QUICK_PROMPT_ROW_ID_PREFIX).toIntOrNull()
}
