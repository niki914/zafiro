package com.niki914.nexus.agentic.app.ui.nexus.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.ProvideLiquidScreenContentForPreview
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsPageSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsRowAction
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsRowSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSectionLayout
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSectionSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSpecPageContent
import com.niki914.nexus.agentic.app.ui.infra.nav.pageViewModel
import com.niki914.nexus.agentic.app.ui.nexus.PageChromeContribution
import com.niki914.nexus.agentic.app.ui.nexus.RegisterPageChrome
import com.niki914.nexus.agentic.app.ui.nexus.model.ExecutionRuleItem
import com.niki914.nexus.agentic.app.ui.nexus.model.ExecutionRulesSettingsIntent
import com.niki914.nexus.agentic.app.ui.nexus.model.ExecutionRulesSettingsUiState
import com.niki914.nexus.agentic.app.ui.nexus.model.ExecutionRulesSettingsViewModel
import com.niki914.nexus.agentic.app.ui.nexus.nav.TopBarActionSpec
import com.niki914.nexus.agentic.runtime.settings.model.RuntimeExecutionRuleEnabledMode

private const val EXECUTION_RULE_ROW_ID_PREFIX = "execution.rule."

@Composable
fun ExecutionRulesSettingsContent(
    onOpenRuleDetail: (ruleName: String, ruleIndex: Int, isCreating: Boolean) -> Unit,
) {
    val viewModel = pageViewModel<ExecutionRulesSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val createTitle = stringResource(R.string.execution_rules_editor_title_create)
    val latestOnOpenRuleDetail by rememberUpdatedState(onOpenRuleDetail)
    val pageChromeContribution = remember(createTitle) {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = {
                    latestOnOpenRuleDetail(createTitle, -1, true)
                },
                contentDescription = createTitle,
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    LaunchedEffect(Unit) {
        viewModel.sendIntent(ExecutionRulesSettingsIntent.Load)
    }

    ExecutionRulesSettingsContentBody(
        uiState = uiState,
        onOpenRuleDetail = onOpenRuleDetail,
    )
}

@Composable
private fun ExecutionRulesSettingsContentBody(
    uiState: ExecutionRulesSettingsUiState,
    onOpenRuleDetail: (ruleName: String, ruleIndex: Int, isCreating: Boolean) -> Unit,
) {

    val pageDescription = when {
        uiState.isLoading || uiState.items.isNotEmpty() -> {
            stringResource(R.string.execution_rules_page_description)
        }

        else -> stringResource(R.string.execution_rules_empty_action_hint)
    }
    val loadingText = stringResource(R.string.execution_rules_loading)

    SettingsSpecPageContent(
        spec = executionRulesSettingsSpec(
            uiState = uiState,
            pageDescription = pageDescription,
            loadingText = loadingText,
        ),
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate -> {
                    val index =
                        executionRuleIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                    val item = uiState.items.getOrNull(index) ?: return@SettingsSpecPageContent
                    onOpenRuleDetail(item.name, index, false)
                }

                is SettingsRowAction.Click,
                is SettingsRowAction.ToggleChanged -> Unit
            }
        },
    )
}

private fun executionRulesSettingsSpec(
    uiState: ExecutionRulesSettingsUiState,
    pageDescription: String,
    loadingText: String,
): SettingsPageSpec {
    val sections = when {
        uiState.isLoading -> listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = listOf(
                    SettingsRowSpec.Message(
                        title = loadingText,
                        verticalPadding = 12.dp,
                    )
                ),
            )
        )

        uiState.items.isNotEmpty() -> listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.CardList,
                rows = uiState.items.mapIndexed { index, item ->
                    SettingsRowSpec.Navigation(
                        id = executionRuleRowId(index),
                        title = item.name,
                        enabled = !uiState.isSaving,
                    )
                },
            )
        )

        else -> emptyList()
    }

    return SettingsPageSpec(
        description = pageDescription,
        sections = sections,
    )
}

private fun executionRuleRowId(index: Int): String = "$EXECUTION_RULE_ROW_ID_PREFIX$index"

private fun executionRuleIndexFromRowId(id: String): Int? {
    if (!id.startsWith(EXECUTION_RULE_ROW_ID_PREFIX)) return null
    return id.removePrefix(EXECUTION_RULE_ROW_ID_PREFIX).toIntOrNull()
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun ExecutionRulesSettingsContentPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            ExecutionRulesSettingsContentBody(
                uiState = ExecutionRulesSettingsUiState(
                    items = listOf(
                        ExecutionRuleItem(
                            id = "builtin-dangerous-delete",
                            name = "危险删改",
                            enabledMode = RuntimeExecutionRuleEnabledMode.LOCKED_ONLY,
                            patterns = listOf("\\brm\\s+-rf\\b"),
                        ),
                        ExecutionRuleItem(
                            id = "builtin-uninstall",
                            name = "卸载相关",
                            enabledMode = RuntimeExecutionRuleEnabledMode.ALWAYS,
                            patterns = listOf("\\bpm\\s+uninstall\\b"),
                        ),
                        ExecutionRuleItem(
                            id = "builtin-privileged",
                            name = "高危提权",
                            enabledMode = RuntimeExecutionRuleEnabledMode.DISABLED,
                            patterns = listOf("\\bsu\\b"),
                        ),
                    ),
                    isLoading = false,
                    isSaving = false,
                ),
                onOpenRuleDetail = { _, _, _ -> },
            )
        }
    }
}
