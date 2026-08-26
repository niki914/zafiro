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

// ── 块 UI 密度参数表（折叠块 + 命令型工具正文 + turn 分隔共用）────────────────
// 调视觉密度只改这一处；使用处按名引用或组合计算（如 TurnSeparator - BlockSpacing），
// 不要散落魔法数字。
internal val TurnSeparator = 32.dp    // turn 分隔总间距：UserMsg→agent 内容（补差 TurnSeparator - BlockSpacing）与 跨 turn（上一 turn 末尾→下一 UserMsg，LazyColumn item 顶距）共用
internal val BlockSpacing = 12.dp      // 统一块间距：turn 内块间、头部↔展开内容、多工具链行间、命令↔输出缝隙
// ======
internal val BlockRowInset = 6.dp     // 折叠行左右内收
internal val BlockContentInset = 12.dp // 展开内容左右内收（比头部宽，展示更多）
internal val BlockRowGap = 8.dp       // 折叠行 icon↔标题、标题↔箭头
internal val BlockRowIcon = 13.dp     // 折叠行图标
internal val BlockRowChevron = 14.dp  // 折叠行箭头
internal val BlockSpinnerSize = 11.dp // 运行中 spinner
internal val CommandPanelPadX = 12.dp // 命令面板左右内收
internal val CopyBtnGap = 6.dp        // 面板内容 / 文本 ↔ 复制按钮 的邻近间隙
internal val CommandRowPadY = 4.dp    // 命令行纵向
internal val OutputPadY = 6.dp        // 输出区纵向
internal val OutputBtnInset = 36.dp   // 多行输出右缘给右上角按钮让位
internal val CopyBtnSize = 26.dp      // 复制按钮
internal val CopyIconSize = 14.dp     // 复制图标
internal val ResultScrollMaxHeight = 102.dp // 工具结果/思考正文滚动高度上限

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
                .padding(horizontal = BlockRowInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(BlockRowIcon),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(BlockRowGap))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(BlockRowGap))
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(BlockSpinnerSize),
                    strokeWidth = 1.5.dp,
                    color = contentColor.copy(alpha = 0.6f),
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.6f),
                    modifier = Modifier
                        .size(BlockRowChevron)
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
            Column(modifier = Modifier.padding(start = BlockContentInset, end = BlockContentInset, top = BlockSpacing)) {
                content()
            }
        }
    }
}