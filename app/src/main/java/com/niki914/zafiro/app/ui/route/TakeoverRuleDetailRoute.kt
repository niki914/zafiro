package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.TakeoverRuleDetailContent
import com.niki914.zafiro.app.ui.nav.TakeoverRuleDetailPage

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
