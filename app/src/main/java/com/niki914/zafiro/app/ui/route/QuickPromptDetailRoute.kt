package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.QuickPromptDetailContent
import com.niki914.zafiro.app.ui.nav.QuickPromptDetailPage

@Composable
internal fun QuickPromptDetailRoute(
    page: QuickPromptDetailPage,
    onBack: () -> Unit,
) {
    QuickPromptDetailContent(
        page = page,
        onBack = onBack,
    )
}
