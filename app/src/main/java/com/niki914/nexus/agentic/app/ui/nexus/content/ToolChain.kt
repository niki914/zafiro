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
 * Stateless, centered tool call list.
 *
 * [isExpanded] and [expandedResults] come from [com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatUiState],
 * toggled via [com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatIntent.ToggleToolRun] and
 * [ToggleToolResult][com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatIntent.ToggleToolResult].
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
    val showCollapsedNames = !isExpanded && tools.size >= 2

    val chevron by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "chevron",
    )

    Column(
        modifier = modifier
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── header ──
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggleRun,
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val headerText = if (tools.size == 1) {
                tools[0].name
            } else {
                pluralStringResource(R.plurals.ui_tool_chain_count, tools.size, tools.size)
            }
            Text(
                text = headerText,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor.copy(alpha = 0.72f),
            )
            if (showCollapsedNames) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = tools.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.38f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            }
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

        // ── expanded rows ──
        if (isExpanded) {
            Spacer(modifier = Modifier.height(2.dp))

            tools.forEachIndexed { index, status ->
                val isOpen = index in expandedResults
                val hasResult = status.resultText != null
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

            // Collapse
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleRun,
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ui_tool_chain_collapse),
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.38f),
                )
            }
        }
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
    val statusColor = when (status.state) {
        HomeToolState.Succeeded -> SucceededColor
        HomeToolState.Failed -> FailedColor
        HomeToolState.Running -> contentColor
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
            StatusDot(state = status.state, color = statusColor)
            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = status.name,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )

            Spacer(modifier = Modifier.width(10.dp))

            if (!isExpanded || !hasResult) {
                Text(
                    text = statusLabel(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            when {
                status.state == HomeToolState.Running -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 1.5.dp,
                    color = statusColor,
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
            visible = isExpanded && hasResult,
            enter = fadeIn(tween(150)) +
                    slideInVertically(tween(150)) { it / 6 },
            exit = fadeOut(tween(80)),
        ) {
            status.resultText?.let { text ->
                val resultColor = if (status.state == HomeToolState.Failed) {
                    MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f)
                } else {
                    contentColor.copy(alpha = 0.6f)
                }
                Box(
                    modifier = Modifier
                        .padding(top = 0.dp, bottom = 6.dp)
                        .heightIn(max = 102.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                            ),
                            color = resultColor,
                        )
                    }
                }
            }
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
