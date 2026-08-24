package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.CustomToolDetailContent
import com.niki914.zafiro.app.ui.nav.CustomToolDetailPage

@Composable
internal fun CustomToolDetailRoute(
    page: CustomToolDetailPage,
    onBack: () -> Unit,
) {
    CustomToolDetailContent(
        page = page,
        onBack = onBack,
    )
}
