package com.niki914.zafiro.app.ui.chat.composable.body.custom

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.content.BlockSpacing
import com.niki914.zafiro.app.ui.chat.composable.attachment.HomeChatImageCard

private val ToolImagePreviewSize = 96.dp

/**
 * 工具返回的图片预览排版：
 * 展示落盘图缩略卡，不提供文本复制按钮。
 */
@Composable
internal fun ImagePreviewBody(
    ui: ActionBodyUi.Custom.ImagePreview,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BlockSpacing, Alignment.CenterHorizontally),
    ) {
        ui.images.forEach { image ->
            HomeChatImageCard(
                image = image,
                size = ToolImagePreviewSize,
                cornerRadius = 18.dp,
                onRemove = null,
            )
        }
    }
}
