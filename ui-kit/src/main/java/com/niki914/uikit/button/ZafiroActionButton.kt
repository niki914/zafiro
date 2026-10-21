package com.niki914.uikit.button

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.niki914.uikit.infra.ActionBarButton
import com.niki914.uikit.infra.LocalScreenBackdrop

@Composable
fun ZafiroActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    backdrop: Backdrop? = LocalScreenBackdrop.current,
    onButtonBoundsChanged: ((Rect) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    ActionBarButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        backdrop = backdrop ?: emptyBackdrop(),
        onButtonBoundsChanged = onButtonBoundsChanged,
        content = content,
    )
}
