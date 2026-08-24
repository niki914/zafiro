package com.niki914.zafiro.app.ui.nexus.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.nexus.content.TakeoverRuleDetailContent
import com.niki914.zafiro.app.ui.nexus.nav.TakeoverRuleDetailPage

@Composable
internal fun TakeoverRuleDetailRoute(
    page: TakeoverRuleDetailPage,
    onBack: () -> Unit,
) {
    TakeoverRuleDetailContent(
        page = page,
        onBack = onBack,
    )
}
