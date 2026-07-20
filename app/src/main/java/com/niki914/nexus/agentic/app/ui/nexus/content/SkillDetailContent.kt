package com.niki914.nexus.agentic.app.ui.nexus.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.ConfirmationLiquidDialog
import com.niki914.nexus.agentic.app.ui.infra.component.PageDescriptionText
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsDetailPageDefaults
import com.niki914.nexus.agentic.app.ui.infra.component.TintLiquidButton
import com.niki914.nexus.agentic.app.ui.infra.liquidScreenHazeSource
import com.niki914.nexus.agentic.app.ui.infra.liquidScreenTopPadding
import com.niki914.nexus.agentic.app.ui.infra.nav.pageViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.SkillDeleteConfirmationState
import com.niki914.nexus.agentic.app.ui.nexus.model.SkillInlineError
import com.niki914.nexus.agentic.app.ui.nexus.model.SkillSettingsEffect
import com.niki914.nexus.agentic.app.ui.nexus.model.SkillSettingsIntent
import com.niki914.nexus.agentic.app.ui.nexus.model.SkillSettingsUiState
import com.niki914.nexus.agentic.app.ui.nexus.model.SkillSettingsViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.hasUnsavedChanges
import com.niki914.nexus.agentic.app.ui.nexus.nav.SkillDetailPage

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .liquidScreenHazeSource(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = SettingsDetailPageDefaults.HorizontalPadding)
                .padding(
                    top = liquidScreenTopPadding(SettingsDetailPageDefaults.VerticalPadding),
                    bottom = SettingsDetailPageDefaults.VerticalPadding +
                            SettingsDetailPageDefaults.RootVerticalSpacing +
                            SettingsDetailPageDefaults.ActionButtonReservedHeight,
                ),
            verticalArrangement = Arrangement.spacedBy(
                SettingsDetailPageDefaults.ContentVerticalSpacing,
            ),
        ) {
            PageDescriptionText(text = stringResource(R.string.skill_editor_description))
            SkillContentEditor(
                value = uiState.formState.content,
                enabled = detailLoaded && !uiState.isSaving && !uiState.isLoading,
                onValueChange = onContentChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            skillInlineErrorText(uiState.inlineError)?.let { errorText ->
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(
                        horizontal = SettingsDetailPageDefaults.InlineErrorHorizontalPadding,
                    ),
                )
            }
        }

        TintLiquidButton(
            text = stringResource(R.string.skill_save_action),
            enabled = detailLoaded && !uiState.isSaving && !uiState.isLoading,
            isLoading = uiState.isSaving,
            onClick = onSave,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(
                    start = SettingsDetailPageDefaults.HorizontalPadding,
                    end = SettingsDetailPageDefaults.HorizontalPadding,
                    bottom = SettingsDetailPageDefaults.VerticalPadding,
                ),
        )
    }
}

@Composable
private fun SkillContentEditor(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
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
