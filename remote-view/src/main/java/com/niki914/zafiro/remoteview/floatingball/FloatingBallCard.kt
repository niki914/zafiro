package com.niki914.zafiro.remoteview.floatingball

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.zafiro.remoteview.R

// ============================================================
// 极值参数系统 (Two Extremes Spec)
// ============================================================

// 基准原子尺度
const val BUTTON_DIAMETER_DP = 44
const val CARD_PADDING_DP = 10
const val CARD_CORNER_RADIUS_DP = 20

// 展开态按钮：凑成圆形
const val EXPANDED_BUTTON_CORNER_RADIUS_DP = 22

// 收起态圆角：在原 10dp 基础上调高 3 个值 = 13dp
const val COLLAPSED_CORNER_RADIUS_DP = 15

const val PREVIEW_MAX_LINES = 2

val BUTTON_DIAMETER = BUTTON_DIAMETER_DP.dp
val CARD_PADDING = CARD_PADDING_DP.dp
val CARD_CORNER_RADIUS = CARD_CORNER_RADIUS_DP.dp
val COLLAPSED_CORNER_RADIUS = COLLAPSED_CORNER_RADIUS_DP.dp

// 展开态极值 (Expanded Extremes)：
// 宽度严格遵循公式：3 * BUTTON_DIAMETER + 4 * CARD_PADDING
val EXPANDED_WIDTH = (3 * BUTTON_DIAMETER_DP + 4 * CARD_PADDING_DP).dp
val EXPANDED_HEIGHT = 118.dp

// 收起态极值 (Collapsed Extremes)：
val COLLAPSED_WIDTH = BUTTON_DIAMETER
val COLLAPSED_HEIGHT = BUTTON_DIAMETER

/**
 * 展开态悬浮球卡片。
 *
 * 尺寸严格受 [EXPANDED_WIDTH] 与 [EXPANDED_HEIGHT] 约束；
 * 底部 3 按钮为圆形按键，严丝合缝充满宽度。
 * 文本区上下 padding 均衡，且左右内收避免切角。
 */
@Composable
fun FloatingBallExpandedCard(
    preview: String = "Agent Msg Pre-\n-view....",
    onJumpToApp: () -> Unit = {},
    onStop: () -> Unit = {},
    onMinimize: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cardShape = G2CardShape(CARD_CORNER_RADIUS)
    val colors = MaterialTheme.colorScheme

    val cardBg = colors.primaryContainer
    val cardContentColor = colors.onPrimaryContainer
    val buttonBg = colors.onPrimaryContainer
    val buttonIconTint = colors.primaryContainer

    Column(
        modifier = modifier
            .size(width = EXPANDED_WIDTH, height = EXPANDED_HEIGHT)
            .clip(cardShape)
            .background(cardBg, cardShape)
            .padding(CARD_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 预览区：重力置顶（TopCenter），上下 padding 均衡（6dp），左右内收 8dp 避开圆角
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = preview,
                color = cardContentColor,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = PREVIEW_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        // 操作栏：3 个圆形实体按钮，与外观反转形成对比，严丝合缝充满宽度
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CARD_PADDING),
        ) {
            listOf(
                Triple(Icons.AutoMirrored.Filled.OpenInNew, "Jump to app", onJumpToApp),
                Triple(Icons.Filled.Pause, "Stop", onStop),
                Triple(Icons.Filled.CloseFullscreen, "Minimize", onMinimize),
            ).forEach { (icon, description, onClick) ->
                Box(
                    modifier = Modifier
                        .size(BUTTON_DIAMETER)
                        .clip(CircleShape)
                        .background(buttonBg, CircleShape)
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = description,
                        tint = buttonIconTint,
                    )
                }
            }
        }
    }
}

/**
 * 收起态悬浮球静态方块。
 *
 * 尺寸为 [BUTTON_DIAMETER]，圆角调高至 [COLLAPSED_CORNER_RADIUS]（15dp），
 * 背景色与展开卡片统一，图标采用融合柔和色调。
 */
@Composable
fun FloatingBallCollapsedBall(
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.primaryContainer,
    content: @Composable () -> Unit = {
        Icon(
            imageVector = ImageVector.vectorResource(R.drawable.ic_thinking),
            contentDescription = "Agent Icon",
            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
            modifier = Modifier.size(18.dp),
        )
    },
) {
    val ballShape = G2CardShape(COLLAPSED_CORNER_RADIUS)

    Box(
        modifier = modifier
            .size(COLLAPSED_WIDTH, COLLAPSED_HEIGHT)
            .clip(ballShape)
            .background(backgroundColor, ballShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Preview(name = "Floating Ball Preview", showBackground = true)
@Composable
private fun FloatingBallPreview() {
    BaseTheme(darkTheme = false, dynamicColor = false) {
        Surface {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                FloatingBallExpandedCard()
                FloatingBallCollapsedBall()
            }
        }
    }
}
