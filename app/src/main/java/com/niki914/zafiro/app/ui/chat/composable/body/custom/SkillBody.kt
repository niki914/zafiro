package com.niki914.zafiro.app.ui.chat.composable.body.custom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.chat.composable.text.ToolResultText

private val CommandPanelPadX = 14.dp
private val OutputPadY = 8.dp

/**
 * 技能（load_skill）专属阅读排版：
 * 属于有意义的内容阅读卡片，自身全权负责布局，不提供复制按钮。
 */
@Composable
internal fun SkillBody(
    ui: ActionBodyUi.Custom.SkillContent,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(16.dp),
            )
            .padding(horizontal = CommandPanelPadX, vertical = OutputPadY),
    ) {
        ToolResultText(
            text = ui.markdown,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
