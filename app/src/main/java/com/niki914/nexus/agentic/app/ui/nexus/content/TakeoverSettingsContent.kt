package com.niki914.nexus.agentic.app.ui.nexus.content

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.ProvideLiquidScreenContentForPreview
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsGroupCard
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsSegmentedSelector
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsPageSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsRowAction
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsRowSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSectionLayout
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSectionSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSpecPageContent
import com.niki914.nexus.agentic.app.ui.infra.nav.pageViewModel
import com.niki914.nexus.agentic.app.ui.nexus.PageChromeContribution
import com.niki914.nexus.agentic.app.ui.nexus.RegisterPageChrome
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverInlineError
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverRuleItem
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverSettingsIntent
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverSettingsUiState
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverSettingsViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.TakeoverTarget
import com.niki914.nexus.agentic.app.ui.nexus.nav.TopBarActionSpec

private const val TAKEOVER_RULE_ROW_ID_PREFIX = "takeover.rule."

@Composable
fun TakeoverSettingsContent(
    onOpenRuleDetail: (ruleId: String?, ruleName: String, ruleIndex: Int, isCreating: Boolean) -> Unit,
) {
    val viewModel = pageViewModel<TakeoverSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val createTitle = stringResource(R.string.takeover_editor_title_create)
    val latestOnOpenRuleDetail by rememberUpdatedState(onOpenRuleDetail)
    val pageChromeContribution = remember(createTitle) {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = {
                    latestOnOpenRuleDetail(null, createTitle, -1, true)
                },
                contentDescription = createTitle,
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    LaunchedEffect(Unit) {
        viewModel.sendIntent(TakeoverSettingsIntent.Load)
    }

    TakeoverSettingsContentBody(
        uiState = uiState,
        onOpenRuleDetail = onOpenRuleDetail,
        onItemEnabledChanged = { index, enabled ->
            viewModel.sendIntent(TakeoverSettingsIntent.ItemEnabledChanged(index, enabled))
        },
        onDefaultTargetChanged = { target ->
            viewModel.sendIntent(TakeoverSettingsIntent.DefaultTargetChanged(target))
        },
    )
}

@Composable
private fun TakeoverSettingsContentBody(
    uiState: TakeoverSettingsUiState,
    onOpenRuleDetail: (ruleId: String?, ruleName: String, ruleIndex: Int, isCreating: Boolean) -> Unit,
    onItemEnabledChanged: (index: Int, enabled: Boolean) -> Unit = { _, _ -> },
    onDefaultTargetChanged: (TakeoverTarget) -> Unit = {},
) {
    val pageDescription = when {
        uiState.isLoading || uiState.items.isNotEmpty() -> {
            stringResource(R.string.takeover_page_description)
        }

        else -> stringResource(R.string.takeover_empty_action_hint)
    }
    val loadingText = stringResource(R.string.takeover_loading)

    SettingsSpecPageContent(
        spec = takeoverRulesSettingsSpec(
            uiState = uiState,
            pageDescription = pageDescription,
            loadingText = loadingText,
        ),
        contentBeforeSections = {
            takeoverInlineErrorText(uiState.inlineError)?.let { message ->
                SettingsGroupCard {
                    TakeoverListMessage(text = message)
                }
            }
            if (!uiState.isLoading) {
                SettingsGroupCard {
                    SettingsSegmentedSelector(
                        title = stringResource(R.string.takeover_default_responder),
                        options = TakeoverTarget.entries,
                        selected = uiState.defaultTarget,
                        label = { target -> target.label() },
                        enabled = !uiState.isSaving,
                        onSelected = onDefaultTargetChanged,
                    )
                }
            }
        },
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate -> {
                    val index =
                        takeoverRuleIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                    val item = uiState.items.getOrNull(index) ?: return@SettingsSpecPageContent
                    onOpenRuleDetail(item.id, item.name, index, false)
                }

                is SettingsRowAction.ToggleChanged -> {
                    val index =
                        takeoverRuleIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                    onItemEnabledChanged(index, action.checked)
                }

                is SettingsRowAction.Click -> Unit
            }
        },
    )
}

private fun takeoverRulesSettingsSpec(
    uiState: TakeoverSettingsUiState,
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
                    SettingsRowSpec.ToggleNavigation(
                        id = takeoverRuleRowId(index),
                        title = item.name,
                        checked = item.enabled,
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

private fun takeoverRuleRowId(index: Int): String = "$TAKEOVER_RULE_ROW_ID_PREFIX$index"

private fun takeoverRuleIndexFromRowId(id: String): Int? {
    if (!id.startsWith(TAKEOVER_RULE_ROW_ID_PREFIX)) return null
    return id.removePrefix(TAKEOVER_RULE_ROW_ID_PREFIX).toIntOrNull()
}

@Composable
private fun TakeoverListMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun takeoverInlineErrorText(error: TakeoverInlineError?): String? {
    return when (error) {
        null -> null
        is TakeoverInlineError.LoadFailed -> takeoverErrorText(
            message = error.causeMessage,
            genericResId = R.string.takeover_error_load_failed_generic,
            detailedResId = R.string.takeover_error_load_failed,
        )

        is TakeoverInlineError.SaveFailed -> takeoverErrorText(
            message = error.causeMessage,
            genericResId = R.string.takeover_error_save_failed_generic,
            detailedResId = R.string.takeover_error_save_failed,
        )

        is TakeoverInlineError.DeleteFailed -> takeoverErrorText(
            message = error.causeMessage,
            genericResId = R.string.takeover_error_delete_failed_generic,
            detailedResId = R.string.takeover_error_delete_failed,
        )
    }
}

@Composable
private fun takeoverErrorText(
    message: String?,
    genericResId: Int,
    detailedResId: Int,
): String {
    return if (message.isNullOrBlank()) {
        stringResource(genericResId)
    } else {
        stringResource(detailedResId, message)
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun TakeoverSettingsContentPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            TakeoverSettingsContentBody(
                uiState = TakeoverSettingsUiState(
                    items = listOf(
                        TakeoverRuleItem(
                            id = "native-weather",
                            name = "小布生活服务",
                            target = TakeoverTarget.NativeAssistant,
                            patterns = listOf("天气", "闹钟", "日程"),
                        ),
                        TakeoverRuleItem(
                            id = "nexus-debug",
                            name = "调试问题交给 Nexus",
                            target = TakeoverTarget.Nexus,
                            patterns = listOf(".*崩溃.*", ".*日志.*"),
                        ),
                        TakeoverRuleItem(
                            id = "native-media",
                            name = "媒体控制回退原生",
                            target = TakeoverTarget.NativeAssistant,
                            patterns = listOf("播放", "暂停"),
                            enabled = false,
                        ),
                    ),
                    defaultTarget = TakeoverTarget.Nexus,
                ),
                onOpenRuleDetail = { _, _, _, _ -> },
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun TakeoverSettingsEmptyContentPreview() {
    MaterialTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            TakeoverSettingsContentBody(
                uiState = TakeoverSettingsUiState(),
                onOpenRuleDetail = { _, _, _, _ -> },
            )
        }
    }
}
