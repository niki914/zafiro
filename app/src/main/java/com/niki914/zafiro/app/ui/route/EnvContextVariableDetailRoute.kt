package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.EnvContextVariableDetailContent
import com.niki914.zafiro.app.ui.nav.EnvContextVariableDetailPage

@Composable
internal fun EnvContextVariableDetailRoute(
    page: EnvContextVariableDetailPage,
    onBack: () -> Unit,
) {
    EnvContextVariableDetailContent(
        page = page,
        onBack = onBack,
    )
}
