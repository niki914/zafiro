package com.niki914.zafiro.app.ui.nexus.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.nexus.content.ExecutionRuleDetailContent
import com.niki914.zafiro.app.ui.nexus.nav.ExecutionRuleDetailPage

@Composable
internal fun ExecutionRuleDetailRoute(
    page: ExecutionRuleDetailPage,
    onBack: () -> Unit,
) {
    ExecutionRuleDetailContent(
        page = page,
        onBack = onBack,
    )
}
