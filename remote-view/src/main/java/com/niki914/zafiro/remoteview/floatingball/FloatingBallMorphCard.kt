package com.niki914.zafiro.remoteview.floatingball

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.zafiro.api.model.ApprovalRequest

/**
 * 卡片独立窗口内部的形态演变组件。
 *
 * 核心设计：
 * 1. 窗口尺寸终生固定，永远不做系统级 Window Resize；
 * 2. 展开与收缩全过程由双窗口无缝接力：小球在底层垫底，卡片在顶层展开/收起；
 * 3. 按钮顺序永远保持固定（1B 顺序：[跳转应用 | 暂停/允许 | 收起/拒绝]），贴边方向仅决定卡片容器朝左或朝右收拢。
 */
@Composable
fun FloatingBallMorphCard(
    state: FloatingBallState,
    dockSide: DockSide,
    modifier: Modifier = Modifier,
    preview: String? = null,
    approvalRequest: ApprovalRequest? = null,
    isApprovalPending: Boolean = approvalRequest != null,
    isStopEnabled: Boolean = true,
    onBallClick: () -> Unit = {},
    onMinimize: () -> Unit = {},
    onJumpToApp: () -> Unit = {},
    onStop: () -> Unit = {},
    onAllow: () -> Unit = {},
    onDeny: () -> Unit = {},
    onOpenDetail: () -> Unit = {},
    onCollapseFinished: () -> Unit = {},
    onBallAlphaChanged: (Float) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val cardBg = colors.primaryContainer
    val cardContentColor = colors.onPrimaryContainer
    val buttonBg = colors.onPrimaryContainer
    val buttonIconTint = colors.primaryContainer

    // 动画进度：0f 为收起态（小球尺寸），1f 为完全展开态（卡片尺寸）
    val animProgress = remember { Animatable(if (state.isExpanded) 1f else 0f) }

    LaunchedEffect(state) {
        if (state.isExpanded) {
            animProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                block = {
                    val p = value
                    // 展开动画进度到达 90%（p >= 0.9f）时，底层小球以 alpha 动画淡出
                    val ballAlpha = if (p >= 0.9f) {
                        ((1f - p) / 0.1f).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    onBallAlphaChanged(ballAlpha)
                },
            )
            onBallAlphaChanged(0f)
        } else {
            // 仅在真实处于展开/部分展开状态时才执行收缩动画与完成回调，杜绝冷启动误判
            if (animProgress.value > 0f) {
                // 收起开始时，小球在底层保持可见准备接力
                onBallAlphaChanged(1f)
                animProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
                )
                onCollapseFinished()
            } else {
                onBallAlphaChanged(1f)
            }
        }
    }

    val progress = animProgress.value

    // 在收起动画完成度达 90%（即仅剩最后 10% 进度 progress <= 0.1f）时，卡片以 alpha 动画淡出
    val cardAlpha = if (!state.isExpanded && progress <= 0.1f) {
        (progress / 0.1f).coerceIn(0f, 1f)
    } else {
        1f
    }

    val anchorX = if (dockSide.isRight) FloatingBallTokens.rightAnchorXDp else FloatingBallTokens.leftAnchorXDp
    val anchorY = FloatingBallTokens.anchorYDp

    val currentWidth = lerp(FloatingBallTokens.collapsedWidthDp, FloatingBallTokens.expandedWidthDp, progress)
    val currentHeight = lerp(FloatingBallTokens.collapsedHeightDp, FloatingBallTokens.expandedHeightDp, progress)
    val currentCardPadding = lerp(0.dp, FloatingBallTokens.cardPaddingDp, progress)

    // 计算卡片容器在展开窗口中的偏移，使锚点相对屏幕绝对坐标严格静止
    val containerLeft = if (dockSide.isRight) {
        anchorX + FloatingBallTokens.buttonDiameterDp + currentCardPadding - currentWidth
    } else {
        anchorX - currentCardPadding
    }
    val containerTop = anchorY + FloatingBallTokens.buttonDiameterDp + currentCardPadding - currentHeight

    val cardShape = G2CardShape(FloatingBallTokens.cardCornerRadiusDp)

    Box(
        modifier = modifier
            .size(
                width = FloatingBallTokens.expandedWidthDp,
                height = FloatingBallTokens.expandedHeightDp,
            )
            .graphicsLayer { alpha = cardAlpha },
    ) {
        Column(
            modifier = Modifier
                .offset { IntOffset(containerLeft.roundToPx(), containerTop.roundToPx()) }
                .size(width = currentWidth, height = currentHeight)
                .clip(cardShape)
                .background(cardBg, cardShape)
                .clickable(enabled = progress < 0.2f, onClick = onBallClick)
                .padding(currentCardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 预览文本槽位：高度随卡片平滑变化，透明度在后 30% 渐显，绝不提前排版闪烁
            val textSlotHeight = lerp(0.dp, FloatingBallTokens.previewHeightDp, progress)
            val textAlpha = if (state.isExpanded && progress > 0.7f) {
                ((progress - 0.7f) / 0.3f).coerceIn(0f, 1f)
            } else {
                0f
            }

            FloatingBallPreviewSlot(
                preview = preview,
                approvalRequest = approvalRequest,
                isApprovalPending = isApprovalPending,
                slotHeight = textSlotHeight,
                alpha = textAlpha,
                contentColor = cardContentColor,
                onOpenDetail = onOpenDetail,
            )

            // 操作栏：3 个卡牌按钮永远固定 1B 顺序 [跳转应用 | 暂停/允许 | 收起/拒绝]，像卡牌一样叠放与铺开
            val offsets = FloatingBallGeometry.computeCardStackOffsets(dockSide, progress)
            val buttonsAlpha = if (progress > 0.15f) {
                ((progress - 0.15f) / 0.55f).coerceIn(0f, 1f)
            } else {
                0f
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(FloatingBallTokens.buttonDiameterDp),
            ) {
                FloatingBallActionButton(
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    onClick = onJumpToApp,
                    backgroundColor = buttonBg,
                    contentColor = buttonIconTint,
                    enabled = progress >= 0.7f,
                    modifier = Modifier
                        .offset { IntOffset(offsets.jumpX.roundToPx(), 0) }
                        .zIndex(offsets.jumpZIndex)
                        .graphicsLayer { alpha = buttonsAlpha },
                )
                FloatingBallActionButton(
                    icon = if (isApprovalPending) Icons.Filled.Check else Icons.Filled.Pause,
                    onClick = if (isApprovalPending) onAllow else onStop,
                    backgroundColor = buttonBg,
                    contentColor = buttonIconTint,
                    enabled = progress >= 0.7f && (isApprovalPending || isStopEnabled),
                    modifier = Modifier
                        .offset { IntOffset(offsets.stopX.roundToPx(), 0) }
                        .zIndex(offsets.stopZIndex)
                        .graphicsLayer { alpha = buttonsAlpha },
                )
                FloatingBallActionButton(
                    icon = if (isApprovalPending) Icons.Filled.Close else Icons.Filled.CloseFullscreen,
                    onClick = if (isApprovalPending) onDeny else onMinimize,
                    backgroundColor = buttonBg,
                    contentColor = buttonIconTint,
                    enabled = progress >= 0.7f,
                    modifier = Modifier
                        .offset { IntOffset(offsets.minimizeX.roundToPx(), 0) }
                        .zIndex(offsets.minimizeZIndex)
                        .graphicsLayer { alpha = buttonsAlpha },
                )
            }
        }
    }
}

