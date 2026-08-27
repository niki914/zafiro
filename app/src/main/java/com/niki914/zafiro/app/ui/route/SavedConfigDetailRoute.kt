package com.niki914.zafiro.app.ui.route

import androidx.compose.runtime.Composable
import com.niki914.zafiro.app.ui.content.SavedConfigDetailContent
import com.niki914.zafiro.app.ui.nav.SavedConfigDetailPage

@Composable
internal fun SavedConfigDetailRoute(
    page: SavedConfigDetailPage,
    onBack: () -> Unit,
    onPopMultiple: (Int) -> Unit,
) {
    SavedConfigDetailContent(
        page = page,
        onBack = onBack,
        // 新建保存后连 pop 两层，跳过品牌选择页直接回到 Model Configuration 顶级页
        onSaveCompleted = {
            if (page.isCreating) onPopMultiple(2) else onBack()
        },
    )
}
