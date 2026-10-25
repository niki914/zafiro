package com.niki914.uikit.menu

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.Backdrop
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.LiquidPopover
import com.niki914.uikit.infra.LocalScreenBackdrop
import com.niki914.uikit.infra.PopoverAlignment

typealias ZafiroMenuAlignment = PopoverAlignment

@Composable
fun ZafiroDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    anchorBounds: Rect? = null,
    alignment: ZafiroMenuAlignment = ZafiroMenuAlignment.BottomEnd,
    containerColor: Color? = null,
    scrollable: Boolean = false,
    onProgressChanged: ((Float) -> Unit)? = null,
    backdrop: Backdrop? = LocalScreenBackdrop.current,
    content: @Composable ColumnScope.() -> Unit,
) {
    when (LocalThemeStyle.current) {
        ThemeStyle.LiquidGlass -> {
            LiquidPopover(
                expanded = expanded,
                onDismissRequest = onDismissRequest,
                anchorBounds = anchorBounds,
                modifier = modifier,
                alignment = alignment,
                containerColor = containerColor,
                scrollable = scrollable,
                onProgressChanged = onProgressChanged,
                backdrop = backdrop,
                content = content,
            )
        }
        ThemeStyle.Material3,
        ThemeStyle.Material3Expressive -> {
            MaterialDropdownMenu(
                expanded = expanded,
                onDismissRequest = onDismissRequest,
                modifier = modifier,
                anchorBounds = anchorBounds,
                alignment = alignment,
                containerColor = containerColor,
                scrollable = scrollable,
                onProgressChanged = onProgressChanged,
                content = content,
            )
        }
    }
}
