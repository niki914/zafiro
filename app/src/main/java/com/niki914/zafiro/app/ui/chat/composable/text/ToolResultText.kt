package com.niki914.zafiro.app.ui.chat.composable.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import com.niki914.zafiro.app.ui.content.ResultScrollMaxHeight
import com.niki914.zafiro.app.ui.home.rememberScrollFollowState

// ── nested scroll: pass through user drag, block fling inertia ─────────────

private val BlockFlingScrollPropagation: NestedScrollConnection = object : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = when {
        source == NestedScrollSource.Fling -> available.copy(x = 0f)
        else -> Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(x = 0f, y = available.y)
}

// ── result text — 共享有界滚动文本（工具结果 / 思考展开共用，高度上限统一 102dp） ───

@Composable
internal fun ToolResultText(
    text: String,
    style: TextStyle,
    color: Color,
    /** active 思考块流式更新时自动滚到底跟随；用户手动拖动后暂停，滚回底部后恢复。 */
    autoScrollToEnd: Boolean = false,
) {
    var overflow by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val shouldFollow by rememberScrollFollowState(
        interactionSource = scrollState.interactionSource,
        isScrollInProgress = { scrollState.isScrollInProgress },
        isAtEnd = { !scrollState.canScrollForward },
    )

    // maxValue 更新（内容增长/布局完成）即滚到底；autoScrollToEnd 置位瞬间先跟随当前尾部。
    LaunchedEffect(autoScrollToEnd, shouldFollow, scrollState.maxValue) {
        if (autoScrollToEnd && shouldFollow) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = ResultScrollMaxHeight)
            .let {
                if (overflow) {
                    it
                        .nestedScroll(BlockFlingScrollPropagation)
                        .verticalScroll(scrollState)
                } else {
                    it
                }
            },
        contentAlignment = Alignment.TopStart,
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = style,
                color = color,
                maxLines = if (overflow) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = overflow,
                onTextLayout = { layoutResult ->
                    if (!overflow && layoutResult.hasVisualOverflow) {
                        overflow = true
                    }
                },
            )
        }
    }
}
