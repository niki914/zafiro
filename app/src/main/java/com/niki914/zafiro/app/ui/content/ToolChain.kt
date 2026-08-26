package com.niki914.zafiro.app.ui.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.HomeChatViewModel
import com.niki914.zafiro.app.ui.model.HomeToolState
import com.niki914.zafiro.app.ui.model.HomeToolStatus
import com.niki914.zafiro.app.ui.model.ToolPresentation
import kotlinx.coroutines.delay

// ── shared animation specs ─────────────────────────────────────────────────

private val StaggerFadeSpring = spring<Float>(dampingRatio = 1f, stiffness = 300f)
private val StaggerSlideSpring = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.8f, stiffness = 300f)

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

// ── ToolChain — stateless, state driven by ViewModel ────────────────────────

/**
 * Stateless tool call list. Single-tool: renders one [CollapsibleBlock] directly.
 * Multi-tool: renders a header [CollapsibleBlock] (title = count) whose expansion
 * reveals a staggered list of per-tool [CollapsibleBlock] rows.
 * 图标按工具名分派（ToolPresentation.forTool），无专有布局时走默认折叠块。
 */
@Composable
fun ToolChain(
    tools: List<HomeToolStatus>,
    isExpanded: Boolean,
    expandedResults: Set<Int>,
    onToggleRun: () -> Unit,
    onToggleResult: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 展开内容（工具结果）点击回调；null 时不拦截点击（head 仍只管展开/收起）。 */
    onContentClick: (() -> Unit)? = null,
) {
    if (tools.size == 1) {
        val status = tools[0]
        SingleToolRow(
            status = status,
            isOpen = 0 in expandedResults,
            onToggle = { onToggleResult(0) },
            onContentClick = onContentClick,
        )
    } else {
        CollapsibleBlock(
            icon = ToolPresentation.Multi,
            title = pluralStringResource(R.plurals.ui_tool_chain_count, tools.size, tools.size),
            isExpanded = isExpanded,
            onToggle = onToggleRun,
            modifier = modifier,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(BlockSpacing)) {
                tools.forEachIndexed { index, status ->
                    StaggeredEntry(index = index, staggerMs = index * 40L) {
                        SingleToolRow(
                            status = status,
                            isOpen = index in expandedResults,
                            onToggle = { onToggleResult(index) },
                            onContentClick = onContentClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SingleToolRow(
    status: HomeToolStatus,
    isOpen: Boolean,
    onToggle: () -> Unit,
    onContentClick: (() -> Unit)? = null,
) {
    val hasResult = status.resultText != null || status.failedReason != null
    val isRunning = status.state == HomeToolState.Running
    val title = buildString {
        append(status.displayNameRes?.let { stringResource(it) } ?: status.name)
        status.summary?.let { summary -> append(" · ").append(summary) }
    }
    CollapsibleBlock(
        icon = ToolPresentation.forTool(status.name),
        title = title,
        isExpanded = isOpen,
        isRunning = isRunning,
        onToggle = { if (!isRunning && hasResult) onToggle() },
    ) {
        // 兜底内容样式 = Thinking 同款正文（bodyMedium / onSurface），失败时整体转错误色。
        // 文本只显示一份（resultText 优先，缺省时回退 failedReason），避免结果内重复同一段文字。
        val text = status.resultText ?: status.failedReason
        if (text != null) {
            val contentModifier = if (onContentClick != null) {
                Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onContentClick!!,
                )
            } else {
                Modifier
            }
            Box(modifier = Modifier.fillMaxWidth().then(contentModifier)) {
                ToolResultText(
                    text = if (text == HomeChatViewModel.FAILED_REASON_INTERRUPTED) {
                        stringResource(R.string.ui_tool_status_failed_reason_interrupted)
                    } else {
                        text
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (status.state == HomeToolState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

// ── staggered entry ─────────────────────────────────────────────────────────

@Composable
private fun StaggeredEntry(
    index: Int,
    staggerMs: Long,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(index) {
        delay(staggerMs)
        visible = true
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(StaggerFadeSpring) + slideInVertically(StaggerSlideSpring) { it / 4 },
        exit = fadeOut(tween(80)),
    ) { content() }
}

// ── result text — 共享有界滚动文本（工具结果 / 思考展开共用，高度上限统一 102dp） ───

@Composable
internal fun ToolResultText(
    text: String,
    style: TextStyle,
    color: Color,
) {
    var overflow by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 102.dp)
            .let {
                if (overflow) {
                    it.nestedScroll(BlockFlingScrollPropagation).verticalScroll(rememberScrollState())
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