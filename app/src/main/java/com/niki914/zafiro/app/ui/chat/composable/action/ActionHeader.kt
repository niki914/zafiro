package com.niki914.zafiro.app.ui.chat.composable.action

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.zafiro.app.ui.chat.helper.MiniCopyButton
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi
import com.niki914.zafiro.app.ui.chat.model.ActionUiModel
import com.niki914.zafiro.app.ui.content.BlockCircularLoaderSize
import com.niki914.zafiro.app.ui.content.BlockIconDot
import com.niki914.zafiro.app.ui.content.BlockLoaderSize
import com.niki914.zafiro.app.ui.content.BlockRowChevron
import com.niki914.zafiro.app.ui.content.BlockRowGap
import com.niki914.zafiro.app.ui.content.BlockRowIcon
import com.niki914.zafiro.app.ui.model.ToolPresentation

/**
 * 同构 Action Header 基建：100% 对齐 [CollapsibleBlock] Header 布局与色彩。
 *
 * 核心护栏：
 * 1. 工具失败时 Header 绝对不染红，左图标圆底与文字永远保持统一色调；
 * 2. 左右槽位对称，底色统一为 primary.copy(alpha = 0.12f)；
 * 3. nonCollapsible = true 时右侧槽位不渲染 Chevron。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ActionHeader(
    action: ActionUiModel,
    modifier: Modifier = Modifier,
) {
    val themeStyle = LocalThemeStyle.current
    val isExpressive = themeStyle == ThemeStyle.Material3Expressive

    val chevronRotation by animateFloatAsState(
        targetValue = if (action.isExpanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "actionChevron",
    )
    val titleColor by animateColorAsState(
        targetValue = if (action.isExpanded) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        },
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
        label = "actionTitle",
    )

    val titleText = buildString {
        append(action.titleRes?.let { stringResource(it) } ?: action.title.orEmpty())
        action.subtitlePreview?.let { preview ->
            append(" · ")
            append(preview)
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧：24dp tonal 圆底与图标（失败时坚决不染红）
        Box(
            modifier = Modifier
                .size(BlockIconDot)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            when (val icon = action.iconSource) {
                is ToolPresentation.IconSource.Vector -> {
                    Icon(
                        imageVector = icon.vector,
                        contentDescription = null,
                        modifier = Modifier.size(BlockRowIcon),
                        tint = titleColor,
                    )
                }
                is ToolPresentation.IconSource.DrawableRes -> {
                    Icon(
                        painter = painterResource(icon.resId),
                        contentDescription = null,
                        modifier = Modifier.size(BlockRowIcon),
                        tint = titleColor,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(BlockRowGap))

        // 中间：标题与单行预览（失败时坚决不染红）
        Text(
            text = titleText,
            style = MaterialTheme.typography.labelLarge,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(BlockRowGap))

        // 右侧槽位：tonal 圆底与左图标对称，内容在 chevron（完成）↔ loader（运行中）间切换；
        // 不可折叠形态（如错误卡片）若有可复制内容则在右上角直接展示复制按钮，否则留白对齐
        val copyableErrorText = (action.body as? ActionBodyUi.Custom.ErrorDetail)
            ?.takeIf { it.showCopyButton && !it.body.isNullOrBlank() }
            ?.body

        if (action.nonCollapsible) {
            if (copyableErrorText != null) {
                Box(
                    modifier = Modifier.size(BlockIconDot),
                    contentAlignment = Alignment.Center,
                ) {
                    MiniCopyButton(
                        text = copyableErrorText,
                    )
                }
            } else {
                Spacer(Modifier.size(BlockIconDot))
            }
        } else {
            Box(
                modifier = Modifier
                    .size(BlockIconDot)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (action.isRunning) {
                    if (isExpressive) {
                        LoadingIndicator(
                            modifier = Modifier.size(BlockLoaderSize),
                            color = titleColor,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(BlockCircularLoaderSize),
                            color = titleColor,
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = titleColor.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(BlockRowChevron)
                            .graphicsLayer { rotationZ = chevronRotation },
                    )
                }
            }
        }
    }
}
