package com.niki914.uikit.dialog

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.niki914.uikit.infra.ConfirmationLiquidDialog

@Composable
fun ZafiroConfirmationDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    text: String,
    negativeButtonText: String,
    positiveButtonText: String,
    onNegativeClick: () -> Unit,
    onPositiveClick: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnBackgroundTap: Boolean = true,
) {
    ConfirmationLiquidDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        negativeButtonText = negativeButtonText,
        positiveButtonText = positiveButtonText,
        onNegativeClick = onNegativeClick,
        onPositiveClick = onPositiveClick,
        modifier = modifier,
        dismissOnBackgroundTap = dismissOnBackgroundTap,
    )
}