private sealed class PreviewTarget(val order: Int) {
    data object Placeholder : PreviewTarget(0)
    data class AgentText(val text: String) : PreviewTarget(1)
    data class Approval(val reason: String) : PreviewTarget(2)
}

/**
 * 预览槽位：根据文本有无及待授权状态自动切换展示，支持上下翻折滑入滑出动效。
 *
 * 核心约束：
 * 外层 Box 必须终生占用 [slotHeight]，绝对禁止在 alpha <= 0f 时提前 return 或移除 Box。
 * 展开/收缩全过程中卡片容器的 containerTop 与 slotHeight 联动抵消，
 * 保证下方 3 颗按钮的屏幕垂直物理坐标 100% 严格静止。任何 slotHeight 缺失都会造成按钮往上飘。
 */
@Composable
private fun FloatingBallPreviewSlot(
    preview: String?,
    approvalRequest: ApprovalRequest?,
    isApprovalPending: Boolean,
    slotHeight: Dp,
    alpha: Float,
    contentColor: Color,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(slotHeight)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        if (alpha > 0f) {
            val previewTarget: PreviewTarget = when {
                isApprovalPending || approvalRequest != null -> {
                    val reason = approvalRequest?.ruleName?.takeIf { it.isNotBlank() }
                        ?: approvalRequest?.toolName
                        ?: ""
                    PreviewTarget.Approval(reason)
                }
                preview.isNullOrBlank() -> PreviewTarget.Placeholder
                else -> PreviewTarget.AgentText(preview)
            }

            AnimatedContent(
                targetState = previewTarget,
                transitionSpec = {
                    val floatSpec = tween<Float>(durationMillis = 280, easing = FastOutSlowInEasing)
                    val intOffsetSpec = tween<IntOffset>(durationMillis = 280, easing = FastOutSlowInEasing)
                    if (targetState.order >= initialState.order) {
                        (slideInVertically(animationSpec = intOffsetSpec) { it } + fadeIn(animationSpec = floatSpec)) togetherWith
                                (slideOutVertically(animationSpec = intOffsetSpec) { -it } + fadeOut(animationSpec = floatSpec))
                    } else {
                        (slideInVertically(animationSpec = intOffsetSpec) { -it } + fadeIn(animationSpec = floatSpec)) togetherWith
                                (slideOutVertically(animationSpec = intOffsetSpec) { it } + fadeOut(animationSpec = floatSpec))
                    }.using(SizeTransform(clip = true))
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.alpha = alpha },
                label = "FloatingBallPreviewSlide",
            ) { target ->
                when (target) {
                    is PreviewTarget.Placeholder -> {
                        FloatingBallPlaceholderPreview(
                            alpha = 1f,
                            contentColor = contentColor,
                        )
                    }
                    is PreviewTarget.AgentText -> {
                        FloatingBallTextPreview(
                            text = target.text,
                            alpha = 1f,
                            contentColor = contentColor,
                        )
                    }
                    is PreviewTarget.Approval -> {
                        FloatingBallApprovalPreview(
                            reason = target.reason,
                            alpha = 1f,
                            contentColor = contentColor,
                            onOpenDetail = onOpenDetail,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 待命/空文本占位：居中显示 '-'。
 */
@Composable
fun FloatingBallPlaceholderPreview(
    alpha: Float,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "-",
            color = contentColor,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 真实流式文本展示：居中排版，保留充足的行高与内边距以完整容纳 2 行富文本/Emoji。
 */
@Composable
fun FloatingBallTextPreview(
    text: String,
    alpha: Float,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .graphicsLayer { this.alpha = alpha },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            style = MaterialTheme.typography.bodyMedium.copy(
                lineHeight = 18.sp,
            ),
            maxLines = FloatingBallTokens.previewMaxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 待授权提示文本展示：
 * - 第一行：居中显示 "操作待您批准 (${reason})"
 * - 第二行：居中小字带下划线 "点击展开详情"，具备超链接交互质感
 */
@Composable
fun FloatingBallApprovalPreview(
    reason: String,
    alpha: Float,
    contentColor: Color,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val displayReason = reason.ifBlank { "待确认" }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onOpenDetail)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .graphicsLayer { this.alpha = alpha },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "操作待您批准 ($displayReason)",
            color = contentColor,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.SemiBold,
                lineHeight = 16.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "点击展开详情",
            color = linkColor,
            style = MaterialTheme.typography.labelSmall.copy(
                textDecoration = TextDecoration.Underline,
                fontWeight = FontWeight.Medium,
                lineHeight = 14.sp,
            ),
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(name = "Morph Card - Right Collapsed", showBackground = true)
@Composable
private fun PreviewRightCollapsed() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            FloatingBallMorphCard(
                state = FloatingBallState.Collapsed,
                dockSide = DockSide.Right,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Preview(name = "Morph Card - Right Expanded", showBackground = true)
@Composable
private fun PreviewRightExpanded() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            FloatingBallMorphCard(
                state = FloatingBallState.Expanded,
                dockSide = DockSide.Right,
                preview = "早 ☀️ 需要我干点什么呢？今天的天气真好啊",
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Preview(name = "Morph Card - Left Expanded", showBackground = true)
@Composable
private fun PreviewLeftExpanded() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            FloatingBallMorphCard(
                state = FloatingBallState.Expanded,
                dockSide = DockSide.Left,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Preview(name = "Morph Card - Waiting Approval", showBackground = true)
@Composable
private fun PreviewWaitingApproval() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            FloatingBallMorphCard(
                state = FloatingBallState.Expanded,
                dockSide = DockSide.Right,
                approvalRequest = ApprovalRequest(
                    toolName = "terminal",
                    command = "rm -rf /tmp/cache",
                    ruleName = "危险删除命令",
                ),
                isApprovalPending = true,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

