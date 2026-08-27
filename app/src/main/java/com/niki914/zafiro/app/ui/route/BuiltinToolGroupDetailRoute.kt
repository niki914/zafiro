package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.BuiltinToolGroupDetailContent
import com.niki914.zafiro.app.ui.nav.BuiltinToolGroupDetailPage

@Composable
internal fun BuiltinToolGroupDetailRoute(
    page: BuiltinToolGroupDetailPage,
    onBack: () -> Unit,
) {
    BuiltinToolGroupDetailContent(
        page = page,
        onBack = onBack,
    )
}
