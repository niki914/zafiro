package com.niki914.zafiro.app.ui.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.HomeToolState
import com.niki914.zafiro.app.ui.model.HomeToolStatus
import com.niki914.zafiro.app.ui.model.ToolPresentation
import com.niki914.uikit.infra.shape.G2FieldShape
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

// ── ToolChain — stateless, state driven by ViewModel ────────────────────

/** 命令型工具（精确匹配）：结果体为「命令单行 + 输出」上下分段样式。 */
private val CommandToolNames = setOf("terminal", "execute_python")

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
    // 命令型工具的摘要已在结果体上半区展示完整命令，标题不再重复拼摘要
    val useCommandBody = status.name in CommandToolNames && !status.summary.isNullOrBlank()
    val title = buildString {
        append(status.displayNameRes?.let { stringResource(it) } ?: status.name)
        if (!useCommandBody) {
            status.summary?.let { summary -> append(" · ").append(summary) }
        }
    }
    CollapsibleBlock(
        icon = ToolPresentation.forTool(status.name),
        title = title,
        isExpanded = isOpen,
        isRunning = isRunning,
        onToggle = { if (!isRunning && hasResult) onToggle() },
    ) {
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
            if (useCommandBody) {
                CodeToolBody(
                    command = status.summary.orEmpty(),
                    output = displayOutput(status).trim(),
                    isError = status.state == HomeToolState.Failed,
                )
            } else {
                // 兕底：正文只显示本地化「成功 / 失败」，失败红色。
                // load_skill 成功要展示文件路径，但路径未随结果链路传到 UI（待定），暂同兕底。
                FallbackResultBody(isFailed = status.state == HomeToolState.Failed)
            }
        }
    }
}

// ── tool result bodies ──────────────────────────────────────────────────────

/**
 * 命令型结果体：上下两段独立着色，中间 2dp 透明缝隙露出页面背景（M3E 分割样式）。
 * 上段：命令单行（bodyMedium）+ 复制按钮；下段：输出最多 6 行（bodySmall 等宽）
 * + 复制按钮；[isError] 时仅下段变红。分割侧两角 2dp 小圆角，外侧两角 16dp G2。
 */
@Composable
private fun CodeToolBody(
    command: String,
    output: String,
    isError: Boolean,
) {
    val outerCorner = 16.dp
    val innerCorner = 2.dp
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    background,
                    G2FieldShape(
                        topStart = outerCorner,
                        topEnd = outerCorner,
                        bottomEnd = innerCorner,
                        bottomStart = innerCorner,
                    ),
                )
                .padding(start = 12.dp, end = 6.dp)
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = command,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(6.dp))
            MiniCopyButton(text = command)
        }

        Spacer(modifier = Modifier.height(3.dp))

        // 单行时按钮垂直居中，多行时回到右上角；首帧按是否含换行预估，onTextLayout 纠正
        var singleLine by remember(output) { mutableStateOf(!output.contains('\n')) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    background,
                    G2FieldShape(
                        topStart = innerCorner,
                        topEnd = innerCorner,
                        bottomEnd = outerCorner,
                        bottomStart = outerCorner,
                    ),
                ),
        ) {
            Text(
                text = output,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = if (singleLine) 12.dp else 36.dp)
                    .padding(vertical = 10.dp),
                onTextLayout = { singleLine = it.lineCount == 1 },
            )
            MiniCopyButton(
                text = output,
                modifier = if (singleLine) {
                    Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)
                } else {
                    Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp)
                },
            )
        }
    }
}

/** 小号复制按钮（比标准 IconButton 小、无背景直接融入），点击写入剪贴板。 */
@Composable
private fun MiniCopyButton(text: String, modifier: Modifier = Modifier) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    Box(
        modifier = modifier
            .size(26.dp)
            .clickable { clipboard.setText(AnnotatedString(text)) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.ContentCopy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
            modifier = Modifier.size(14.dp),
        )
    }
}

/** 兜底结果体：UI 与 Thinking 同款，正文只显示本地化「成功 / 失败」，失败红色。 */
@Composable
private fun FallbackResultBody(isFailed: Boolean) {
    Text(
        text = stringResource(if (isFailed) R.string.ui_tool_status_failed else R.string.ui_tool_status_success),
        style = MaterialTheme.typography.bodyMedium,
        color = if (isFailed) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    )
}

/**
 * 提取展示用输出文本：
 * - 「#!tool-result」信封（execute_python 等文本协议工具）→ 取空行后的 payload；
 * - Hermes 扁平 JSON（terminal）→ 拼接 stdout/output/stderr 非空项；
 * - 其余原文。
 */
private fun displayOutput(status: HomeToolStatus): String {
    val raw = status.resultText ?: return status.failedReason.orEmpty()
    if (raw.startsWith("#!tool-result")) {
        val sep = raw.indexOf("\n\n")
        return if (sep != -1) raw.substring(sep + 2) else raw
    }
    val json = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw
    val parts = listOf("stdout", "output", "stderr")
        .mapNotNull { key -> json[key]?.jsonPrimitive?.contentOrNull }
        .filter { it.isNotBlank() }
    return if (parts.isEmpty()) raw else parts.joinToString("\n")
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