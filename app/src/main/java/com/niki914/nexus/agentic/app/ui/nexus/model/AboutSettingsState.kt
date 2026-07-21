package com.niki914.nexus.agentic.app.ui.nexus.model

import android.net.Uri
import androidx.annotation.StringRes
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.base.ComposeMVIViewModel

enum class AboutSettingsItemId {
    AuthorHomepage,
    Github,
    Afdian,
    Telegram,
    FeatureFeedback,
    BugFeedback,
}

data class AboutSettingsItemUiState(
    val id: AboutSettingsItemId,
    @StringRes val titleRes: Int,
    val uri: String?,
    @StringRes val bodyTemplateRes: Int? = null,
    val feedbackTitle: String? = null,
) {
    val canOpen: Boolean
        get() = uri != null || bodyTemplateRes != null
}

data class AboutSettingsUiState(
    val items: List<AboutSettingsItemUiState> = emptyList(),
)

sealed interface AboutSettingsIntent {
    data class OpenItem(val id: AboutSettingsItemId) : AboutSettingsIntent
}

sealed interface AboutSettingsEffect {
    data class OpenUri(val uri: String) : AboutSettingsEffect
    data class OpenFeedbackIssue(
        val title: String,
        @StringRes val bodyTemplateRes: Int,
    ) : AboutSettingsEffect
}

class AboutSettingsViewModel :
    ComposeMVIViewModel<AboutSettingsIntent, AboutSettingsUiState, AboutSettingsEffect>() {

    override fun initUiState(): AboutSettingsUiState {
        return AboutSettingsUiState(items = aboutSettingsItems())
    }

    override suspend fun handleIntent(intent: AboutSettingsIntent) {
        when (intent) {
            is AboutSettingsIntent.OpenItem -> openItem(intent.id)
        }
    }

    private fun openItem(id: AboutSettingsItemId) {
        val item = currentState.items.firstOrNull { it.id == id } ?: return
        when {
            item.uri != null -> sendEffect(AboutSettingsEffect.OpenUri(item.uri))
            item.bodyTemplateRes != null && item.feedbackTitle != null ->
                sendEffect(AboutSettingsEffect.OpenFeedbackIssue(item.feedbackTitle, item.bodyTemplateRes))
        }
    }
}

private fun aboutSettingsItems(): List<AboutSettingsItemUiState> {
    return listOf(
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
            uri = "https://afdian.com/a/niki914",
        ),
        AboutSettingsItemUiState(
            id = AboutSettingsItemId.Telegram,
            titleRes = R.string.ui_settings_about_telegram,
            uri = "https://t.me/+ZPX2xtSl6RwyZGNl",
        ),
        AboutSettingsItemUiState(
            id = AboutSettingsItemId.FeatureFeedback,
            titleRes = R.string.ui_settings_about_feedback_feature,
            uri = null,
            bodyTemplateRes = R.string.feedback_feature_template,
            feedbackTitle = FEATURE_FEEDBACK_TITLE,
        ),
        AboutSettingsItemUiState(
            id = AboutSettingsItemId.BugFeedback,
            titleRes = R.string.ui_settings_about_feedback_bug,
            uri = null,
            bodyTemplateRes = R.string.feedback_bug_template,
            feedbackTitle = BUG_FEEDBACK_TITLE,
        ),
    )
}

internal fun buildIssueUri(title: String, body: String): String {
    return Uri.Builder()
        .scheme("https")
        .authority("github.com")
        .path("/niki914/agentic-nexus/issues/new")
        .appendQueryParameter("title", title)
        .appendQueryParameter("body", body)
        .build()
        .toString()
}

private const val FEATURE_FEEDBACK_TITLE = "[FEATURE] "
private const val BUG_FEEDBACK_TITLE = "[BUG] "
