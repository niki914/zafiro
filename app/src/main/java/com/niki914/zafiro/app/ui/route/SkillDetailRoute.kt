package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.SkillDetailContent
import com.niki914.zafiro.app.ui.nav.SkillDetailPage

@Composable
internal fun SkillDetailRoute(
    page: SkillDetailPage,
    onBack: () -> Unit,
) {
    SkillDetailContent(
        page = page,
        onBack = onBack,
    )
}
