package com.niki914.uikit.textfield

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kyant.backdrop.Backdrop
import com.niki914.uikit.infra.LiquidGlassPresets
import com.niki914.uikit.infra.LocalScreenBackdrop
import com.niki914.uikit.infra.component.LiquidSecretTextField

@Composable
fun ZafiroSecretTextField(
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
    backdrop: Backdrop? = LocalScreenBackdrop.current,
) {
    LiquidSecretTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        visible = visible,
        onToggleVisibility = onToggleVisibility,
        toggleVisibleContentDescription = toggleVisibleContentDescription,
        toggleHiddenContentDescription = toggleHiddenContentDescription,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        moveCursorToEndOnFocus = moveCursorToEndOnFocus,
        backdrop = backdrop,
        glassStyle = LiquidGlassPresets.TextField,
    )
}
