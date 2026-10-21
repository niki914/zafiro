package com.niki914.zafiro.app.ui.chat.composable.body.custom

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.chat.composable.text.ToolResultText

/**
 * 思考过程专属阅读排版：
 * 属于生成式的思考内容，不提供复制按钮；流式更新时支持自动滚到底跟随。
 */
@Composable
internal fun ThinkingBody(
    ui: ActionBodyUi.Custom.Thinking,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        ToolResultText(
            text = ui.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            autoScrollToEnd = ui.autoScrollToEnd,
        )
    }
}
