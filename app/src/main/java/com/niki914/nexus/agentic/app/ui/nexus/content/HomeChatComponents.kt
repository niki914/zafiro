package com.niki914.nexus.agentic.app.ui.nexus.content

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.component.LiquidTextField
import com.niki914.nexus.agentic.app.ui.infra.shape.G2BubbleShape
import com.niki914.nexus.agentic.app.ui.infra.shape.G2CapsuleShape
import com.niki914.nexus.agentic.app.ui.infra.shape.G2CardShape
import com.niki914.nexus.agentic.app.ui.nexus.model.ActionSource
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolState
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolStatus
import com.niki914.nexus.agentic.chat.LlmErrorCode
import com.niki914.nexus.base.BaseTheme

internal data class AssistantErrorUi(
    val titleRes: Int,
    val bodyRes: Int? = null,
    val body: String? = null,
)

internal fun toAssistantErrorUi(message: String, code: LlmErrorCode?): AssistantErrorUi {
    return when (code) {
        // 配置问题：用户可行动的引导（唯一本地化正文）
        LlmErrorCode.ConfigRequired -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_config_required_title,
            bodyRes = R.string.ui_home_error_config_required_body,
        )

        // 服务器/网络异常（用户选的服务器）：标题分类 + 原始 message 透传。
        // Parse 也归此类：400 系客户端错误（模型名无效等）是用户配置/服务器问题，
        // 与 Auth 同类，不应算内部错误（正文 message 给出具体原因）。
        LlmErrorCode.Auth, LlmErrorCode.Quota, LlmErrorCode.RateLimit,
        LlmErrorCode.Overloaded, LlmErrorCode.Transport, LlmErrorCode.Parse,
        LlmErrorCode.RetryExhausted,
        -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_network_title,
            body = message.trim().ifEmpty { null },
        )

        // 内部错误（我们的问题 / 未知）：标题分类 + 原始 message 透传，空则兜底
        LlmErrorCode.TurnConflict, LlmErrorCode.HookFailed,
        LlmErrorCode.ToolExecutionFailed, null,
        -> {
            val normalized = message.trim()
            if (normalized.isEmpty()) {
                AssistantErrorUi(
                    titleRes = R.string.ui_home_error_internal_title,
                    bodyRes = R.string.ui_home_error_retry_body,
                )
            } else {
                AssistantErrorUi(
                    titleRes = R.string.ui_home_error_internal_title,
                    body = normalized,
                )
            }
        }
    }
}

internal fun toAssistantErrorUi(message: String): AssistantErrorUi {
    return toAssistantErrorUi(message = message, code = null)
}

private const val AssistantMarkdownPreviewText = """
# Nexus 对话排版

这是一段用于观察正文、标题、引用和表格体感的示例内容。标题不应该再像页面 Hero 一样夸张。

## 标题层级

- 一级信息要明显
- 二级信息要克制
- 列表和正文尽量共用节奏

### 表格密度

| 项目 | 目标 |
| --- | --- |
| H1 | 明显但不撑爆聊天流 |
| H2 | 比正文大一档 |
| Table | 与正文接近，便于连续阅读 |

> 这是一段引用文字，用来确认弱化后的信息层级是否还清楚。

`inline code`

```kotlin
val answer = "markdown preview"
println(answer)
```
"""

