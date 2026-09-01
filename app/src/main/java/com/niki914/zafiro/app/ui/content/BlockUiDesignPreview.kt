package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.shape.G2BubbleShape
import com.niki914.zafiro.app.ui.model.ToolPresentation

/** 思考块内容：正文区，展开后左右比头部再多一点内缩（兜底样式的参照）。 */
private val ThinkingBody = """
    让我先拆解一下这个问题。用户想要在语音助手里获得一个能执行本地工具的智能回答，
    我需要判断：任务是否允许本地执行、需要调用哪些工具、以及如何把多次工具结果整理成一段连贯的中文回答。
""".trimIndent()

/** 兜底工具结果正文：与 Thinking 同款排版（bodyMedium / onSurface）。 */
private val DefaultToolBody =
    "Retrieved 12 documents. Matched 3 results. Summarized by relevance score."

/** 超长内容（内部文本）：默认展开，验证 ToolResultText 的 102dp 上限与内部滚动。 */
private val LongContentBody = """
    Retrieved 12 documents. Matched 3 results. Summarized by relevance score. Additional context:
    the query matched several internal knowledge base entries with overlapping content, ranked by
    cosine similarity against the embedding index. The top hit was an internal design doc on the
    agent runtime, followed by the Onboarding flow spec and the MCP bridge contract.
""".trimIndent()

@Preview(
    name = "Block UI Design",
    showBackground = true,
    widthDp = 420,
)
@Composable
private fun BlockUiDesignPreview() {
    BaseTheme {
        var thinkingOpen by remember { mutableStateOf(false) }
        var defaultOpen by remember { mutableStateOf(false) }
        var longTitleOpen by remember { mutableStateOf(false) }
        var longContentOpen by remember { mutableStateOf(true) }

        val bodyColor = MaterialTheme.colorScheme.onSurface
        val bubbleShape = G2BubbleShape(24.dp)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(BlockSpacing),
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

            // 思考块（保留样式参照）
            CollapsibleBlock(
                icon = ToolPresentation.Thinking,
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

            // 兜底工具结果：与 Thinking 同款正文
            CollapsibleBlock(
                icon = ToolPresentation.forTool("search_docs"),
                title = "search_docs",
                isExpanded = defaultOpen,
                onToggle = { defaultOpen = !defaultOpen },
            ) {
                Text(
                    text = DefaultToolBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // 超长标题（外部文本）：右侧省略
            CollapsibleBlock(
                icon = ToolPresentation.forTool("terminal"),
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

            // 超长内容（内部文本）：默认展开，验证 102dp 上限 + 内部滚动
            CollapsibleBlock(
                icon = ToolPresentation.forTool("search_knowledge_base"),
                title = "search_knowledge_base",
                isExpanded = longContentOpen,
                onToggle = { longContentOpen = !longContentOpen },
            ) {
                ToolResultText(
                    text = LongContentBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

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