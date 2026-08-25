package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.zafiro.app.ui.model.ToolPresentation
import com.niki914.uikit.base.BaseTheme

// ── 多工具链头部图标候选 ─────────────────────────────────────────────────────

private data class IconCandidate(val icon: ImageVector, val name: String, val note: String)

private val MultiToolCandidates = listOf(
    IconCandidate(Icons.Filled.Build, "Build（扳手）", "当前默认 · 对照组"),
    IconCandidate(Icons.Filled.Hub, "Hub", "中心节点连多条线，有『并行派发一群』感"),
    IconCandidate(Icons.Filled.Widgets, "Widgets", "拼块组合，表达批量执行"),
    IconCandidate(Icons.Filled.Layers, "Layers", "堆叠层，表达一串/一组"),
    IconCandidate(Icons.Filled.AccountTree, "AccountTree", "调用链分叉树，表达工具链"),
    IconCandidate(Icons.AutoMirrored.Filled.ViewList, "ViewList", "清单，最直白的『一批』"),
)

@Preview(
    name = "Multi-tool Header Icon Candidates",
    showBackground = true,
    widthDp = 420,
)
@Composable
private fun MultiToolHeaderIconCandidatesPreview() {
    BaseTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "多工具链头部图标候选（标题 = 8 tools，点开看子行）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(8.dp))

            MultiToolCandidates.forEachIndexed { index, candidate ->
                // 第一个（扳手）默认展开，展示头部 + 子行层次
                CollapsibleBlock(
                    icon = candidate.icon,
                    title = "8 tools",
                    isExpanded = index == 0,
                    onToggle = {},
                ) {
                    SampleToolRow("terminal", "Terminal · ls -la /home")
                    SampleToolRow("python", "Python · count files in dir")
                    SampleToolRow("search_docs", "search_docs")
                    SampleToolRow("load_skill", "Skill · phone-use")
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = candidate.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = candidate.note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.size(4.dp))
            HostStatusLineHint()
        }
    }
}

@Composable
private fun SampleToolRow(toolName: String, title: String) {
    CollapsibleBlock(
        icon = ToolPresentation.forTool(toolName),
        title = title,
        isExpanded = false,
        onToggle = {},
    ) {
        Text(
            text = "no output",
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

// ── Loading / Running 状态 ───────────────────────────────────────────────────

@Preview(
    name = "Loading / Running States",
    showBackground = true,
    widthDp = 420,
)
@Composable
private fun LoadingStatesPreview() {
    BaseTheme {
        val thinkingStreaming = """
            让我先拆解一下这个问题。用户想要在语音助手里获得一个能执行本地工具的智能回答，
            我需要判断：任务是否允许本地执行、需要调用哪些工具、以及如何把多次工具结果整理成一段连贯的中文回答。这部分的后续内容还在持续生成中，用于观察流式更新是否会影响展开状态……""".trimIndent()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "进行中状态：Thinking 思考中（展开）+ 工具 running（右上角转圈）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.size(8.dp))

            // Thinking 思考中：展开、正文持续更新
            CollapsibleBlock(
                icon = ToolPresentation.Thinking,
                title = "Thinking",
                isExpanded = true,
                onToggle = {},
            ) {
                SelectionContainer {
                    Text(
                        text = thinkingStreaming,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Terminal running：右上角 spinner
            CollapsibleBlock(
                icon = ToolPresentation.forTool("terminal"),
                title = "Terminal · ls -la /home/niki/projects",
                isRunning = true,
                isExpanded = false,
                onToggle = {},
            ) {}

            // Python running：右上角 spinner
            CollapsibleBlock(
                icon = ToolPresentation.forTool("python"),
                title = "Python · count files in dir",
                isRunning = true,
                isExpanded = false,
                onToggle = {},
            ) {}

            // 对照组：已完成的工具（箭头而非转圈）
            CollapsibleBlock(
                icon = ToolPresentation.forTool("terminal"),
                title = "Terminal · ls -la /home/niki/projects",
                isRunning = false,
                isExpanded = false,
                onToggle = {},
            ) {
                Text(
                    text = "total 48\ndrwxr-xr-x  12 niki  staff  384  size",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }

            Spacer(modifier = Modifier.size(4.dp))
            HostStatusLineHint()
        }
    }
}

/** 宿主行形态提示（供确认，非真实渲染代码）。 */
@Composable
private fun HostStatusLineHint() {
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = "宿主行形态（二选一）：",
            style = MaterialTheme.typography.labelSmall,
            color = hintColor,
        )
        Text(
            text = "思考中 → [Thinking]    完成（含被掐）→ [Thought]",
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = hintColor,
        )
        Text(
            text = "空文字不显示；方括号后无其他内容。",
            style = MaterialTheme.typography.labelSmall,
            color = hintColor,
        )
    }
}