package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.MaskingRuleDetailContent
import com.niki914.zafiro.app.ui.nav.MaskingRuleDetailPage

@Composable
internal fun MaskingRuleDetailRoute(
    page: MaskingRuleDetailPage,
    onBack: () -> Unit,
) {
    MaskingRuleDetailContent(
        page = page,
        onBack = onBack,
    )
}
