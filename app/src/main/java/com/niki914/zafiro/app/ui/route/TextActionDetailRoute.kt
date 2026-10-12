package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.TextActionsDetailContent
import com.niki914.zafiro.app.ui.nav.TextActionDetailPage

@Composable
internal fun TextActionDetailRoute(
    page: TextActionDetailPage,
    onBack: () -> Unit,
) {
    TextActionsDetailContent(
        page = page,
        onBack = onBack,
    )
}
