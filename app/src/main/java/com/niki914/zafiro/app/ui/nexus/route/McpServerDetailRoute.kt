package com.niki914.zafiro.app.ui.nexus.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.nexus.content.mcp.McpServerDetailContent
import com.niki914.zafiro.app.ui.nexus.nav.McpServerDetailPage

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