@Composable
fun AssistantOutputText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = (MaterialTheme.typography.bodyLarge.fontSize.value + 1f).sp,
        lineHeight = (MaterialTheme.typography.bodyLarge.lineHeight.value + 2f).sp,
    )
    val h1Style = bodyStyle.copy(
        fontSize = 18.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val h2Style = bodyStyle.copy(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val h3Style = bodyStyle.copy(
        fontSize = 14.sp,
        lineHeight = 19.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val h4Style = bodyStyle.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
    )
    val h5Style = bodyStyle.copy(
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
    )
    val h6Style = bodyStyle.copy(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    )
    val tableStyle = bodyStyle.copy(
        fontSize = 16.sp,
        lineHeight = 22.sp,
    )
    val codeStyle = bodyStyle.copy(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontFamily = FontFamily.Monospace,
    )
    val quoteStyle = bodyStyle.copy(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val markdownState = rememberMarkdownState(
        content = text,
        immediate = true,
    )

    SelectionContainer(modifier = modifier.fillMaxWidth()) {
        Markdown(
            markdownState = markdownState,
            modifier = Modifier.fillMaxWidth(),
            typography = markdownTypography(
                h1 = h1Style,
                h2 = h2Style,
                h3 = h3Style,
                h4 = h4Style,
                h5 = h5Style,
                h6 = h6Style,
                text = bodyStyle,
                code = codeStyle,
                inlineCode = codeStyle,
                quote = quoteStyle,
                paragraph = bodyStyle,
                ordered = bodyStyle,
                bullet = bodyStyle,
                list = bodyStyle,
                table = tableStyle,
            ),
        )
    }
}

@Preview(
    name = "Assistant Markdown Preview",
    showBackground = true,
    widthDp = 420,
)
@Composable
private fun AssistantOutputTextPreview() {
    BaseTheme {
        AssistantOutputText(
            text = AssistantMarkdownPreviewText.trim(),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
fun UserMessageBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val bubbleShape = G2BubbleShape(24.dp)

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth * 0.82f)
                .clip(bubbleShape)
                .background(colorScheme.primary.copy(alpha = 0.18f), bubbleShape)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

@Composable
fun AssistantErrorBlock(
    message: String,
    code: LlmErrorCode? = null,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = G2BubbleShape(18.dp)
    val errorUi = toAssistantErrorUi(message = message, code = code)
    val body = errorUi.bodyRes?.let { stringResource(it) } ?: errorUi.body.orEmpty()

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth * 0.82f)
                .clip(shape)
                .background(colorScheme.errorContainer.copy(alpha = 0.68f), shape)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(errorUi.titleRes),
                style = MaterialTheme.typography.labelLarge,
                color = colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onErrorContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun LiquidChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val canSend = !isGenerating && value.isNotBlank()
    val buttonEnabled = isGenerating || canSend
    val stopContentDescription = stringResource(R.string.ui_home_stop_content_description)
    val contentColor = if (buttonEnabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }

    LiquidTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = stringResource(R.string.ui_home_input_placeholder),
        enabled = true,
        singleLine = false,
        maxLines = maxLines,
        modifier = modifier.fillMaxWidth(),
        trailingContent = {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                IconButton(
                    onClick = if (isGenerating) onStopClick else onSendClick,
                    enabled = buttonEnabled,
                    modifier = Modifier.size(40.dp),
                ) {
                    if (isGenerating) {
                        LoadingIndicator(
                            modifier = Modifier
                                .size(28.dp)
                                .clearAndSetSemantics {
                                    contentDescription = stopContentDescription
                                },
                            color = contentColor,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = stringResource(
                                R.string.ui_home_send_content_description
                            ),
                        )
                    }
                }
            }
        },
    )
}

@Composable
fun TurnActionRow(
    source: ActionSource,
    onCopy: () -> Unit,
    onReGenerate: () -> Unit,
    onFork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isAgent = source == ActionSource.Agent

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isAgent) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton(
                icon = Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.ui_home_action_copy_content_description),
                onClick = onCopy,
            )
            if (isAgent) {
                ActionButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.ui_home_action_regenerate_content_description),
                    onClick = onReGenerate,
                )
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.CallSplit,
                    contentDescription = stringResource(R.string.ui_home_action_fork_content_description),
                    onClick = onFork,
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = colorScheme.surfaceVariant.copy(alpha = 0.72f)
    val contentColor = colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
    val shape = G2CardShape(14.dp)

    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(shape)
            .background(containerColor, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = contentColor,
        )
    }
}
