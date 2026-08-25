package com.niki914.zafiro.app.ui.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.zafiro.app.R
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.shape.G2BubbleShape

// 图标映射（思路稿）：
//  - Thinking        → 四角星星（也可换 Psychology 脑子）
//  - Terminal        → 终端
//  - Skill           → 笔记本
//  - Python          → Python logo（自绘矢量）
//  - 默认工具 / MCP   → 扳手
private val ThinkingIcon = Icons.Filled.AutoAwesome // TODO New
private val TerminalIcon = Icons.Filled.Terminal
private val SkillIcon = Icons.AutoMirrored.Filled.MenuBook

/** Python logo：res/drawable/python_logo.xml（VectorDrawable），@Composable 内用 vectorResource 加载。 */

/** 默认工具 / MCP 兜底图标。 */
private val DefaultToolIcon = Icons.Filled.Build

/** 思考块内容：正文区，展开后左右比头部再多一点内缩。 */
private val ThinkingBody = """
    让我先拆解一下这个问题。用户想要在语音助手里获得一个能执行本地工具的智能回答，
    我需要判断：任务是否允许本地执行、需要调用哪些工具、以及如何把多次工具结果整理成一段连贯的中文回答。
""".trimIndent()

/** Terminal 专有展开内容：终端风格（等宽、命令/输出分块）。 */
private val TerminalBody = """
$ ls -la /home/niki/projects
drwxr-xr-x  12 niki  staff   384  size
total 48
""".trimIndent()

/** Python 专有展开内容：代码块风格（等宽）。 */
private val PythonBody = """
import os
count = sum(len(files) for _, _, files in os.walk('/home/niki/projects'))
print(f"total files: {count}")
""".trimIndent()

/** Skill 专有展开内容：普通正文说明技能用途。 */
private val SkillBody = """
    已加载「phone-use」技能。该技能用于通过无障碍服务操作手机屏幕，
    可完成点击、滑动、输入等屏幕操作，并在最后返回操作后的界面状态。
""".trimIndent()

/** 默认工具兜底展开内容：单行等宽，过长省略。 */
private val DefaultToolBody =
    "Retrieved 12 documents. Matched 3 results. Summarized by relevance score."

/**
 * 可复用折叠块：左图标 + 标题（过长右侧省略），点击展开。
 * 展开内容左右 inner margin 比头部再多一点（thinking / tooling 共用骨架）。
 */
@Composable
private fun CollapsibleBlock(
    icon: ImageVector,
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    val chevron by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "chevron",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessLow,
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onToggle,
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = contentColor,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = contentColor.copy(alpha = 0.28f),
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = chevron },
            )
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically() + fadeIn(tween(120)),
        ) {
            // 展开内容：左右比头部再多 14.dp 内缩
            Column(modifier = Modifier.padding(start = 28.dp, end = 14.dp, bottom = 6.dp)) {
                content()
            }
        }
    }
}

/** 默认兜底展开内容：单行等宽，过长省略（软换行，避免撑宽导致拉伸）。 */
@Composable
private fun DefaultToolContent(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Preview(
    name = "Block UI Design",
    showBackground = true,
    widthDp = 420,
)
@Composable
private fun BlockUiDesignPreview() {
    BaseTheme {
        var thinkingOpen by remember { mutableStateOf(false) }
        var terminalOpen by remember { mutableStateOf(false) }
        var skillOpen by remember { mutableStateOf(false) }
        var pythonOpen by remember { mutableStateOf(false) }
        var defaultOpen by remember { mutableStateOf(false) }
        var longTitleOpen by remember { mutableStateOf(false) }
        var longContentOpen by remember { mutableStateOf(true) }

        val pythonIcon = ImageVector.vectorResource(R.drawable.python_logo)
        val bodyColor = MaterialTheme.colorScheme.onSurface
        val bubbleShape = G2BubbleShape(24.dp)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 用户消息
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    modifier = Modifier
                        .clip(bubbleShape)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            bubbleShape,
                        )
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = "在服务器上统计这个目录的文件数",
                        style = MaterialTheme.typography.bodyLarge,
                        color = bodyColor,
                    )
                }
            }

            Spacer(modifier = Modifier.size(4.dp))

            // 思考块
            CollapsibleBlock(
                icon = ThinkingIcon,
                title = "Thinking",
                isExpanded = thinkingOpen,
                onToggle = { thinkingOpen = !thinkingOpen },
            ) {
                Text(
                    text = ThinkingBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Terminal 专有布局
            CollapsibleBlock(
                icon = TerminalIcon,
                title = "Terminal · ls -la /home/niki/projects",
                isExpanded = terminalOpen,
                onToggle = { terminalOpen = !terminalOpen },
            ) {
                SelectionContainer {
                    Text(
                        text = TerminalBody,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
            }

            // Python 专有布局（与 Terminal 区分）
            CollapsibleBlock(
                icon = pythonIcon,
                title = "Python · count files in dir",
                isExpanded = pythonOpen,
                onToggle = { pythonOpen = !pythonOpen },
            ) {
                SelectionContainer {
                    Text(
                        text = PythonBody,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    )
                }
            }

            // Skill 专有布局
            CollapsibleBlock(
                icon = SkillIcon,
                title = "Skill · phone-use",
                isExpanded = skillOpen,
                onToggle = { skillOpen = !skillOpen },
            ) {
                Text(
                    text = SkillBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // 默认工具（兜底布局）
            CollapsibleBlock(
                icon = DefaultToolIcon,
                title = "search_docs",
                isExpanded = defaultOpen,
                onToggle = { defaultOpen = !defaultOpen },
            ) {
                DefaultToolContent(DefaultToolBody)
            }

            // 超长标题（外部文本）：右侧省略
            CollapsibleBlock(
                icon = TerminalIcon,
                title = "Terminal · python3 -m pip install --upgrade torch torchvision torchaudio " +
                    "--index-url https://download.pytorch.org/whl/cu121 --no-cache-dir",
                isExpanded = longTitleOpen,
                onToggle = { longTitleOpen = !longTitleOpen },
            ) {
                Text(
                    text = "pip install finished",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // 超长内容（内部文本）：默认展开，单行等宽省略
            CollapsibleBlock(
                icon = DefaultToolIcon,
                title = "search_knowledge_base",
                isExpanded = longContentOpen,
                onToggle = { longContentOpen = !longContentOpen },
            ) {
                DefaultToolContent(
                    "Retrieved 12 documents. Matched 3 results. " +
                        "Summarized by relevance score. Additional context: the query matched " +
                        "several internal knowledge base entries with overlapping content, ranked " +
                        "by cosine similarity against the embedding index.",
                )
            }

            Spacer(modifier = Modifier.size(8.dp))

            // 正文 markdown（作为块的左对齐参照）
            Box(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "统计完成，该项目下共 12 个文件，其中 8 个 Kotlin 源码、4 个资源文件。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = bodyColor,
                )
            }
        }
    }
}
