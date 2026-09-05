package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.SkillDeleteConfirmationState
import com.niki914.zafiro.app.ui.model.SkillInlineError
import com.niki914.zafiro.app.ui.model.SkillSettingsEffect
import com.niki914.zafiro.app.ui.model.SkillSettingsIntent
import com.niki914.zafiro.app.ui.model.SkillSettingsUiState
import com.niki914.zafiro.app.ui.model.SkillSettingsViewModel
import com.niki914.zafiro.app.ui.model.hasUnsavedChanges
import com.niki914.zafiro.app.ui.nav.SkillDetailPage

@Composable
fun SkillDetailContent(
    page: SkillDetailPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<SkillSettingsViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()

    EditableSettingsDetailChrome(
        isCreating = false,
        hasUnsavedChanges = {
            uiState.formState.hasUnsavedChanges
        },
        onDelete = {
            viewModel.sendIntent(SkillSettingsIntent.RequestDelete)
        },
        onDiscardChanges = onBack,
        hasDeleteConfirmation = {
            uiState.deleteConfirmation != null
        },
        onDismissDeleteConfirmation = {
            viewModel.sendIntent(SkillSettingsIntent.DismissDeleteConfirmation)
        },
    ) {
        SkillDetailContentBody(
            uiState = uiState,
            onContentChange = { value ->
                viewModel.sendIntent(SkillSettingsIntent.ContentChanged(value))
            },
            onSave = {
                viewModel.sendIntent(SkillSettingsIntent.Save)
            },
        )

        SkillDeleteConfirmationDialog(
            state = uiState.deleteConfirmation,
            onDismissRequest = {
                viewModel.sendIntent(SkillSettingsIntent.DismissDeleteConfirmation)
            },
            onConfirmClick = {
                viewModel.sendIntent(SkillSettingsIntent.ConfirmDelete)
            },
        )
    }

    LaunchedEffect(page.routeKey) {
        viewModel.sendIntent(
            SkillSettingsIntent.LoadDetail(
                id = page.skillId,
                fallbackTitle = page.skillTitle,
            )
        )
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                SkillSettingsEffect.ExitDetail -> onBack()
                else -> {}
            }
        }
    }
}

@Composable
private fun SkillDetailContentBody(
    uiState: SkillSettingsUiState,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    val detailLoaded = uiState.formState.skillId.isNotBlank() &&
            uiState.inlineError !is SkillInlineError.LoadFailed

    FullScreenContentEditor(
        description = stringResource(R.string.skill_editor_description),
        value = uiState.formState.content,
        enabled = detailLoaded && !uiState.isSaving && !uiState.isLoading,
        onValueChange = onContentChange,
        actionText = stringResource(R.string.skill_save_action),
        onActionClick = onSave,
        actionLoading = uiState.isSaving,
        inlineErrorText = skillInlineErrorText(uiState.inlineError),
    )
}

@Composable
private fun skillInlineErrorText(error: SkillInlineError?): String? {
    return when (error) {
        null -> null
        is SkillInlineError.LoadFailed -> stringResource(
            R.string.skill_error_load_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is SkillInlineError.SaveFailed -> stringResource(
            R.string.skill_error_save_failed,
            error.message ?: stringResource(error.fallbackResId),
        )

        is SkillInlineError.DeleteFailed -> stringResource(
            R.string.skill_error_delete_failed,
            error.message ?: stringResource(error.fallbackResId),
        )
    }
}

@Composable
private fun SkillDeleteConfirmationDialog(
    state: SkillDeleteConfirmationState?,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    ConfirmationLiquidDialog(
        visible = state != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.skill_delete_dialog_title),
        text = stringResource(R.string.skill_delete_dialog_text, state?.title.orEmpty()),
        negativeButtonText = stringResource(R.string.delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}
