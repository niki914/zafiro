package com.niki914.zafiro.app.ui.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.shape.G2FieldShape

// ── 块 UI 密度参数表（折叠块 + 命令型工具正文 + turn 分隔共用）────────────────
// 调视觉密度只改这一处；使用处按名引用或组合计算（如 TurnSeparator - BlockSpacing），
// 不要散落魔法数字。
internal val TurnSeparator =
    42.dp    // turn 分隔总间距：UserMsg→agent 内容（补差 TurnSeparator - BlockSpacing）与 跨 turn（上一 turn 末尾→下一 UserMsg，LazyColumn item 顶距）共用
internal val BlockSpacing = 12.dp      // 统一块间距：turn 内块间、头部↔展开内容、多工具链行间、命令↔输出缝隙
internal val UserBubbleGap = 3.dp      // 连续 User 气泡组内间隙（纯 User turn 间 LazyColumn item 顶距）

// ======
internal val BlockHeaderInsetCollapsed = 16.dp // 呼吸：收起态 header 水平内收（收得深）
internal val BlockHeaderInsetExpanded = 12.dp  // 呼吸：展开态 header 水平放开；header 左右 padding 下限 12dp
internal val BlockContainerPadY = 12.dp   // 容器纵向内收（折叠行上下呼吸；卡片底部对称基准）
internal val BlockIconDot = 24.dp        // 图标 tonal 底 / 尾部槽位
internal val BlockRowGap = 8.dp          // 图标↔标题、标题↔尾部
internal val BlockRowIcon = 13.dp        // 折叠行图标
internal val BlockRowChevron = 18.dp     // 折叠行箭头（与 loader / 左图标视觉相近）
internal val BlockLoaderSize = 20.dp     // 运行中 M3E LoadingIndicator（装在尾部圆底内）

// 展开内容左右内收 = 展开态左右圆底中线（headerInset + 圆底半径），由常量推导、不另设魔法数字。
// 收起态圆底中线 = BlockHeaderInsetCollapsed + BlockIconDot / 2，随呼吸动画移动，不用于对齐。
internal val BlockContentInset = BlockHeaderInsetExpanded + BlockIconDot / 2
internal val BlockContentTopGap = BlockContainerPadY // 标题底部→内容顶部 = 标题顶部→卡缘，纵向节奏均一（12-12-12）
internal val BlockContentBottomGap = BlockContainerPadY // 展开内容离容器下缘 = header 上方内收，上下对称
internal val CommandPanelPadX = 12.dp // 命令面板左右内收
internal val CopyBtnGap = 6.dp        // 面板内容 / 文本 ↔ 复制按钮 的邻近间隙
internal val CommandRowPadY = 4.dp    // 命令行纵向
internal val OutputPadY = 6.dp        // 输出区纵向
internal val OutputBtnInset = 36.dp   // 多行输出右缘给右上角按钮让位
internal val CopyBtnSize = 26.dp      // 复制按钮
internal val CopyIconSize = 14.dp     // 复制图标
internal val ResultScrollMaxHeight = 102.dp // 工具结果/思考正文滚动高度上限

// 块正文统一色规则：onSurfaceVariant 满值（不叠 alpha）。
// 层级三档：收起行 variant×0.7 < 块内正文 variant < 助手回答 onSurface。
// 输出/思考的区分由等宽字体承担，亮度不参与表意。

// ── 容器 shape morph 圆角 ───────────────────────────────────────────────────

/** 折叠常态圆角（偏胶囊的对话气泡感）。 */
private val BlockRadiusCollapsed = 24.dp

/** 按压圆角：向胶囊进一步形变，反馈「可按」。 */
private val BlockRadiusPressed = 30.dp

/** 展开圆角：容器变大后取更平的圆角，呈现「内容住进容器」。 */
private val BlockRadiusExpanded = 18.dp

private val BlockExpandSpring =
    spring<IntSize>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

/**
 * THINKING / TOOLING 共用折叠块（M3 Expressive）：
 * - Shape morphing：按压圆角 24→30dp（向胶囊形变）+ 0.97 缩放；展开后容器驻留
 *   （surfaceContainerLow）、圆角收敛 18dp；圆角全程 spring。
 * - State layer：按压时容器浮现（alpha 驱动）替代 ripple（indication = null）。
 * - 展开/收起为 spring（expandVertically + fade），与 chevron、多工具链 stagger 同语言。
 * - [isRunning] 时尾部为 M3E LoadingIndicator（与 composer 发送按钮一致），与 chevron 共用圆底槽位。
 * - 呼吸：header 水平内收收起 16dp ↔ 展开 12dp（spring，与圆角 morph 同步）。
 *   展开内容左右 = BlockContentInset = 展开态左右圆底中线（由常量推导，恢复中线对齐）。
 * - 折叠行上下 BlockContainerPadY（12dp）；标题下方 BlockContentTopGap 与内容下缘
 *   BlockContentBottomGap 同取 PadY：标题上方 / 标题下方 / 内容下缘三段等距。
 * - 首尾槽位同为 24dp tonal 圆底，左右对称。
 * 块正文统一色：收起行 variant×0.7，块内正文 variant 满值，回答 onSurface。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val radiusDp by animateDpAsState(
        targetValue = when {
            pressed -> BlockRadiusPressed
            isExpanded -> BlockRadiusExpanded
            else -> BlockRadiusCollapsed
        },
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "blockRadius",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "blockScale",
    )
    val surfaceAlpha by animateFloatAsState(
        targetValue = when {
            isExpanded -> 1f
            pressed -> 0.65f
            else -> 0f
        },
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
        label = "blockSurface",
    )
    val titleColor by animateColorAsState(
        targetValue = if (isExpanded) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        },
        animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium),
        label = "blockTitle",
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "chevron",
    )
    // 呼吸：header 水平内收随展开放开（收 14dp ↔ 放 8dp），与圆角 morph 同一动作
    val headerInset by animateDpAsState(
        targetValue = if (isExpanded) BlockHeaderInsetExpanded else BlockHeaderInsetCollapsed,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
        label = "headerInset",
    )

    val shape = remember(radiusDp) { G2FieldShape(radiusDp) }
    val surface = MaterialTheme.colorScheme.surfaceContainerLow

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(surface.copy(alpha = surfaceAlpha), shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onToggle,
            )
            .padding(vertical = BlockContainerPadY),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = headerInset),
        ) {
            Box(
                modifier = Modifier
                    .size(BlockIconDot)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(BlockRowIcon),
                    tint = titleColor,
                )
            }
            Spacer(modifier = Modifier.width(BlockRowGap))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(BlockRowGap))
            // 尾部槽位：tonal 圆底与左图标对称，内容在 chevron（完成）↔ loader（运行中）间切换
            Box(
                modifier = Modifier
                    .size(BlockIconDot)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (isRunning) {
                    LoadingIndicator(
                        modifier = Modifier.size(BlockLoaderSize),
                        color = titleColor,
                    )
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
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(BlockExpandSpring) +
                    fadeIn(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)),
            exit = shrinkVertically(BlockExpandSpring) +
                    fadeOut(spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)),
        ) {
            Column(
                modifier = Modifier
                    .padding(
                        start = BlockContentInset,
                        end = BlockContentInset,
                        top = BlockContentTopGap,
                        bottom = BlockContentBottomGap,
                    ),
            ) {
                content()
            }
        }
    }
}
