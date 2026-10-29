package com.niki914.zafiro.app.ui.chat.composable.body.custom

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.niki914.zafiro.app.ui.chat.composable.text.ToolResultText
import com.niki914.zafiro.app.ui.chat.helper.tapToOpenViewer
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.viewer.LocalViewerOpener
import com.niki914.zafiro.app.ui.viewer.ViewerContent

/**
 * 思考过程专属阅读排版：
 * 属于生成式的思考内容，不提供复制按钮；流式更新时支持自动滚到底跟随；
 * 仅在思考完成态下支持轻触卡片打开全屏 Text Viewer，长按可就地划选。
 */
@Composable
internal fun ThinkingBody(
    ui: ActionBodyUi.Custom.Thinking,
    modifier: Modifier = Modifier,
) {
    val openViewer = LocalViewerOpener.current
    val canOpenViewer = ui.isComplete && ui.text.isNotBlank()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .tapToOpenViewer(enabled = canOpenViewer) {
                openViewer(ViewerContent.Text(ui.text))
            },
    ) {
        ToolResultText(
            text = ui.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            autoScrollToEnd = ui.autoScrollToEnd,
        )
    }
}
