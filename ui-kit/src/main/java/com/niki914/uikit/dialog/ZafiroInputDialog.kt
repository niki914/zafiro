package com.niki914.uikit.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.niki914.uikit.textfield.ZafiroTextField
import kotlinx.coroutines.delay

@Composable
fun ZafiroInputDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    positiveButtonText: String,
    onPositiveClick: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    negativeButtonText: String = stringResource(android.R.string.cancel),
    onNegativeClick: () -> Unit = onDismissRequest,
    positiveEnabled: Boolean = true,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    dismissOnBackgroundTap: Boolean = true,
) {
    var retainedValue by remember { mutableStateOf(value) }
    LaunchedEffect(visible, value) {
        if (visible) retainedValue = value
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(visible) {
        if (visible) {
            delay(100)
            focusRequester.requestFocus()
        }
    }

    ZafiroDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        dismissOnBackgroundTap = dismissOnBackgroundTap,
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hint != null) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            }
        },
        content = {
            ZafiroTextField(
                value = if (visible) value else retainedValue,
                onValueChange = onValueChange,
                placeholder = placeholder,
                enabled = enabled,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = maxLines,
                moveCursorToEndOnFocus = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .focusRequester(focusRequester),
            )
        },
        actions = {
            ZafiroDialogButton(
                text = negativeButtonText,
                onClick = onNegativeClick,
                isPrimary = false,
            )
            ZafiroDialogButton(
                text = positiveButtonText,
                onClick = onPositiveClick,
                enabled = positiveEnabled,
                isPrimary = true,
            )
        },
    )
}
