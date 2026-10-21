package com.niki914.zafiro.app.ui.chat.composable.action

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.zafiro.app.ui.model.home.ActionSource

@Composable
fun TurnActionRow(
    source: ActionSource,
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

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isAgent) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton(
                icon = Icons.Default.ContentCopy,
                contentDescription = "Copy Icon",
                onClick = onCopy,
            )
            if (isAgent) {
                ActionButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "Regenerate Icon",
                    onClick = onReGenerate,
                )
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.CallSplit,
                    contentDescription = "Fork Icon",
                    onClick = onFork,
                )
                // 分支翻页器：挂在 Agent 行、排在 Regenerate/Fork 之后
                if (branchCount > 1) {
                    BranchPager(
                        index = branchIndex,
                        count = branchCount,
                        onSwitchBranch = onSwitchBranch,
                    )
                }
            } else {
                ActionButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "Regenerate Icon",
                    onClick = onReGenerate,
                )
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Rewind Icon",
                    onClick = onRewind,
                )
            }
        }
    }
}

/**
 * 重新生成分支翻页器：‹ n/N ›。
 * 紧凑无背景、无波纹；只有一版时不渲染。
 */
@Composable
private fun BranchPager(
    index: Int,
    count: Int,
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
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = tint,
        )
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(16.dp),
            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
        )
    }
}
