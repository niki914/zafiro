package com.niki914.zafiro.app.ui.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 聊天块统一间距：块内容与头部之间、多工具链行之间、turn 内块之间全部用同一个值。
 * 目前临时定为 24dp；调整时只改这一处。
 */
internal val BlockSpacing = 24.dp

/** 展开正文颜色
 * （thinking/skill 兜底共用：onSurfaceVariant.copy(alpha = 这里)）；
 * 取值需浅于折叠行（0.7）。调色只改这一处。
 */
internal val BlockBodyAlpha = 0.6f

/**
 * THINKING / TOOLING 共用折叠块：左图标 + 标题（过长右侧省略），点击展开；头部相对正文内收 6dp。
 * 展开内容左缘对齐 icon 中线、右缘对齐箭头中线（比头部更宽，展示更多内容）。
 * [isRunning] 为 true 时右上角显示 spinner（工具执行中），否则显示箭头。
 * 显隐动画：纯匀速展开/收缩（无 fade、无弹性），短时长。
 *
 * 折叠行变浅方案：不换色 token，前景统一 onSurfaceVariant.copy(alpha=0.7f)（淡文本色降透明度），
 * 字号 labelLarge；展开内容相应收敛（padding 12dp），保持"内容比行大"的层级。
 */
@Composable
fun CollapsibleBlock(
    icon: ImageVector,
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    isRunning: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    // 折叠行前景：淡文本色降透明度（用户选定：只调 alpha 变浅，不换 token）
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val chevron by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "chevron",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                )
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.5.dp,
                    color = contentColor.copy(alpha = 0.6f),
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = chevron },
                )
            }
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(animationSpec = tween(160, easing = LinearEasing)),
            exit = shrinkVertically(animationSpec = tween(160, easing = LinearEasing)),
        ) {
            // 展开内容：左缘对齐 icon 中线（6 + 13/2 ≈ 12.5）、右缘对齐箭头中线（6 + 14/2 = 13）
            // 统一 12dp；top = BlockSpacing：头部与内容间间距与块间/行间一致（padding 放在收起态高度为 0 的内容内，折叠时不产生幻影空隙）
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = BlockSpacing)) {
                content()
            }
        }
    }
}