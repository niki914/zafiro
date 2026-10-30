package com.niki914.zafiro.app.ui.content

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
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.uikit.screen.ProvideZafiroScreenContentForPreview
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.model.MaskingRuleItem
import com.niki914.zafiro.app.ui.model.MaskingRulesInlineError
import com.niki914.zafiro.app.ui.model.MaskingRulesSettingsIntent
import com.niki914.zafiro.app.ui.model.MaskingRulesSettingsUiState
import com.niki914.zafiro.app.ui.model.MaskingRulesSettingsViewModel
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec

private const val MASKING_RULE_ROW_ID_PREFIX = "masking.rule."

@Composable
fun MaskingRulesSettingsContent(
    onOpenRuleDetail: (ruleId: String?, ruleName: String, ruleIndex: Int, isCreating: Boolean) -> Unit,
) {
    val viewModel = pageViewModel<MaskingRulesSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val createTitle = stringResource(R.string.masking_rules_editor_title_create)
    val latestOnOpenRuleDetail by rememberUpdatedState(onOpenRuleDetail)
    val pageChromeContribution = remember(createTitle) {
        PageChromeContribution(
            rightAction = TopBarActionSpec(
                icon = Icons.Default.Add,
                onClick = {
                    latestOnOpenRuleDetail(null, createTitle, -1, true)
                },
                contentDescription = "Add Button",
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    LaunchedEffect(Unit) {
        viewModel.sendIntent(MaskingRulesSettingsIntent.Load)
    }

    MaskingRulesSettingsContentBody(
        uiState = uiState,
        onOpenRuleDetail = onOpenRuleDetail,
        onItemEnabledChanged = { index, enabled ->
            viewModel.sendIntent(MaskingRulesSettingsIntent.ItemEnabledChanged(index, enabled))
        },
    )
}

@Composable
private fun MaskingRulesSettingsContentBody(
    uiState: MaskingRulesSettingsUiState,
    onOpenRuleDetail: (ruleId: String?, ruleName: String, ruleIndex: Int, isCreating: Boolean) -> Unit,
    onItemEnabledChanged: (index: Int, enabled: Boolean) -> Unit = { _, _ -> },
) {
    val pageDescription = when {
        uiState.isLoading || uiState.items.isNotEmpty() -> {
            stringResource(R.string.masking_rules_page_description)
        }

        else -> stringResource(R.string.masking_rules_empty_action_hint)
    }
    val loadingText = stringResource(R.string.masking_rules_loading)

    SettingsSpecPageContent(
        spec = maskingRulesSettingsSpec(
            uiState = uiState,
            pageDescription = pageDescription,
            loadingText = loadingText,
        ),
        contentBeforeSections = {
            maskingInlineErrorText(uiState.inlineError)?.let { message ->
                SettingsGroupCard {
                    MaskingListMessage(text = message)
                }
            }
        },
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate -> {
                    val index =
                        maskingRuleIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                    val item = uiState.items.getOrNull(index) ?: return@SettingsSpecPageContent
                    onOpenRuleDetail(item.id, item.name, index, false)
                }

                is SettingsRowAction.ToggleChanged -> {
                    val index =
                        maskingRuleIndexFromRowId(action.id) ?: return@SettingsSpecPageContent
                    onItemEnabledChanged(index, action.checked)
                }

                else -> Unit
            }
        },
    )
}

private fun maskingRulesSettingsSpec(
    uiState: MaskingRulesSettingsUiState,
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
                        id = maskingRuleRowId(index),
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

private fun maskingRuleRowId(index: Int): String = "$MASKING_RULE_ROW_ID_PREFIX$index"

private fun maskingRuleIndexFromRowId(id: String): Int? {
    if (!id.startsWith(MASKING_RULE_ROW_ID_PREFIX)) return null
    return id.removePrefix(MASKING_RULE_ROW_ID_PREFIX).toIntOrNull()
}

@Composable
private fun MaskingListMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
internal fun maskingInlineErrorText(error: MaskingRulesInlineError?): String? {
    return when (error) {
        null -> null
        is MaskingRulesInlineError.LoadFailed -> maskingErrorText(
            message = error.causeMessage,
            genericResId = R.string.masking_rules_error_load_failed_generic,
            detailedResId = R.string.masking_rules_error_load_failed,
        )

        is MaskingRulesInlineError.SaveFailed -> maskingErrorText(
            message = error.causeMessage,
            genericResId = R.string.masking_rules_error_save_failed_generic,
            detailedResId = R.string.masking_rules_error_save_failed,
        )

        is MaskingRulesInlineError.DeleteFailed -> maskingErrorText(
            message = error.causeMessage,
            genericResId = R.string.masking_rules_error_delete_failed_generic,
            detailedResId = R.string.masking_rules_error_delete_failed,
        )
    }
}

@Composable
private fun maskingErrorText(
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
private fun MaskingRulesSettingsContentPreview() {
    MaterialTheme {
        ProvideZafiroScreenContentForPreview(topPadding = 0.dp) {
            MaskingRulesSettingsContentBody(
                uiState = MaskingRulesSettingsUiState(
                    items = listOf(
                        MaskingRuleItem(
                            id = "builtin-default",
                            name = "default",
                            enabled = true,
                            kind = "API_KEY",
                            patterns = listOf("""\bsk-[a-zA-Z0-9_\-]{20,}\b"""),
                        ),
                        MaskingRuleItem(
                            id = "custom-email",
                            name = "邮箱脱敏",
                            enabled = true,
                            kind = "EMAIL",
                            patterns = listOf("""[\w.-]+@[\w.-]+\.\w+"""),
                        ),
                    ),
                ),
                onOpenRuleDetail = { _, _, _, _ -> },
            )
        }
    }
}
