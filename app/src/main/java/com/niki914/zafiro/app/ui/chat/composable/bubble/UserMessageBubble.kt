package com.niki914.zafiro.app.ui.chat.composable.bubble

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.zafiro.app.R

/** User 气泡在连续用户消息组内的位置（渲染层分组：组边界 = 相邻 UserBubble 之间存在任何内容，见 userBubblePosition）。 */
enum class UserBubblePosition {
    /** 独立单条：右上圆角、右下直角 + 尾巴 */
    Single,

    /** 组首：右上圆角、右下直角 */
    GroupFirst,

    /** 组中：右上、右下均直角 */
    GroupMid,

    /** 组末：右上、右下均直角 + 尾巴 */
    GroupLast,
}

/** 接缝侧小圆角（组内 User 气泡相接角与尾巴底角共用）。 */
private val UserBubbleInnerCorner = 2.dp

/** User 气泡普通角半径（与 UserMessageBubble 的 G2FieldShape 角一致）。 */
internal val UserBubbleCornerRadius = 24.dp

@Composable
fun UserMessageBubble(
    text: String,
    modifier: Modifier = Modifier,
    position: UserBubblePosition = UserBubblePosition.Single,
    maxHeight: Dp = Dp.Unspecified,
    onViewFull: (() -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val bubbleBg = colorScheme.primary.copy(alpha = 0.18f)
    // 真实合成底色：若 bubbleBg 带 alpha，与当前窗口 background 真实混合，绝不使用带有黑色的高透明度脏斑
    val actualBubbleBg = remember(bubbleBg, colorScheme.background) {
        bubbleBg.compositeOver(colorScheme.background)
    }
    // 接缝侧（右）小圆角与命令工具结果体分割侧一致（2dp）；带尾巴的角保持直角
    val bubbleShape = when (position) {
        UserBubblePosition.Single -> G2FieldShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomEnd = 0.dp,
            bottomStart = 24.dp,
        )

        UserBubblePosition.GroupFirst -> G2FieldShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomEnd = UserBubbleInnerCorner,
            bottomStart = 24.dp,
        )

        UserBubblePosition.GroupMid -> G2FieldShape(
            topStart = 24.dp,
            topEnd = UserBubbleInnerCorner,
            bottomEnd = UserBubbleInnerCorner,
            bottomStart = 24.dp,
        )

        UserBubblePosition.GroupLast -> G2FieldShape(
            topStart = 24.dp,
            topEnd = UserBubbleInnerCorner,
            bottomEnd = 0.dp,
            bottomStart = 24.dp,
        )
    }
    val hasTail = position == UserBubblePosition.Single || position == UserBubblePosition.GroupLast
    val isHeightCapped = maxHeight != Dp.Unspecified && maxHeight > 0.dp

    var hasVisualOverflow by remember(text, maxHeight) { mutableStateOf(false) }

    val textStyle = MaterialTheme.typography.bodyLarge
    val density = LocalDensity.current
    val singleLineHeightPx = remember(textStyle, density) {
        with(density) {
            if (textStyle.lineHeight.isSpecified) {
                textStyle.lineHeight.toPx()
            } else {
                24.dp.toPx()
            }
        }
    }
    val verticalPaddingPx = with(density) { 24.dp.toPx() }
    val maxLines = remember(isHeightCapped, maxHeight, density, singleLineHeightPx) {
        if (isHeightCapped && singleLineHeightPx > 0) {
            val availableHeightPx = with(density) { maxHeight.toPx() } - verticalPaddingPx
            (availableHeightPx / singleLineHeightPx).toInt().coerceAtLeast(1)
        } else {
            Int.MAX_VALUE
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val bubbleModifier = Modifier
            .widthIn(max = maxWidth * 0.82f)
            .then(if (hasTail) Modifier.drawBehind { drawUserBubbleTail(bubbleBg) } else Modifier)
            .clip(bubbleShape)
            .background(bubbleBg, bubbleShape)

        Box(
            modifier = bubbleModifier,
            contentAlignment = Alignment.CenterEnd,
        ) {
            SelectionContainer(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(
                    text = text,
                    style = textStyle,
                    color = colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                    maxLines = maxLines,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layoutResult ->
                        if (isHeightCapped) {
                            val overflow = layoutResult.hasVisualOverflow ||
                                (layoutResult.lineCount > 0 && layoutResult.isLineEllipsized(layoutResult.lineCount - 1))
                            if (hasVisualOverflow != overflow) {
                                hasVisualOverflow = overflow
                            }
                        }
                    },
                )
            }

            if (isHeightCapped && hasVisualOverflow && onViewFull != null) {
                val transparentBg = actualBubbleBg.copy(alpha = 0f)

                // 1. 底部整行水平渐变蒙层：左淡右深，使最后一行向右平滑隐入气泡底色
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(with(density) { singleLineHeightPx.toDp() } + 12.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    transparentBg,
                                    actualBubbleBg.copy(alpha = 0.5f),
                                    actualBubbleBg,
                                )
                            )
                        )
                )

                // 2. 右下角“查看”动作按钮：带有局部水平过渡蒙层，确保文字尾部与按钮自然融合
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    transparentBg,
                                    actualBubbleBg,
                                )
                            )
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onViewFull,
                        )
                        .padding(start = 24.dp, end = 18.dp, bottom = 12.dp, top = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ui_action_view),
                        color = colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    )
                }
            }
        }
    }
}

/**
 * 右下角尾巴：沿气泡右缘向上突的小竖条。右上角为 1/4 圆角（普通圆角），
 * 右下角与接缝一致的 2dp 小圆角，顶边平直、左缘贴气泡右缘、底缘贴气泡底边，
 * 不叠入主体（避免半透明背景两次绘制产生色差）。
 */
private fun DrawScope.drawUserBubbleTail(color: Color) {
    val w = size.width
    val h = size.height
    val tw = 5.dp.toPx()                  // 尾巴宽度
    val th = 8.dp.toPx()                 // 尾巴高度（沿右缘向上突）
    val rTop = 4.dp.toPx()                // 右上 1/4 圆角半径
    val rBot = UserBubbleInnerCorner.toPx() // 右下小圆角，与接缝一致
    val path = Path().apply {
        moveTo(w, h)                             // 气泡右下角
        lineTo(w + tw - rBot, h)                 // 底边
        quadraticTo(w + tw, h, w + tw, h - rBot) // 右下 1/4 圆角
        lineTo(w + tw, h - th + rTop)            // 右缘向上
        quadraticTo(w + tw, h - th, w + tw - rTop, h - th) // 右上 1/4 圆角
        lineTo(w, h - th)                        // 平直顶边
        close()                                  // 沿气泡右缘回到底角
    }
    drawPath(path, color)
}
