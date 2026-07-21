package com.niki914.nexus.agentic.app.ui.nexus.content

import android.content.res.Configuration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.BuildConfig
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.ProvideLiquidScreenContentForPreview
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsPageSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsRowAction
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsRowLeadingIcon
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsRowSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSectionLayout
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSectionSpec
import com.niki914.nexus.agentic.app.ui.infra.component.settings.SettingsSpecPageContent
import com.niki914.nexus.agentic.app.ui.infra.nav.pageViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.AboutSettingsEffect
import com.niki914.nexus.agentic.app.ui.nexus.model.AboutSettingsIntent
import com.niki914.nexus.agentic.app.ui.nexus.model.AboutSettingsItemId
import com.niki914.nexus.agentic.app.ui.nexus.model.AboutSettingsItemUiState
import com.niki914.nexus.agentic.app.ui.nexus.model.AboutSettingsUiState
import com.niki914.nexus.agentic.app.ui.nexus.model.AboutSettingsViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.buildIssueUri
import com.niki914.nexus.base.BaseTheme

private const val ABOUT_SETTINGS_ROW_ID_PREFIX = "about.item."

@Composable
fun AboutSettingsContent() {
    val viewModel = pageViewModel<AboutSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    LaunchedEffect(viewModel, uriHandler) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is AboutSettingsEffect.OpenUri -> uriHandler.openUri(effect.uri)
                is AboutSettingsEffect.OpenFeedbackIssue -> {
                    val body = context.resources.getString(effect.bodyTemplateRes)
                    uriHandler.openUri(buildIssueUri(effect.title, body))
                }
            }
        }
    }

    AboutSettingsContentBody(
        uiState = uiState,
        onItemClick = { itemId ->
            viewModel.sendIntent(AboutSettingsIntent.OpenItem(itemId))
        },
    )
}

@Composable
private fun AboutSettingsContentBody(
    uiState: AboutSettingsUiState,
    onItemClick: (AboutSettingsItemId) -> Unit,
) {
    SettingsSpecPageContent(
        spec = aboutSettingsSpec(
            uiState = uiState,
            versionName = BuildConfig.VERSION_NAME,
            description = stringResource(R.string.ui_settings_about_description),
        ),
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Navigate -> {
                    val itemId =
                        aboutSettingsItemIdFromRowId(action.id) ?: return@SettingsSpecPageContent
                    val item = uiState.items.firstOrNull { it.id == itemId }
                        ?: return@SettingsSpecPageContent
                    if (item.canOpen) {
                        onItemClick(itemId)
                    }
                }

                is SettingsRowAction.Click,
                is SettingsRowAction.ToggleChanged -> Unit
            }
        },
    )
}

@Composable
private fun aboutSettingsSpec(
    uiState: AboutSettingsUiState,
    versionName: String,
    description: String,
): SettingsPageSpec {
    val rows = uiState.items.map { item ->
        if (item.canOpen) {
            SettingsRowSpec.Navigation(
                id = aboutSettingsRowId(item.id),
                title = stringResource(item.titleRes),
                leadingIcon = item.leadingIcon(),
            )
        } else {
            SettingsRowSpec.Value(
                title = stringResource(item.titleRes),
                leadingIcon = item.leadingIcon(),
            )
        }
    } + SettingsRowSpec.Value(
        title = stringResource(R.string.ui_settings_about_version),
        currentState = versionName,
    )

    return SettingsPageSpec(
        description = description,
        sections = listOf(
            SettingsSectionSpec(
                layout = SettingsSectionLayout.GroupedCard,
                rows = rows,
            )
        ),
    )
}

private fun aboutSettingsRowId(id: AboutSettingsItemId): String =
    "$ABOUT_SETTINGS_ROW_ID_PREFIX${id.name}"

private fun aboutSettingsItemIdFromRowId(id: String): AboutSettingsItemId? {
    if (!id.startsWith(ABOUT_SETTINGS_ROW_ID_PREFIX)) return null
    val rawId = id.removePrefix(ABOUT_SETTINGS_ROW_ID_PREFIX)
    return AboutSettingsItemId.entries.firstOrNull { it.name == rawId }
}

private fun AboutSettingsItemUiState.leadingIcon(): SettingsRowLeadingIcon {
    return when (id) {
        AboutSettingsItemId.AuthorHomepage,
        AboutSettingsItemId.Github -> SettingsRowLeadingIcon.PainterResource(R.drawable.github)

        AboutSettingsItemId.Afdian -> SettingsRowLeadingIcon.PainterResource(R.drawable.aifadian)
        AboutSettingsItemId.Telegram -> SettingsRowLeadingIcon.PainterResource(R.drawable.telegram)
        AboutSettingsItemId.FeatureFeedback -> SettingsRowLeadingIcon.Vector(Icons.Default.Feedback)
        AboutSettingsItemId.BugFeedback -> SettingsRowLeadingIcon.Vector(Icons.Default.BugReport)
    }
}

@Preview(name = "About Settings Light", showBackground = true, widthDp = 420, heightDp = 900)
@Composable
private fun AboutSettingsLightPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
                AboutSettingsContentBody(
                    uiState = previewAboutSettingsUiState(),
                    onItemClick = {},
                )
            }
        }
    }
}

@Preview(
    name = "About Settings Dark",
    showBackground = true,
    widthDp = 420,
    heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun AboutSettingsDarkPreview() {
    BaseTheme(darkTheme = true, dynamicColor = false) {
        Surface {
            ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
                AboutSettingsContentBody(
                    uiState = previewAboutSettingsUiState(),
                    onItemClick = {},
                )
            }
        }
    }
}

private fun previewAboutSettingsUiState(): AboutSettingsUiState {
    return AboutSettingsUiState(
        items = listOf(
            AboutSettingsItemUiState(
                id = AboutSettingsItemId.AuthorHomepage,
                titleRes = R.string.ui_settings_about_author_homepage,
                uri = "https://github.com/niki914",
            ),
            AboutSettingsItemUiState(
                id = AboutSettingsItemId.Github,
                titleRes = R.string.ui_settings_about_github,
                uri = "https://github.com/niki914/agentic-nexus",
            ),
            AboutSettingsItemUiState(
                id = AboutSettingsItemId.Afdian,
                titleRes = R.string.ui_settings_about_afdian,
                uri = null,
            ),
            AboutSettingsItemUiState(
                id = AboutSettingsItemId.Telegram,
                titleRes = R.string.ui_settings_about_telegram,
                uri = null,
            ),
            AboutSettingsItemUiState(
                id = AboutSettingsItemId.FeatureFeedback,
                titleRes = R.string.ui_settings_about_feedback_feature,
                uri = "https://github.com/niki914/agentic-nexus/issues/new",
            ),
            AboutSettingsItemUiState(
                id = AboutSettingsItemId.BugFeedback,
                titleRes = R.string.ui_settings_about_feedback_bug,
                uri = "https://github.com/niki914/agentic-nexus/issues/new",
            ),
        )
    )
}
