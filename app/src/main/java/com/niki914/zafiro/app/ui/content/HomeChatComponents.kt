package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.ActionBarButton
import com.niki914.uikit.infra.component.LiquidTextField
import com.niki914.uikit.infra.shape.G2BubbleShape
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ActionSource
import com.niki914.zafiro.app.ui.model.MessageActionsDisplay
import com.niki914.zafiro.app.ui.model.HomeChatImage
import com.niki914.zafiro.chat.LlmErrorCode

internal data class AssistantErrorUi(
    val titleRes: Int,
    val bodyRes: Int? = null,
    val body: String? = null,
)

internal fun toAssistantErrorUi(message: String?, code: LlmErrorCode?, attempts: Int? = null): AssistantErrorUi {
    return when (code) {
        // 配置问题：用户可行动的引导（唯一本地化正文）
        LlmErrorCode.ConfigRequired -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_config_required_title,
            bodyRes = R.string.ui_home_error_config_required_body,
        )

        // 服务器/网络异常（用户选的服务器）：标题分类 + 原始 message 透传。
        // Parse 也归此类：400 系客户端错误（模型名无效等）是用户配置/服务器问题，
        // 与 Auth 同类，不应算内部错误（正文 message 给出具体原因）。
        // message 为空（controller 不再兜底字符串）时由 bodyRes 兜底。
        // RetryExhausted 带 attempts：标题反映"重试已耗尽"而非泛网络错误
        LlmErrorCode.RetryExhausted if attempts != null -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_retry_exhausted_title,
            bodyRes = if (message.isNullOrBlank()) R.string.ui_home_error_retry_body else null,
            body = message?.trim()?.ifEmpty { null },
        )

        LlmErrorCode.Auth, LlmErrorCode.Quota, LlmErrorCode.RateLimit,
        LlmErrorCode.Overloaded, LlmErrorCode.Transport, LlmErrorCode.Parse,
        LlmErrorCode.RetryExhausted,
            -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_network_title,
            bodyRes = if (message.isNullOrBlank()) R.string.ui_home_error_retry_body else null,
            body = message?.trim()?.ifEmpty { null },
        )

        // 响应空闲超时：专属标题，秒数不进错误串（用户只需知道超时了）
        LlmErrorCode.IdleTimeout -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_idle_timeout_title,
            bodyRes = if (message.isNullOrBlank()) R.string.ui_home_error_retry_body else null,
            body = message?.trim()?.ifEmpty { null },
        )

        // 内部错误（我们的问题 / 未知）：标题分类 + 原始 message 透传，空则兜底
        LlmErrorCode.TurnConflict, LlmErrorCode.HookFailed,
        LlmErrorCode.ToolExecutionFailed, null,
            -> {
            val normalized = message?.trim()
            if (normalized.isNullOrEmpty()) {
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

internal fun toAssistantErrorUi(message: String?): AssistantErrorUi {
    return toAssistantErrorUi(message = message, code = null)
}

private const val AssistantMarkdownPreviewText = """
# Zafiro 对话排版

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
    val markdownState = rememberMarkdownState(
        content = text,
        immediate = true,
    )

    SelectionContainer(modifier = modifier.fillMaxWidth()) {
        Markdown(
            markdownState = markdownState,
            modifier = Modifier.fillMaxWidth(),
            typography = assistantMarkdownTypography(),
        )
    }
}

@Composable
private fun assistantMarkdownTypography(): MarkdownTypography {
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = (MaterialTheme.typography.bodyLarge.fontSize.value + 1f).sp,
        lineHeight = (MaterialTheme.typography.bodyLarge.lineHeight.value + 2f).sp,
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
    return markdownTypography(
        h1 = bodyStyle.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        h2 = bodyStyle.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        h3 = bodyStyle.copy(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
        h4 = bodyStyle.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
        h5 = bodyStyle.copy(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
        h6 = bodyStyle.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        text = bodyStyle,
        code = codeStyle,
        inlineCode = codeStyle,
        quote = quoteStyle,
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        table = bodyStyle.copy(fontSize = 16.sp, lineHeight = 22.sp),
    )
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

/** User 气泡在连续用户消息组内的位置（渲染层分组：组边界 = 相邻 UserBubble 之间存在任何内容，见 userBubblePosition）。 */
enum class UserBubblePosition {
    /** 独立单条：右上圆角、右下直角 + 尾巴 */
    Single,

    /** 组首：右上圆角、右下直角 */
    GroupFirst,

    /** 组中：右上、右下均直角 */
    GroupMid,

    /** 组末：右上、右下均直角 + 尾巴 */
    GroupLast,
}

/** 接缝侧小圆角（组内 User 气泡相接角与尾巴底角共用）。 */
private val UserBubbleInnerCorner = 2.dp

/** User 气泡普通角半径（与 UserMessageBubble 的 G2FieldShape 角一致）。 */
internal val UserBubbleCornerRadius = 24.dp

/** 紧凑态 composer 最小高度：12dp 容器垂直 padding × 2 + 48sp 按钮 footprint。 */
internal val COMPACT_COMPOSER_MIN_HEIGHT = 72.dp

/** 紧凑态字符数上限：超过即切展开态（先于布局溢出信号，杜绝横向滚动）。 */
private const val COMPACT_CHAR_LIMIT = 12

/** 展开态编辑区最大行数。 */
private const val EXPANDED_MAX_LINES = 7

@Composable
fun UserMessageBubble(
    text: String,
    modifier: Modifier = Modifier,
    position: UserBubblePosition = UserBubblePosition.Single,
) {
    val colorScheme = MaterialTheme.colorScheme
    val bubbleBg = colorScheme.primary.copy(alpha = 0.18f)
    // 接缝侧（右）小圆角与命令工具结果体分割侧一致（2dp）；带尾巴的角保持直角
    val bubbleShape = when (position) {
        UserBubblePosition.Single -> G2FieldShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomEnd = 0.dp,
            bottomStart = 24.dp,
        )

        UserBubblePosition.GroupFirst -> G2FieldShape(
            topStart = 24.dp,
            topEnd = 24.dp,
            bottomEnd = UserBubbleInnerCorner,
            bottomStart = 24.dp,
        )

        UserBubblePosition.GroupMid -> G2FieldShape(
            topStart = 24.dp,
            topEnd = UserBubbleInnerCorner,
            bottomEnd = UserBubbleInnerCorner,
            bottomStart = 24.dp,
        )

        UserBubblePosition.GroupLast -> G2FieldShape(
            topStart = 24.dp,
            topEnd = UserBubbleInnerCorner,
            bottomEnd = 0.dp,
            bottomStart = 24.dp,
        )
    }
    val hasTail = position == UserBubblePosition.Single || position == UserBubblePosition.GroupLast

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth * 0.82f)
                .then(if (hasTail) Modifier.drawBehind { drawUserBubbleTail(bubbleBg) } else Modifier)
                .clip(bubbleShape)
                .background(bubbleBg, bubbleShape)
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

/**
 * 右下角尾巴：沿气泡右缘向上突的小竖条。右上角为 1/4 圆角（普通圆角），
 * 右下角与接缝一致的 2dp 小圆角，顶边平直、左缘贴气泡右缘、底缘贴气泡底边，
 * 不叠入主体（避免半透明背景两次绘制产生色差）。
 */
private fun DrawScope.drawUserBubbleTail(color: Color) {
    val w = size.width
    val h = size.height
    val tw = 5.dp.toPx()                  // 尾巴宽度
    val th = 8.dp.toPx()                 // 尾巴高度（沿右缘向上突）
    val rTop = 4.dp.toPx()                // 右上 1/4 圆角半径
    val rBot = UserBubbleInnerCorner.toPx() // 右下小圆角，与接缝一致
    val path = Path().apply {
        moveTo(w, h)                             // 气泡右下角
        lineTo(w + tw - rBot, h)                 // 底边
        quadraticTo(w + tw, h, w + tw, h - rBot) // 右下 1/4 圆角
        lineTo(w + tw, h - th + rTop)            // 右缘向上
        quadraticTo(w + tw, h - th, w + tw - rTop, h - th) // 右上 1/4 圆角
        lineTo(w, h - th)                        // 平直顶边
        close()                                  // 沿气泡右缘回到底角
    }
    drawPath(path, color)
}

@Composable
fun AssistantErrorBlock(
    message: String?,
    code: LlmErrorCode? = null,
    attempts: Int? = null,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = G2BubbleShape(18.dp)
    val errorUi = toAssistantErrorUi(message = message, code = code, attempts = attempts)
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
    pendingImages: List<HomeChatImage> = emptyList(),
    onAttachImageClick: () -> Unit = {},
) {
    val canSend = !isGenerating && (value.isNotBlank() || pendingImages.isNotEmpty())
    val buttonEnabled = isGenerating || canSend
    val stopContentDescription = stringResource(R.string.ui_home_stop_content_description)
    val contentColor = if (buttonEnabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }

    // 双态：紧凑态恒单行；文本放不下（布局 didOverflowWidth）或输入了换行符
    // → 展开态（2..7 行）；展开态回到 1 行 → 回紧凑。字符数阈值先于溢出兜底，
    // 保证打字过程中不会出现单行横向滚动。同一 BasicTextField + 同一玻璃容器，
    // 切换不丢焦点、键盘不收起；按钮 bottom/左右 padding 不变。
    var compactOverflow by remember { mutableStateOf(false) }
    var expandedLineCount by remember { mutableIntStateOf(1) }
    val expanded = value.contains('\n') || value.length > COMPACT_CHAR_LIMIT ||
            compactOverflow || expandedLineCount > 1
    val onLayout: (TextLayoutResult?) -> Unit = { layout ->
        if (layout != null) {
            if (expanded) {
                expandedLineCount = layout.lineCount
            } else {
                compactOverflow = layout.didOverflowWidth
            }
        }
    }

    @Composable
    fun attachButton() {
        // add 按钮恒亮：不随 canSend 变灰（只有 send 随发送态变化）
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.primary,
        ) {
            ActionBarButton(
                onClick = onAttachImageClick,
                enabled = !isGenerating,
                modifier = Modifier.offset(x = (-6).dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(
                        R.string.ui_home_add_image_content_description
                    ),
                )
            }
        }
    }

    @Composable
    fun sendButton() {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ActionBarButton(
                onClick = if (isGenerating) onStopClick else onSendClick,
                enabled = buttonEnabled,
                modifier = Modifier.offset(x = 6.dp),
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
                        painter = painterResource(R.drawable.ic_arrow_up),
                        contentDescription = stringResource(
                            R.string.ui_home_send_content_description
                        ),
                    )
                }
            }
        }
    }

    // 唯一 BasicTextField 实例：expandedLayout 只切换容器内部布局，不重建字段
    LiquidTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = if (expanded) null else stringResource(R.string.ui_home_input_placeholder),
            enabled = true,
            singleLine = false,
            // 紧凑态恒单行；任何换行（wrap 或 \n）立即切展开态
            maxLines = if (expanded) EXPANDED_MAX_LINES else 1,
            contentVerticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically,
            minHeight = COMPACT_COMPOSER_MIN_HEIGHT,
            onTextLayout = onLayout,
            expandedLayout = expanded,
            expandedActionsRow = {
                attachButton()
                sendButton()
            },
            modifier = modifier.fillMaxWidth(),
            leadingContent = { attachButton() },
            trailingContent = { sendButton() },
        )
}

@Composable
fun TurnActionRow(
    source: ActionSource,
    display: MessageActionsDisplay,
    onCopy: () -> Unit,
    onReGenerate: () -> Unit,
    onFork: () -> Unit,
    onRewind: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isAgent = source == ActionSource.Agent
    // Always 模式：去背景只留图标，融入背景降噪音
    val iconOnly = display == MessageActionsDisplay.Always

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
                iconOnly = iconOnly,
            )
            if (isAgent) {
                ActionButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.ui_home_action_regenerate_content_description),
                    onClick = onReGenerate,
                    iconOnly = iconOnly,
                )
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.CallSplit,
                    contentDescription = stringResource(R.string.ui_home_action_fork_content_description),
                    onClick = onFork,
                    iconOnly = iconOnly,
                )
            } else {
                ActionButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.ui_home_action_regenerate_content_description),
                    onClick = onReGenerate,
                    iconOnly = iconOnly,
                )
                ActionButton(
                    icon = Icons.Default.Undo,
                    contentDescription = stringResource(R.string.ui_home_action_rewind_content_description),
                    onClick = onRewind,
                    iconOnly = iconOnly,
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
    iconOnly: Boolean,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = G2CardShape(14.dp)

    Box(
        modifier = Modifier
            .size(38.dp)
            .then(
                if (iconOnly) {
                    Modifier
                } else {
                    Modifier
                        .clip(shape)
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.72f), shape)
                }
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // iconOnly 无背景：波纹会重新暴露按钮矩形，直接禁用点击指示
                indication = if (iconOnly) null else LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
        )
    }
}

// ── 图片附件 ─────────────────────────────────────────────────────────────

/**
 * 落盘路径 → 异步解码 ImageBitmap（IO dispatcher，主线程零阻塞）。
 * null = 文件不存在/解码失败（显示占位底色）；解码完成自动刷新。
 */
@Composable
private fun rememberPathBitmap(path: String): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap()
            }.getOrNull()
        }
    }.value

/**
 * 单张图片卡。尺寸由调用方决定（composer 待发 60dp / 消息内大图卡 / 工具结果预览）。
 * 顶部 30% 纵向渐变遮罩（black 50% → 0%），右上角白色关闭钮（可选），无圆形背景。
 * 供本文件与 ToolChain（工具结果图片预览）共用。
 */
@Composable
internal fun HomeChatImageCard(
    image: HomeChatImage,
    size: Dp,
    cornerRadius: Dp,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = G2CardShape(cornerRadius)
    val bitmap = rememberPathBitmap(image.path)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        if (onRemove != null) {
            // 顶部 30% 渐变遮罩：顶部 black 50% → 底部 black 0%，衬出白色关闭钮
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(size * 0.3f)
                    .drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent),
                            ),
                        )
                    },
            )
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.ui_home_image_remove_content_description),
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .size(16.dp)
                    .clickable(onClick = onRemove),
            )
        }
    }
}

/**
 * 图片行（composer 待发条 / 用户消息图片区共用）：横向滚动，外层以与卡片
 * 相同的 G2 圆角 clip——边缘图片被裁切时仍呈现圆角形态。宽度约束由调用方给。
 */
@Composable
fun HomeChatImageRow(
    images: List<HomeChatImage>,
    cardSize: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    onRemoveImage: ((String) -> Unit)? = null,
) {
    Row(
        modifier = modifier
            // 宽度由调用方决定（贴内容宽或撑满）：不加 fillMaxWidth，
            // 否则消息图片行无法右对齐贴内容宽
            .clip(G2CardShape(cornerRadius))
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        images.forEach { image ->
            HomeChatImageCard(
                image = image,
                size = cardSize,
                cornerRadius = cornerRadius,
                onRemove = onRemoveImage?.let { remove -> { remove(image.id) } },
            )
        }
    }
}
