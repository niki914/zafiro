package com.niki914.zafiro.app.ui.chat.composable.action

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.zafiro.app.ui.chat.composable.body.ActionBodyDispatcher
import com.niki914.zafiro.app.ui.chat.model.ActionContainerTone
import com.niki914.zafiro.app.ui.chat.model.ActionUiModel
import com.niki914.zafiro.app.ui.content.BlockContainerPadY
import com.niki914.zafiro.app.ui.content.BlockContentBottomGap
import com.niki914.zafiro.app.ui.content.BlockContentInset
import com.niki914.zafiro.app.ui.content.BlockContentTopGap
import com.niki914.zafiro.app.ui.content.BlockHeaderInsetCollapsed
import com.niki914.zafiro.app.ui.content.BlockHeaderInsetExpanded

/** 折叠常态圆角（偏胶囊的对话气泡感） */
private val BlockRadiusCollapsed = 24.dp

/** 按压圆角：向胶囊进一步形变，反馈「可按」 */
private val BlockRadiusPressed = 30.dp

/** 展开圆角：容器变大后取更平的圆角，呈现「内容住进容器」 */
private val BlockRadiusExpanded = 18.dp

/**
 * 外部独立展示卡片：100% 对齐 [CollapsibleBlock] 物理形变与规范。
 * 拥有独立 G2FieldShape 圆角卡片背景与按压缩放反馈；头部与展开内容严格解耦。
 */
@Composable
internal fun StandaloneActionCard(
    action: ActionUiModel,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding: PaddingValues = PaddingValues(
        start = BlockContentInset,
        end = BlockContentInset,
        top = BlockContentTopGap,
        bottom = BlockContentBottomGap,
    ),
    content: @Composable ColumnScope.() -> Unit = { ActionBodyDispatcher(ui = action.body) },
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val radiusDp by animateDpAsState(
        targetValue = when {
            pressed && !action.nonCollapsible -> BlockRadiusPressed
            action.isExpanded || action.nonCollapsible -> BlockRadiusExpanded
            else -> BlockRadiusCollapsed
        },
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "standaloneRadius",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed && !action.nonCollapsible) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "standaloneScale",
    )
    val surfaceAlpha by animateFloatAsState(
        targetValue = when {
            action.isExpanded || action.nonCollapsible -> 1f
            pressed -> 0.65f
            else -> 0f
        },
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
        label = "standaloneAlpha",
    )
    val headerInset by animateDpAsState(
        targetValue = if (action.isExpanded || action.nonCollapsible) BlockHeaderInsetExpanded else BlockHeaderInsetCollapsed,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "standaloneInset",
    )

    val shape = remember(radiusDp) { G2FieldShape(radiusDp) }

    val resolvedContainerColor = when (action.containerTone) {
        ActionContainerTone.Tertiary -> MaterialTheme.colorScheme.tertiaryContainer
        ActionContainerTone.Default -> containerColor
    }

    val canToggle = !action.nonCollapsible
    val isExpanded = action.isExpanded

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(resolvedContainerColor.copy(alpha = surfaceAlpha), shape)
            .then(
                if (!isExpanded && canToggle) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = action.onToggle,
                    )
                } else {
                    Modifier
                }
            )
            .padding(vertical = BlockContainerPadY),
    ) {
        ActionHeader(
            action = action,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isExpanded && canToggle) {
                        Modifier.clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = action.onToggle,
                        )
                    } else {
                        Modifier
                    }
                )
                .padding(horizontal = headerInset),
        )

        AnimatedVisibility(
            visible = action.isExpanded,
            enter = expandVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
            ) + fadeIn(
                animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
            ),
            exit = shrinkVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow),
            ) + fadeOut(
                animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
            ) {
                content()
            }
        }
    }
}
