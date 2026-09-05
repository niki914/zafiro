package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.PromptEditEffect
import com.niki914.zafiro.app.ui.model.PromptEditIntent
import com.niki914.zafiro.app.ui.model.PromptEditViewModel
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges

/**
 * System Prompt 整页编辑页：卡片点击进入，整页编辑 + 显式保存。
 * 骨架复用 [FullScreenContentEditor]（与 Skill 详情页同款）。
 */
@Composable
fun PromptEditContent(
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<PromptEditViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()

    EditableSettingsDetailChrome(
        isCreating = false,
        hasUnsavedChanges = { uiState.hasUnsavedChanges },
        onDiscardChanges = onBack,
    ) {
        FullScreenContentEditor(
            description = stringResource(R.string.prompt_editor_description),
            value = uiState.content,
            enabled = !uiState.isLoading && !uiState.loadError && !uiState.isSaving,
            onValueChange = { value ->
                viewModel.sendIntent(PromptEditIntent.ContentChanged(value))
            },
            actionText = stringResource(R.string.skill_save_action),
            onActionClick = {
                viewModel.sendIntent(PromptEditIntent.Save)
            },
            actionLoading = uiState.isSaving,
            inlineErrorText = if (uiState.loadError) {
                stringResource(R.string.prompt_error_load_failed)
            } else {
                null
            },
        )
    }

    LaunchedEffect(Unit) {
        viewModel.sendIntent(PromptEditIntent.Load)
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                PromptEditEffect.Exit -> onBack()
            }
        }
    }
}
