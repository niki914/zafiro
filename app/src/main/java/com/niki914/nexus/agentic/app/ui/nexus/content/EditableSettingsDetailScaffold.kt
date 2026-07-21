package com.niki914.nexus.agentic.app.ui.nexus.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.ConfirmationLiquidDialog
import com.niki914.nexus.agentic.app.ui.infra.component.SettingExpandableTextItem
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsDetailFormScaffold
import com.niki914.nexus.agentic.app.ui.nexus.PageBackHandler
import com.niki914.nexus.agentic.app.ui.nexus.PageChromeContribution
import com.niki914.nexus.agentic.app.ui.nexus.RegisterPageChrome
import com.niki914.nexus.agentic.app.ui.nexus.nav.TopBarActionSpec

@Composable
fun EditableSettingsDetailChrome(
    isCreating: Boolean,
    hasUnsavedChanges: () -> Boolean,
    onDiscardChanges: () -> Unit,
    onDelete: (() -> Unit)? = null,
    rightAction: TopBarActionSpec? = null,
    hasDeleteConfirmation: () -> Boolean = { false },
    onDismissDeleteConfirmation: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val latestHasUnsavedChanges by rememberUpdatedState(hasUnsavedChanges)
    val latestOnDelete by rememberUpdatedState(onDelete)
    val latestRightAction by rememberUpdatedState(rightAction)
    val latestOnDiscardChanges by rememberUpdatedState(onDiscardChanges)
    val latestHasDeleteConfirmation by rememberUpdatedState(hasDeleteConfirmation)
    val latestOnDismissDeleteConfirmation by rememberUpdatedState(onDismissDeleteConfirmation)
    var showUnsavedChangesDialog by rememberSaveable { mutableStateOf(false) }

    val pageChromeContribution = remember(
        isCreating,
        onDelete != null,
        rightAction?.icon,
        rightAction?.contentDescription,
    ) {
        PageChromeContribution(
            rightAction = rightAction?.copy(
                onClick = {
                    latestRightAction?.onClick?.invoke()
                },
            ) ?: if (isCreating || onDelete == null) {
                null
            } else {
                TopBarActionSpec(
                    icon = Icons.Default.Delete,
                    onClick = {
                        latestOnDelete?.invoke()
                    },
                )
            },
            backHandler = PageBackHandler(
                shouldConsumeBack = {
                    latestHasUnsavedChanges() || latestHasDeleteConfirmation()
                },
                onConsumeBack = {
                    if (latestHasDeleteConfirmation()) {
                        latestOnDismissDeleteConfirmation()
                    } else {
                        showUnsavedChangesDialog = true
                    }
                },
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    content()

    ConfirmationLiquidDialog(
        visible = showUnsavedChangesDialog,
        onDismissRequest = {
            showUnsavedChangesDialog = false
        },
        title = stringResource(R.string.unsaved_changes_dialog_title),
        text = stringResource(R.string.unsaved_changes_dialog_text),
        negativeButtonText = stringResource(R.string.unsaved_changes_dialog_cancel),
        positiveButtonText = stringResource(R.string.unsaved_changes_dialog_confirm_exit),
        onNegativeClick = {
            showUnsavedChangesDialog = false
        },
        onPositiveClick = {
            showUnsavedChangesDialog = false
            latestOnDiscardChanges()
        },
    )
}

@Composable
fun <Field : Enum<Field>> rememberEditableDetailFieldController(
    requestedFocusField: Field?,
    onRequestedFocusHandled: () -> Unit,
): EditableDetailFieldController<Field> {
    val focusManager = LocalFocusManager.current
    var expandedField by rememberSaveable { mutableStateOf<Field?>(null) }

    fun clearActiveField() {
        expandedField = null
        focusManager.clearFocus()
    }

    LaunchedEffect(requestedFocusField) {
        if (requestedFocusField != null) {
            expandedField = requestedFocusField
            onRequestedFocusHandled()
        }
    }

    return EditableDetailFieldController(
        expandedField = expandedField,
        onExpandedFieldChange = { field ->
            expandedField = field
        },
        clearActiveField = ::clearActiveField,
    )
}

data class EditableDetailFieldController<Field>(
    val expandedField: Field?,
    val onExpandedFieldChange: (Field?) -> Unit,
    val clearActiveField: () -> Unit,
)

@Composable
fun <Field : Enum<Field>> SettingControlledExpandableTextItem(
    field: Field,
    controller: EditableDetailFieldController<Field>,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    description: String? = null,
    enabled: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    secretVisible: Boolean = false,
    onToggleSecretVisibility: (() -> Unit)? = null,
    toggleSecretVisibleContentDescription: String? = null,
    toggleSecretHiddenContentDescription: String? = null,
) {
    SettingExpandableTextItem(
        title = title,
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        description = description,
        enabled = enabled,
        expanded = controller.expandedField == field,
        minLines = minLines,
        maxLines = maxLines,
        secretVisible = secretVisible,
        onToggleSecretVisibility = onToggleSecretVisibility,
        toggleSecretVisibleContentDescription = toggleSecretVisibleContentDescription,
        toggleSecretHiddenContentDescription = toggleSecretHiddenContentDescription,
        onExpandedChange = { expanded ->
            controller.onExpandedFieldChange(if (expanded) field else null)
        },
    )
}

@Composable
fun <Field : Enum<Field>> EditableSettingsDetailFormScaffold(
    actionText: String,
    requestedFocusField: Field?,
    onRequestedFocusHandled: () -> Unit,
    onActionClick: () -> Unit,
    description: String? = null,
    inlineErrorText: String? = null,
    actionEnabled: Boolean = true,
    content: @Composable (EditableDetailFieldController<Field>) -> Unit,
) {
    val fieldController = rememberEditableDetailFieldController(
        requestedFocusField = requestedFocusField,
        onRequestedFocusHandled = onRequestedFocusHandled,
    )

    SettingsDetailFormScaffold(
        actionText = actionText,
        onActionClick = {
            fieldController.clearActiveField()
            onActionClick()
        },
        description = description,
        inlineErrorText = inlineErrorText,
        actionEnabled = actionEnabled,
        onBackgroundTap = fieldController.clearActiveField,
    ) {
        content(fieldController)
    }
}
