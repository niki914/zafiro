package com.niki914.nexus.agentic.app.ui.nexus.content

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.shape.G2CapsuleShape
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolState
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolStatus

private val SucceededColor = Color(0xFF4F8F6B)
private val FailedColor = Color(0xFFB85C5C)

// ── ToolChain — stateless, state driven by ViewModel ──────────────────────

/**
 * Stateless tool call list. For a single tool the header is skipped and the
 * tool row is rendered directly; for two or more tools a header row controls
 * expand/collapse of the tool list.
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
            val isPressable = status.state != HomeToolState.Running
            ToolRow(
                status = status,
                isExpanded = 0 in expandedResults,
                isPressable = isPressable,
                hasResult = hasResult,
                onToggle = { onToggleResult(0) },
            )
        } else {
            MultiToolHeader(
                tools = tools,
                isExpanded = isExpanded,
                contentColor = contentColor,
                onToggle = onToggleRun,
            )

            if (isExpanded) {
                Spacer(modifier = Modifier.height(2.dp))

                tools.forEachIndexed { index, status ->
                    val isOpen = index in expandedResults
                    val hasResult = status.resultText != null || status.failedReason != null
                    val isPressable = status.state != HomeToolState.Running

                    StaggeredEntry(
                        index = index,
                        staggerMs = index * 40L,
                    ) {
                        ToolRow(
                            status = status,
                            isExpanded = isOpen,
                            isPressable = isPressable,
                            hasResult = hasResult,
                            onToggle = { onToggleResult(index) },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

// ── multi-tool header ─────────────────────────────────────────────────────

@Composable
private fun MultiToolHeader(
    tools: List<HomeToolStatus>,
    isExpanded: Boolean,
    contentColor: Color,
    onToggle: () -> Unit,
) {
    val chevron by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "chevron",
    )

    Row(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = pluralStringResource(R.plurals.ui_tool_chain_count, tools.size, tools.size),
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor.copy(alpha = 0.72f),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Default.KeyboardArrowRight,
            contentDescription = null,
            tint = contentColor.copy(alpha = 0.38f),
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = chevron },
        )
    }
}

// ── staggered entry ───────────────────────────────────────────────────────

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
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(spring(dampingRatio = 1f, stiffness = 300f)) +
                slideInVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) { it / 4 },
    ) { content() }
}

// ── single tool row ───────────────────────────────────────────────────────

@Composable
private fun ToolRow(
    status: HomeToolStatus,
    isExpanded: Boolean,
    isPressable: Boolean,
    hasResult: Boolean,
    onToggle: () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val statusDotColor = when (status.state) {
        HomeToolState.Succeeded -> SucceededColor
        HomeToolState.Failed -> FailedColor
        HomeToolState.Running -> contentColor
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (isPressable && hasResult) onToggle() },
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(state = status.state, color = statusDotColor)
            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = status.name,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = statusLabel(status),
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.48f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (hasResult || status.state == HomeToolState.Running) {
                Spacer(modifier = Modifier.width(6.dp))
            }

            when {
                status.state == HomeToolState.Running -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = contentColor,
                )
                hasResult -> Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.28f),
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer { rotationZ = if (isExpanded) 90f else 0f },
                )
            }
        }

        // Result detail
        AnimatedVisibility(
            visible = isExpanded && (status.resultText != null || status.failedReason != null),
            enter = fadeIn(tween(150)) +
                    slideInVertically(tween(150)) { it / 6 },
            exit = fadeOut(tween(80)),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                status.failedReason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = FailedColor.copy(alpha = 0.72f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (status.resultText != null) 2.dp else 0.dp),
                    )
                }
                status.resultText?.let { text ->
                    ToolResultText(
                        text = text,
                        contentColor = contentColor,
                    )
                }
            }
        }
    }
}

// ── result text — single-line centered, multi-line fill-width ──────────────

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
            .let { if (overflow) it.verticalScroll(rememberScrollState()) else it },
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

// ── status dot ────────────────────────────────────────────────────────────

@Composable
private fun StatusDot(state: HomeToolState, color: Color) {
    val dotShape = G2CapsuleShape()
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(dotShape)
            .background(color.copy(alpha = 0.78f), dotShape),
    )
}

// ── helpers ───────────────────────────────────────────────────────────────

@Composable
private fun statusLabel(status: HomeToolStatus): String = when (status.state) {
    HomeToolState.Running -> stringResource(R.string.ui_tool_status_running)
    HomeToolState.Succeeded -> stringResource(R.string.ui_tool_status_success)
    HomeToolState.Failed -> when (status.failedReason) {
        "Interrupted by user" -> stringResource(R.string.ui_tool_status_failed_reason_interrupted)
        else -> stringResource(R.string.ui_tool_status_failed)
    }
}
