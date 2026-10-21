package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.nav.parentPageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ConfigureIntent
import com.niki914.zafiro.app.ui.model.ConfigureViewModel
import com.niki914.zafiro.app.ui.nav.SavedConfigDetailPage
import com.niki914.zafiro.app.ui.nav.SavedConfigPromptEditPage
import com.niki914.zafiro.app.ui.settings.form.EditableSettingsDetailChrome

/**
 * 模型配置专属独立 System Prompt 编辑页。
 * 骨架复用 [FullScreenContentEditor]，状态绑定至父级 [ConfigureViewModel]。
 * 留空则回退至全局 Prompt，保存后随当前模型配置生效。
 */
@Composable
fun SavedConfigPromptEditContent(
    page: SavedConfigPromptEditPage,
    onBack: () -> Unit,
) {
    val parentKey = "saved-config-detail:${page.configId ?: page.providerId}"
    val configureViewModel = parentPageViewModel<ConfigureViewModel>(
        key = parentKey,
        predicate = { it is SavedConfigDetailPage },
    ) ?: error("Parent ConfigureViewModel not found for $parentKey")

    val configureUiState by configureViewModel.uiStateFlow.collectAsState()

    var localContent by rememberSaveable {
        mutableStateOf(configureUiState.promptInput)
    }

    val hasUnsavedChanges = localContent != configureUiState.promptInput

    EditableSettingsDetailChrome(
        isCreating = false,
        hasUnsavedChanges = { hasUnsavedChanges },
        unsavedFieldLabelRes = {
            if (hasUnsavedChanges) listOf(R.string.ui_settings_configure_prompt_label) else emptyList()
        },
        onDiscardChanges = onBack,
    ) {
        FullScreenContentEditor(
            description = stringResource(R.string.config_prompt_editor_description),
            value = localContent,
            enabled = !configureUiState.isSaving,
            onValueChange = { localContent = it },
            actionText = stringResource(R.string.skill_save_action),
            onActionClick = {
                configureViewModel.sendIntent(ConfigureIntent.UpdatePrompt(localContent))
                onBack()
            },
            actionLoading = false,
            inlineErrorText = null,
        )
    }
}
