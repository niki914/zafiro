package com.niki914.zafiro.app.ui.chat.composable.action

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.zafiro.app.ui.model.home.ActionSource
import com.niki914.zafiro.app.ui.model.home.MessageActionsDisplay

@Composable
fun TurnActionRow(
    source: ActionSource,
    display: MessageActionsDisplay,
    onCopy: () -> Unit,
    onReGenerate: () -> Unit,
    onFork: () -> Unit,
    onRewind: () -> Unit = {},
    branchIndex: Int = 1,
    branchCount: Int = 1,
    onSwitchBranch: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isAgent = source == ActionSource.Agent
    // Always 模式：去背景只留图标，融入背景降噪音
    val iconOnly = display == MessageActionsDisplay.Always

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isAgent) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (iconOnly) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton(
                icon = Icons.Default.ContentCopy,
                contentDescription = "Copy Icon",
                onClick = onCopy,
                iconOnly = iconOnly,
            )
            if (isAgent) {
                ActionButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "Regenerate Icon",
                    onClick = onReGenerate,
                    iconOnly = iconOnly,
                )
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.CallSplit,
                    contentDescription = "Fork Icon",
                    onClick = onFork,
                    iconOnly = iconOnly,
                )
                // 分支翻页器：挂在 Agent 行、排在 Regenerate/Fork 之后
                if (branchCount > 1) {
                    BranchPager(
                        index = branchIndex,
                        count = branchCount,
                        iconOnly = iconOnly,
                        onSwitchBranch = onSwitchBranch,
                    )
                }
            } else {
                ActionButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "Regenerate Icon",
                    onClick = onReGenerate,
                    iconOnly = iconOnly,
                )
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Rewind Icon",
                    onClick = onRewind,
                    iconOnly = iconOnly,
                )
            }
        }
    }
}

/**
 * 重新生成分支翻页器：‹ n/N ›。
 * 与相邻按钮同尺寸同颜色、同样无波纹（与 iconOnly 按钮一致）；
 * 只有一版时不渲染（调用方就不传入）。
 */
@Composable
private fun BranchPager(
    index: Int,
    count: Int,
    iconOnly: Boolean,
    onSwitchBranch: (Int) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val enabledColor = colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
    val disabledColor = colorScheme.onSurfaceVariant.copy(alpha = 0.32f)

    Row(verticalAlignment = Alignment.CenterVertically) {
        PagerChevron(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "Previous Branch",
            enabled = index > 1,
            iconOnly = iconOnly,
            tint = if (index > 1) enabledColor else disabledColor,
            onClick = { onSwitchBranch(-1) },
        )
        Text(
            text = "$index/$count",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = enabledColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        PagerChevron(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Next Branch",
            enabled = index < count,
            iconOnly = iconOnly,
            tint = if (index < count) enabledColor else disabledColor,
            onClick = { onSwitchBranch(1) },
        )
    }
}

@Composable
private fun PagerChevron(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    iconOnly: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    val shape = G2CardShape(14.dp)
    Box(
        modifier = Modifier
            .size(if (iconOnly) 28.dp else 38.dp)
            .then(
                if (iconOnly) {
                    Modifier
                } else {
                    Modifier
                        .clip(shape)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                            shape,
                        )
                }
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                // 翻页器无波纹：去掉点击指示，不从头像式图标堆里弹出一块矩形
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(if (iconOnly) 16.dp else 20.dp),
            tint = tint,
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    iconOnly: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = G2CardShape(14.dp)

    Box(
        modifier = Modifier
            .size(if (iconOnly) 28.dp else 38.dp)
            .then(
                if (iconOnly) {
                    Modifier
                } else {
                    Modifier
                        .clip(shape)
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.72f), shape)
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // iconOnly 无背景：波纹会重新暴露按钮矩形，直接禁用点击指示
                indication = if (iconOnly) null else LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(if (iconOnly) 16.dp else 20.dp),
            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
        )
    }
}
