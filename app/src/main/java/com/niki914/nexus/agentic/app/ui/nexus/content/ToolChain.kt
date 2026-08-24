package com.niki914.nexus.agentic.app.ui.nexus.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.shape.G2CapsuleShape
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolState
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolStatus

private val SucceededColor = Color(0xFF4F8F6B)
private val FailedColor = Color(0xFFB85C5C)

private sealed interface DotVisibility {
    data object Gone : DotVisibility
    data class Visible(val color: Color) : DotVisibility
}

// ── shared animation specs ─────────────────────────────────────────────────

private val ChevronSpring = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)
private val StaggerFadeSpring = spring<Float>(dampingRatio = 1f, stiffness = 300f)
private val StaggerSlideSpring = spring<IntOffset>(dampingRatio = 0.8f, stiffness = 300f)

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
 * Stateless tool call list. Single-tool: renders one [ToolRowBase] directly.
 * Multi-tool: renders a header [ToolRowBase] (dot gone, name = count)
 * whose expansion reveals a staggered list of per-tool [ToolRowBase] rows.
 */
@Composable
fun ToolChain(
    tools: List<HomeToolStatus>,
    isExpanded: Boolean,
    expandedResults: Set<Int>,
    onToggleRun: () -> Unit,
    onToggleResult: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (tools.size == 1) {
            val status = tools[0]
            val hasResult = status.resultText != null || status.failedReason != null
            val isRunning = status.state == HomeToolState.Running
            val isOpen = 0 in expandedResults
            ToolRowBase(
                name = status.name,
                nameAlpha = 0.78f,
                dot = DotVisibility.Visible(statusDotColor(status.state, contentColor)),
                isExpanded = isOpen,
                isPressable = !isRunning,
                hasResult = hasResult,
                showSpinner = isRunning,
                onClick = { onToggleResult(0) },
            ) {
                ExpandableContainer(visible = isOpen && hasResult) {
                    ToolResultDetail(status.failedReason, status.resultText)
                }
            }
        } else {
            ToolRowBase(
                name = pluralStringResource(R.plurals.ui_tool_chain_count, tools.size, tools.size),
                nameAlpha = 0.72f,
                dot = DotVisibility.Gone,
                isExpanded = isExpanded,
                isPressable = true,
                hasResult = true,
                showSpinner = false,
                onClick = onToggleRun,
            ) {
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(2.dp))
                    tools.forEachIndexed { index, status ->
                        val isOpen = index in expandedResults
                        val hasResult = status.resultText != null || status.failedReason != null
                        val isRunning = status.state == HomeToolState.Running
                        StaggeredEntry(
                            index = index,
                            staggerMs = index * 40L,
                        ) {
                            ToolRowBase(
                                name = status.name,
                                nameAlpha = 0.78f,
                                dot = DotVisibility.Visible(statusDotColor(status.state, contentColor)),
                                isExpanded = isOpen,
                                isPressable = !isRunning,
                                hasResult = hasResult,
                                showSpinner = isRunning,
                                onClick = { onToggleResult(index) },
                            ) {
                                ExpandableContainer(visible = isOpen && hasResult) {
                                    ToolResultDetail(status.failedReason, status.resultText)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

// ── unified tool row ────────────────────────────────────────────────────────

/**
 * Base row for both multi-tool header and individual tool rows.
 *
 * [dot] controls visibility: [DotVisibility.Gone] omits the dot entirely
 * (header), [DotVisibility.Visible] renders a colored dot (individual tools).
 * [showSpinner] replaces the chevron with a [CircularProgressIndicator].
 */
@Composable
private fun ToolRowBase(
    name: String,
    nameAlpha: Float,
    dot: DotVisibility,
    isExpanded: Boolean,
    isPressable: Boolean,
    hasResult: Boolean,
    showSpinner: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val chevron by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = ChevronSpring,
        label = "chevron",
    )
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val showChevron = hasResult || showSpinner

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (isPressable && hasResult) onClick() },
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (dot) {
                is DotVisibility.Visible -> {
                    StatusDot(color = dot.color)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                DotVisibility.Gone -> {}
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor.copy(alpha = nameAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            if (showChevron) {
                Spacer(modifier = Modifier.width(8.dp))
                if (showSpinner) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = contentColor,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = contentColor.copy(alpha = 0.28f),
                        modifier = Modifier
                            .size(16.dp)
                            .graphicsLayer { rotationZ = chevron },
                    )
                }
            }
        }
        content()
    }
}

// ── expandable container (reusable animation wrapper) ───────────────────────

@Composable
private fun ExpandableContainer(
    visible: Boolean,
    enter: EnterTransition = fadeIn(StaggerFadeSpring) +
            slideInVertically(StaggerSlideSpring) { it / 4 },
    exit: ExitTransition = fadeOut(tween(80)),
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit,
    ) { content() }
}

// ── tool result detail (failed reason + result text) ────────────────────────

@Composable
private fun ToolResultDetail(
    failedReason: String?,
    resultText: String?,
) {
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        failedReason?.let { reason ->
            // 用户中断原因 UI 本地化；其余 failedReason 来自模型工具结果，保持原样
            Text(
                text = if (reason == HomeChatViewModel.FAILED_REASON_INTERRUPTED) {
                    stringResource(R.string.ui_tool_status_failed_reason_interrupted)
                } else {
                    reason
                },
                style = MaterialTheme.typography.bodySmall,
                color = FailedColor.copy(alpha = 0.72f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = if (resultText != null) 2.dp else 0.dp),
            )
        }
        resultText?.let { text ->
            ToolResultText(text = text, contentColor = contentColor)
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
        kotlinx.coroutines.delay(staggerMs)
        visible = true
    }
    ExpandableContainer(visible = visible) { content() }
}

// ── result text — single-line centered, multi-line fill-width ───────────────

@Composable
private fun ToolResultText(
    text: String,
    contentColor: Color,
) {
    var overflow by remember { mutableStateOf(false) }

    val resultStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
    val resultColor = contentColor.copy(alpha = 0.58f)

    Box(
        modifier = Modifier
            .let { if (overflow) it.fillMaxWidth() else it }
            .heightIn(max = 102.dp)
            .let { if (overflow) it.nestedScroll(BlockFlingScrollPropagation).verticalScroll(rememberScrollState()) else it },
        contentAlignment = if (overflow) Alignment.TopStart else Alignment.Center,
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = resultStyle,
                color = resultColor,
                maxLines = if (overflow) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                softWrap = overflow,
                modifier = if (overflow) Modifier.fillMaxWidth() else Modifier,
                onTextLayout = { layoutResult ->
                    if (!overflow && layoutResult.hasVisualOverflow) {
                        overflow = true
                    }
                },
            )
        }
    }
}

// ── status dot ──────────────────────────────────────────────────────────────

@Composable
private fun StatusDot(color: Color) {
    val dotShape = G2CapsuleShape()
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(dotShape)
            .background(color, dotShape),
    )
}

// ── helpers ─────────────────────────────────────────────────────────────────

@Composable
private fun statusDotColor(state: HomeToolState, fallback: Color): Color = when (state) {
    HomeToolState.Succeeded -> SucceededColor
    HomeToolState.Failed -> FailedColor
    HomeToolState.Running -> fallback
}
