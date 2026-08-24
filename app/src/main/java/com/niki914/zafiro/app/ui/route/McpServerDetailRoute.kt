package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.mcp.McpServerDetailContent
import com.niki914.zafiro.app.ui.nav.McpServerDetailPage

@Composable
internal fun McpServerDetailRoute(
    page: McpServerDetailPage,
    onBack: () -> Unit,
) {
    McpServerDetailContent(
        page = page,
        onBack = onBack,
    )
}
