package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.nav.ViewerPage
import com.niki914.zafiro.app.ui.viewer.ViewerPageContent

@Composable
internal fun ViewerPageRoute(
    page: ViewerPage,
    onBack: () -> Unit,
) {
    ViewerPageContent(
        content = page.content,
        onBack = onBack,
    )
}
