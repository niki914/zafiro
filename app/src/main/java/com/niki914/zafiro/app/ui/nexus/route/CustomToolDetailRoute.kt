package com.niki914.zafiro.app.ui.nexus.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.nexus.content.CustomToolDetailContent
import com.niki914.zafiro.app.ui.nexus.nav.CustomToolDetailPage

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
