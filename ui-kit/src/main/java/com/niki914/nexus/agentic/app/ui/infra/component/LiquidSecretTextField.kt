package com.niki914.nexus.agentic.app.ui.infra.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun LiquidSecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    toggleVisibleContentDescription: String,
    toggleHiddenContentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    moveCursorToEndOnFocus: Boolean = false,
) {
    val contentDescription = if (visible) {
        toggleHiddenContentDescription
    } else {
        toggleVisibleContentDescription
    }

    LiquidTextFieldContainer(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        moveCursorToEndOnFocus = moveCursorToEndOnFocus,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        trailingContent = {
            SecretFieldVisibilityAction(
                visible = visible,
                onToggleVisibility = onToggleVisibility,
                contentDescription = contentDescription,
                enabled = enabled,
            )
        },
    )
}

@Composable
private fun RowScope.SecretFieldVisibilityAction(
    visible: Boolean,
    onToggleVisibility: () -> Unit,
    contentDescription: String,
    enabled: Boolean,
) {
    IconButton(
        onClick = onToggleVisibility,
        enabled = enabled,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = contentDescription,
        )
    }
}
