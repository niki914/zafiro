package com.niki914.zafiro.remoteview.floatingball

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.remoteview.R

/**
 * 授权确认卡片（第 3 个 Window 的核心视图）。
 *
 * 支持与第 2 个 Window（展开卡片）之间的像素级无缝连续容器膨胀动效（Container Morph）：
 * - 起始点严格继承 Card 的当前屏幕物理坐标 [startCardX], [startCardY] 与尺寸 (182dp x 118dp)；
 * - 颜色从 Card 的 [MaterialTheme.colorScheme.primaryContainer] 平滑演进至 [MaterialTheme.colorScheme.surfaceContainerHigh]；
 * - 前半程 100% 共享卡片原装组件 [FloatingBallExpandedCardContent]，杜绝任何手写伪卡片导致的色差与排版脱节；
 * - 详情内容 [FloatingBallDetailCardContent] 遵循标准对话框规范，自然高度包裹排版，不设多余滚动；
 * - 关闭时提前通知底层的 Card 窗口恢复就绪，杜绝交接屏闪。
 */
@Composable
fun FloatingBallDetailMorphCard(
    startCardX: Dp,
    startCardY: Dp,
    request: ApprovalRequest,
    preview: String? = null,
    isStopEnabled: Boolean = false,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onFirstFrameReady: () -> Unit = {},
    onCollapseFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
    icon: ImageVector = ImageVector.vectorResource(R.drawable.ic_remote_view),
) {
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp.dp
    val screenHeightDp = configuration.screenHeightDp.dp
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val colors = MaterialTheme.colorScheme
    val targetWidth = 320.dp
    val maxDialogHeight = (screenHeightDp * 0.82f).coerceAtLeast(360.dp)

    var measuredContentHeightDp by remember { mutableStateOf<Dp?>(null) }
    val targetHeight = (measuredContentHeightDp ?: 460.dp).coerceAtMost(maxDialogHeight)

    val targetLeft = (screenWidthDp - targetWidth) / 2
    val targetTop = (screenHeightDp - targetHeight) / 2

    val animProgress = remember { Animatable(0f) }
    var isClosing by remember { mutableStateOf(false) }
    val firstFrameReported = remember { booleanArrayOf(false) }

    fun startClose() {
        if (!isClosing) {
            isClosing = true
            coroutineScope.launch {
                animProgress.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                )
                onCollapseFinished()
            }
        }
    }

    val progress = animProgress.value

    val currentLeft = lerp(startCardX, targetLeft, progress)
    val currentTop = lerp(startCardY, targetTop, progress)
    val currentWidth = lerp(FloatingBallTokens.expandedWidthDp, targetWidth, progress)
    val currentHeight = lerp(FloatingBallTokens.expandedHeightDp, targetHeight, progress)
    val currentRadius = lerp(FloatingBallTokens.cardCornerRadiusDp, 28.dp, progress)
    val currentColor = lerp(colors.primaryContainer, colors.surfaceContainerHigh, progress)
    val currentElevation = 10.dp * progress

    val cardShape = G2CardShape(currentRadius)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { startClose() },
            ),
    ) {
        // 隐式测绘区：获取详情内容的非受约束真实内在高度，杜绝动画中途在尺寸约束下测出压扁高度的恶性循环
        Box(
            modifier = Modifier
                .offset(x = (-9999).dp, y = (-9999).dp)
                .width(targetWidth)
                .graphicsLayer { alpha = 0f }
                .onSizeChanged { size ->
                    if (size.height > 0) {
                        val h = with(density) { size.height.toDp() }
                        if (measuredContentHeightDp != h) {
                            measuredContentHeightDp = h
                        }
                    }
                },
        ) {
            FloatingBallDetailCardContent(
                request = request,
                onAllow = {},
                onDeny = {},
                icon = icon,
            )
        }

        Box(
            modifier = Modifier
                .offset(x = currentLeft, y = currentTop)
                .size(width = currentWidth, height = currentHeight)
                .shadow(elevation = currentElevation, shape = cardShape)
                .clip(cardShape)
                .background(currentColor, cardShape)
                .drawWithContent {
                    drawContent()
                    if (!firstFrameReported[0]) {
                        firstFrameReported[0] = true
                        onFirstFrameReady()
                        coroutineScope.launch {
                            animProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                            )
                        }
                    }
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}, // 阻断卡片内部点击穿透到底层触发关闭
                ),
        ) {
            // 前 35% 进度：100% 复用卡片完全展开态的通用内容组件，平滑淡出
            if (progress < 0.35f) {
                val previewAlpha = ((0.35f - progress) / 0.35f).coerceIn(0f, 1f)
                FloatingBallExpandedCardContent(
                    preview = preview,
                    approvalRequest = request,
                    isApprovalPending = true,
                    isStopEnabled = isStopEnabled,
                    onJumpToApp = {},
                    onAllow = {},
                    onDeny = {},
                    onStop = {},
                    onMinimize = {},
                    onOpenDetail = {},
                    buttonsEnabled = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = previewAlpha },
                )
            }

            // 后 75% 进度（progress > 0.25f）：显示完整详情内容，平滑淡入
            if (progress > 0.25f) {
                val detailAlpha = ((progress - 0.25f) / 0.75f).coerceIn(0f, 1f)
                FloatingBallDetailCardContent(
                    request = request,
                    onAllow = onAllow,
                    onDeny = onDeny,
                    icon = icon,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = detailAlpha },
                )
            }
        }
    }
}

