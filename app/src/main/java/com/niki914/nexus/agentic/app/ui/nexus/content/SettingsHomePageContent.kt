package com.niki914.nexus.agentic.app.ui.nexus.content

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.ui.infra.ProvideLiquidScreenContentForPreview
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsPageSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsRowAction
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsRowSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSectionLayout
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSectionSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSpecPageContent
import com.niki914.nexus.agentic.app.ui.infra.nav.pageViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.SettingsUiState
import com.niki914.nexus.agentic.app.ui.nexus.model.SettingsViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.buildSettingsUiState
import com.niki914.nexus.agentic.app.ui.nexus.nav.NexusSettingsGroup
import com.niki914.nexus.base.BaseTheme

@Composable
fun SettingsHomePageContent(
    onOpenGroup: (NexusSettingsGroup) -> Unit,
) {
    val viewModel = pageViewModel<SettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()

    SettingsHomePageContentBody(
        uiState = uiState,
        onOpenGroup = onOpenGroup,
    )
}

@Composable
private fun SettingsHomePageContentBody(
    uiState: SettingsUiState,
    onOpenGroup: (NexusSettingsGroup) -> Unit,
) {
    val groupsById = uiState.sections
        .flatMap { it.groups }
        .associateBy { it.name }

    SettingsSpecPageContent(
        spec = settingsHomePageSpec(uiState),
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate -> groupsById[action.id]?.let(onOpenGroup)
                is SettingsRowAction.Click -> Unit
                is SettingsRowAction.ToggleChanged -> Unit
            }
        },
    )
}

@Composable
private fun settingsHomePageSpec(
    uiState: SettingsUiState,
): SettingsPageSpec {
    return SettingsPageSpec(
        sections = uiState.sections.map { section ->
            SettingsSectionSpec(
                title = stringResource(section.titleRes),
                layout = SettingsSectionLayout.GroupedCard,
                rows = section.groups.map { group ->
                    val summary = stringResource(group.summaryRes)
                    SettingsRowSpec.Navigation(
                        id = group.name,
                        title = stringResource(group.titleRes),
                        summary = summary.takeIf { it.isNotBlank() },
                    )
                },
            )
        },
    )
}

@Preview(name = "Settings Home", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun SettingsHomePageContentPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
                SettingsHomePageContentBody(
                    uiState = buildSettingsUiState(hiddenGroups = emptySet()),
                    onOpenGroup = {},
                )
            }
        }
    }
}
