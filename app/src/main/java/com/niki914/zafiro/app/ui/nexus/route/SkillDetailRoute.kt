package com.niki914.zafiro.app.ui.nexus.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.nexus.content.SkillDetailContent
import com.niki914.zafiro.app.ui.nexus.nav.SkillDetailPage

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
