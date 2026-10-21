package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.SavedConfigDetailContent
import com.niki914.zafiro.app.ui.nav.SavedConfigDetailPage
import com.niki914.zafiro.app.ui.nav.SavedConfigPromptEditPage
import com.niki914.zafiro.app.ui.nav.ZafiroPage

@Composable
internal fun SavedConfigDetailRoute(
    page: SavedConfigDetailPage,
    onBack: () -> Unit,
    onPopMultiple: (Int) -> Unit,
    onPush: (ZafiroPage) -> Unit,
) {
    SavedConfigDetailContent(
        page = page,
        onBack = onBack,
        onOpenPromptEdit = {
            onPush(
                SavedConfigPromptEditPage(
                    configId = page.configId,
                    providerId = page.providerId,
                )
            )
        },
        // 新建保存后连 pop 两层，跳过品牌选择页直接回到 Model Configuration 顶级页
        onSaveCompleted = {
            if (page.isCreating) onPopMultiple(2) else onBack()
        },
    )
}
