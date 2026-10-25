package com.niki914.uikit.dialog

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.LiquidDialog

@Composable
fun ZafiroDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissOnBackgroundTap: Boolean = true,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    when (LocalThemeStyle.current) {
        ThemeStyle.LiquidGlass -> {
            LiquidDialog(
                visible = visible,
                onDismissRequest = onDismissRequest,
                modifier = modifier,
                dismissOnBackgroundTap = dismissOnBackgroundTap,
                title = title,
                text = text,
                actions = actions,
                content = content,
            )
        }
        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            MaterialDialog(
                visible = visible,
                onDismissRequest = onDismissRequest,
                modifier = modifier,
                dismissOnBackgroundTap = dismissOnBackgroundTap,
                title = title,
                text = text,
                actions = actions,
                content = content,
            )
        }
    }
}