/**
 * 详情卡片内容排版区（标准授权对话框内容布局，自适应高度）。
 */
@Composable
fun FloatingBallDetailCardContent(
    request: ApprovalRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = ImageVector.vectorResource(R.drawable.ic_remote_view),
) {
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 图标 24dp 居中
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(24.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 标题 20sp 居中
        Text(
            text = stringResource(R.string.tool_permission_dialog_title),
            color = colors.onSurface,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 工具介绍
        Text(
            text = stringResource(R.string.tool_permission_request_intro, request.toolName),
            color = colors.onSurfaceVariant,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 命令块（等宽，surfaceContainerHighest 圆角块，支持水平滚动）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceContainerHighest, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(
                text = request.command,
                color = colors.onSurface,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 命中规则
        Text(
            text = stringResource(R.string.tool_permission_matched_rule, request.ruleName),
            color = colors.error,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 允许按钮（上）：外侧上圆角 12dp，内侧下圆角 4dp
        DetailActionButton(
            text = stringResource(R.string.tool_permission_allow),
            bgColor = colors.primaryContainer,
            textColor = colors.onPrimaryContainer,
            topCorner = 12.dp,
            bottomCorner = 4.dp,
            onClick = onAllow,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )

        Spacer(modifier = Modifier.height(2.dp))

        // 拒绝按钮（下）：内侧上圆角 4dp，外侧下圆角 12dp
        DetailActionButton(
            text = stringResource(R.string.tool_permission_deny),
            bgColor = colors.primaryContainer,
            textColor = colors.onPrimaryContainer,
            topCorner = 4.dp,
            bottomCorner = 12.dp,
            onClick = onDeny,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        )
    }
}

/**
 * 静态授权卡片组件（便于直接使用与单项预览）。
 */
@Composable
fun FloatingBallDetailCard(
    request: ApprovalRequest,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = ImageVector.vectorResource(R.drawable.ic_remote_view),
) {
    val colors = MaterialTheme.colorScheme
    val cardShape = G2CardShape(28.dp)

    Box(
        modifier = modifier
            .width(320.dp)
            .shadow(elevation = 10.dp, shape = cardShape)
            .clip(cardShape)
            .background(colors.surfaceContainerHigh, cardShape),
    ) {
        FloatingBallDetailCardContent(
            request = request,
            onAllow = onAllow,
            onDeny = onDeny,
            icon = icon,
        )
    }
}

@Composable
private fun DetailActionButton(
    text: String,
    bgColor: Color,
    textColor: Color,
    topCorner: Dp,
    bottomCorner: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(
        topStart = topCorner,
        topEnd = topCorner,
        bottomStart = bottomCorner,
        bottomEnd = bottomCorner,
    )
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(shape)
            .background(bgColor, shape)
            .clickable(onClick = onClick)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(name = "Detail Card Preview", showBackground = true)
@Composable
private fun PreviewDetailCard() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            Box(modifier = Modifier.padding(16.dp)) {
                FloatingBallDetailCard(
                    request = ApprovalRequest(
                        toolName = "terminal",
                        command = "rm -rf /data/local/tmp/cache_logs_001.log",
                        ruleName = "危险删除命令",
                    ),
                    onAllow = {},
                    onDeny = {},
                )
            }
        }
    }
}
