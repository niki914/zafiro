package com.niki914.zafiro.app.ui.content

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import com.kyant.backdrop.Backdrop
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.ActionBarButton
import com.niki914.uikit.infra.LiquidGlassPresets
import com.niki914.uikit.infra.component.LiquidTextField
import com.niki914.uikit.infra.shape.G2BubbleShape
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.home.ActionSource
import com.niki914.zafiro.app.ui.model.home.HomeChatFile
import com.niki914.zafiro.app.ui.model.home.HomeChatImage
import com.niki914.zafiro.app.ui.model.home.MessageActionsDisplay
import com.niki914.zafiro.app.ui.model.home.formatFileNameMiddleTruncated
import com.niki914.zafiro.chat.LlmErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AssistantErrorUi(
    val titleRes: Int,
    val bodyRes: Int? = null,
    val body: String? = null,
)

internal fun toAssistantErrorUi(
    message: String?,
    code: LlmErrorCode?,
    attempts: Int? = null
): AssistantErrorUi {
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

        // 输出被上限截断：正文告诉用户去哪里调大（新设置项），不说是网络/内部问题
        LlmErrorCode.OutputTruncated -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_output_truncated_title,
            bodyRes = R.string.ui_home_error_output_truncated_body,
        )

        // 复读/退化熔断：模型输出陷入重复，框架主动中止。正文告知已中止与应对。
        LlmErrorCode.Repetition -> AssistantErrorUi(
            titleRes = R.string.ui_home_error_repetition_title,
            bodyRes = R.string.ui_home_error_repetition_body,
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

/** 全局 Markdown 排版缩放比例，默认 1.0f (100%)。由全局或局部 [CompositionLocalProvider] 注入。 */
val LocalMarkdownScale = compositionLocalOf { 1.0f }

/** 预览样张：同一句 hello 的多文字变体 + 字符表 + 符号，只做字形展示，不作阅读内容。 */
internal const val AssistantMarkdownPreviewText = """
# hello

HELLO 你好 こんにちは ПРИВЕТ HOLA 안녕하세요 ALLÔ CIAO

## ＡＢＣ あいう 日月山川

- ABCDEFGHIJKLMNOPQRSTUVWXYZ
- abcdefghijklmnopqrstuvwxyz
- 0123456789 ! ? . , : ; — … · « » 「 」 『 』
- あいうえお かきくけこ さしすせそ
- 天地玄黄 宇宙洪荒 日月盈昃 辰宿列张

### ▲ ● ■ ◆ ★ ♪ ⚡ ⌘

| A | あ | 永 |
| --- | --- | --- |
| B | か | 空 |
| Γ | さ | 海 |

> ! ? ¿ ¡ : ; ~ ≈ ≠ ∞ °

`Aa` **Aa** *Aa* あ 永 Ж Ω

```kotlin
val hello = "hello 你好 こんにちは ПРИВЕТ"
repeat(8) { print(hello) }
```
"""

@Composable
fun AssistantOutputText(
    text: String,
    modifier: Modifier = Modifier,
) {
    val markdownState = rememberMarkdownState(content = text, retainState = true)

    SelectionContainer(modifier = modifier.fillMaxWidth()) {
        Markdown(
            markdownState = markdownState,
            modifier = Modifier.fillMaxWidth(),
            typography = assistantMarkdownTypography(),
            components = assistantMarkdownComponents(),
        )
    }
}

@Composable
private fun assistantMarkdownComponents(): MarkdownComponents = remember {
    markdownComponents(
        table = { model ->
            AssistantMarkdownTable(
                content = model.content,
                node = model.node,
                style = model.typography.table,
            )
        },
        checkbox = { model -> MarkdownCheckBox(model.content, model.node, model.typography.text) },
    )
}

@Composable
private fun assistantMarkdownTypography(
    scale: Float = LocalMarkdownScale.current,
): MarkdownTypography {
    val bodyStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = (17f * scale).sp,
        lineHeight = (26f * scale).sp,
    )
    val codeStyle = bodyStyle.copy(
        fontSize = (15f * scale).sp,
        lineHeight = (22f * scale).sp,
        fontFamily = FontFamily.Monospace,
    )
    val quoteStyle = bodyStyle.copy(
        fontSize = (16f * scale).sp,
        lineHeight = (24f * scale).sp,
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    return markdownTypography(
        h1 = bodyStyle.copy(
            fontSize = (20f * scale).sp,
            lineHeight = (26f * scale).sp,
            fontWeight = FontWeight.SemiBold
        ),
        h2 = bodyStyle.copy(
            fontSize = (18f * scale).sp,
            lineHeight = (24f * scale).sp,
            fontWeight = FontWeight.SemiBold
        ),
        h3 = bodyStyle.copy(
            fontSize = (16f * scale).sp,
            lineHeight = (22f * scale).sp,
            fontWeight = FontWeight.SemiBold
        ),
        h4 = bodyStyle.copy(
            fontSize = (14.5f * scale).sp,
            lineHeight = (20f * scale).sp,
            fontWeight = FontWeight.Medium
        ),
        h5 = bodyStyle.copy(
            fontSize = (13f * scale).sp,
            lineHeight = (18f * scale).sp,
            fontWeight = FontWeight.Medium
        ),
        h6 = bodyStyle.copy(
            fontSize = (12f * scale).sp,
            lineHeight = (16f * scale).sp,
            fontWeight = FontWeight.Medium
        ),
        text = bodyStyle,
        code = codeStyle,
        inlineCode = codeStyle,
        quote = quoteStyle,
        paragraph = bodyStyle,
        ordered = bodyStyle,
        bullet = bodyStyle,
        list = bodyStyle,
        table = bodyStyle.copy(fontSize = (13.5f * scale).sp, lineHeight = (18f * scale).sp),
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
    contextProgress: Float? = null,
    pendingImages: List<HomeChatImage> = emptyList(),
    pendingFiles: List<HomeChatFile> = emptyList(),
    onAttachImageClick: () -> Unit = {},
    backdrop: Backdrop? = null,
) {
    // 发送按钮恒亮：无文本点击时触发 Toast 提示，能不能点不靠颜色表达。
    // contextProgress 为 null（无记账）时不画环。
    val contentColor = MaterialTheme.colorScheme.primary

    // 展开唯一信号源：布局行数。观测到 lineCount > 1 即锁住展开态；
    // expanded 本身改变测量宽度，对称条件会振荡，故回落只看文本清空。
    // 同一 BasicTextField + 同一玻璃容器，切换不丢焦点、键盘不收起。
    var expandedLatch by remember { mutableStateOf(false) }
    val expanded = value.contains('\n') || expandedLatch
    val onLayout: (TextLayoutResult?) -> Unit = { layout ->
        if (layout != null && !expandedLatch && layout.lineCount > 1) {
            expandedLatch = true
        }
    }
    LaunchedEffect(value) {
        if (value.isEmpty()) expandedLatch = false
    }

    @Composable
    fun attachButton() {
        // add 按钮不限时机：回合进行中加附件也只是进草稿，随下一次发送走。
        // （禁用态在 ActionBarButton 里是「静默吞掉点击且无视觉差异」，所以这里绝不能设 enabled=false）
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.primary,
        ) {
            ActionBarButton(
                onClick = onAttachImageClick,
                modifier = Modifier.offset(x = (-6).dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Button",
                )
            }
        }
    }

    @Composable
    fun sendButton() {
        val progress = contextProgress
        val ringModifier = if (progress == null) {
            Modifier
        } else {
            val trackColor =
                MaterialTheme.colorScheme.primary.copy(alpha = CONTEXT_RING_TRACK_ALPHA)
            val progressColor = MaterialTheme.colorScheme.primary
            Modifier.drawBehind { drawContextRing(progress, trackColor, progressColor) }
        }
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            ActionBarButton(
                onClick = if (isGenerating) onStopClick else onSendClick,
                enabled = true,
                // 环画在按钮自己的 modifier 上：与按钮同心，跟着 offset 一起走。
                modifier = Modifier
                    .offset(x = 6.dp)
                    .then(ringModifier),
            ) {
                if (isGenerating) {
                    LoadingIndicator(
                        modifier = Modifier
                            .size(28.dp)
                            .clearAndSetSemantics {
                                contentDescription = "Stop Button"
                            },
                        color = contentColor,
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_up),
                        contentDescription = "Send Button",
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
        backdrop = backdrop,
        glassStyle = LiquidGlassPresets.ComposerBar,
        modifier = modifier.fillMaxWidth(),
        leadingContent = { attachButton() },
        trailingContent = { sendButton() },
    )
}

/** 上下文环底轨的不透明度。 */
private const val CONTEXT_RING_TRACK_ALPHA = 0.22f

/** 上下文环线宽。 */
private val ContextRingStroke = 2.dp

/** 发送按钮的圆直径，与 ActionBarButton 的 requiredSize 同口径（sp 换算）。 */
private val ContextRingButtonDiameter = 48.sp

/**
 * 在发送按钮边缘画一圈上下文占用环。
 *
 * 环的外围严格等于按钮的外围（外径 = 按钮直径）：Stroke 居中对齐向两侧各占 stroke / 2，
 * 故中心线直径取 [ContextRingButtonDiameter] - stroke，使整个环紧贴并隐没在按钮轮廓内。
 */
private fun DrawScope.drawContextRing(progress: Float, trackColor: Color, progressColor: Color) {
    val stroke = ContextRingStroke.toPx()
    val arcDiameter = ContextRingButtonDiameter.toPx() - stroke
    val topLeft = Offset((size.width - arcDiameter) / 2f, (size.height - arcDiameter) / 2f)
    val ringSize = Size(arcDiameter, arcDiameter)
    drawArc(
        color = trackColor,
        startAngle = 0f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = topLeft,
        size = ringSize,
        style = Stroke(width = stroke),
    )
    if (progress > 0f) {
        drawArc(
            color = progressColor,
            startAngle = -90f,
            sweepAngle = 360f * progress.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = topLeft,
            size = ringSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun TurnActionRow(
    source: ActionSource,
    display: MessageActionsDisplay,
    onCopy: () -> Unit,
    onReGenerate: () -> Unit,
    onFork: () -> Unit,
    onRewind: () -> Unit = {},
    branchIndex: Int = 1,
    branchCount: Int = 1,
    onSwitchBranch: (Int) -> Unit = {},
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
            horizontalArrangement = Arrangement.spacedBy(if (iconOnly) 2.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton(
                icon = Icons.Default.ContentCopy,
                contentDescription = "Copy Icon",
                onClick = onCopy,
                iconOnly = iconOnly,
            )
            if (isAgent) {
                ActionButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "Regenerate Icon",
                    onClick = onReGenerate,
                    iconOnly = iconOnly,
                )
                ActionButton(
                    icon = Icons.AutoMirrored.Filled.CallSplit,
                    contentDescription = "Fork Icon",
                    onClick = onFork,
                    iconOnly = iconOnly,
                )
                // 分支翻页器：挂在 Agent 行、排在 Regenerate/Fork 之后
                if (branchCount > 1) {
                    BranchPager(
                        index = branchIndex,
                        count = branchCount,
                        iconOnly = iconOnly,
                        onSwitchBranch = onSwitchBranch,
                    )
                }
            } else {
                ActionButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "Regenerate Icon",
                    onClick = onReGenerate,
                    iconOnly = iconOnly,
                )
                ActionButton(
                    icon = Icons.Default.Undo,
                    contentDescription = "Rewind Icon",
                    onClick = onRewind,
                    iconOnly = iconOnly,
                )
            }
        }
    }
}

/**
 * 重新生成分支翻页器：‹ n/N ›。
 * 与相邻按钮同尺寸同颜色、同样无波纹（与 iconOnly 按钮一致）；
 * 只有一版时不渲染（调用方就不传入）。
 */
@Composable
private fun BranchPager(
    index: Int,
    count: Int,
    iconOnly: Boolean,
    onSwitchBranch: (Int) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val enabledColor = colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
    val disabledColor = colorScheme.onSurfaceVariant.copy(alpha = 0.32f)

    Row(verticalAlignment = Alignment.CenterVertically) {
        PagerChevron(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "Previous Branch",
            enabled = index > 1,
            iconOnly = iconOnly,
            tint = if (index > 1) enabledColor else disabledColor,
            onClick = { onSwitchBranch(-1) },
        )
        Text(
            text = "$index/$count",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = enabledColor,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        PagerChevron(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Next Branch",
            enabled = index < count,
            iconOnly = iconOnly,
            tint = if (index < count) enabledColor else disabledColor,
            onClick = { onSwitchBranch(1) },
        )
    }
}

@Composable
private fun PagerChevron(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    iconOnly: Boolean,
    tint: Color,
    onClick: () -> Unit,
) {
    val shape = G2CardShape(14.dp)
    Box(
        modifier = Modifier
            .size(if (iconOnly) 28.dp else 38.dp)
            .then(
                if (iconOnly) {
                    Modifier
                } else {
                    Modifier
                        .clip(shape)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                            shape,
                        )
                }
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                // 翻页器无波纹：去掉点击指示，不从头像式图标堆里弹出一块矩形
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(if (iconOnly) 16.dp else 20.dp),
            tint = tint,
        )
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
            .size(if (iconOnly) 28.dp else 38.dp)
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
            modifier = Modifier.size(if (iconOnly) 16.dp else 20.dp),
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
                BitmapFactory.decodeFile(path)?.asImageBitmap()
            }.getOrNull()
        }
    }.value

/**
 * 卡片的顶部遮罩 + 右上角关闭钮（图片卡 / 文件卡共用）。
 *
 * BoxScope 扩展：调用方是卡片的 Box，两者都对齐在卡片里。遮罩是卡片高度 30% 的
 * 纵向渐变（black 50% → 0%），用来衬出白色关闭钮。
 */
@Composable
private fun BoxScope.CardRemoveOverlay(
    size: Dp,
    contentDescription: String,
    onRemove: () -> Unit,
) {
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
        contentDescription = contentDescription,
        tint = Color.White,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .size(16.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onRemove,
            ),
    )
}

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
            CardRemoveOverlay(
                size = size,
                contentDescription = "Remove Image Icon",
                onRemove = onRemove,
            )
        }
    }
}

/**
 * 单个文件 / 文件夹卡。
 *
 * 紧凑态（待发条 60dp）：左上角圆角图标徽章 + 底部居左单行截断文字（加大底部边距）+ 右上角关闭钮。
 * 展开态（消息气泡 120dp）：左上角 36dp 徽章 + 底部加粗文件名 + 粗粒度分类副标题（多语言）。
 */
@Composable
internal fun HomeChatFileCard(
    file: HomeChatFile,
    size: Dp,
    cornerRadius: Dp,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val shape = G2CardShape(cornerRadius)
    val category = file.category
    val isCompact = size <= 80.dp

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (isCompact) {
            // 待发条 60dp：纯图标（无背景容器，尺寸放大至 20dp）+ 底部单行截断文字
            val displayName = formatFileNameMiddleTruncated(file.name, 11)
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = 8.dp)
                    .size(20.dp),
            )
            Text(
                text = displayName,
                fontSize = 9.5.sp,
                lineHeight = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
        } else {
            // 消息卡片 120dp：实心图标（30dp）+ 底部 2 行文件名（字号 12sp）+ 粗粒度分类说明（完全展示不截断）
            val displayName = formatFileNameMiddleTruncated(file.name, 32)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = displayName,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(category.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
        }

        if (onRemove != null) {
            CardRemoveOverlay(
                size = size,
                contentDescription = "Remove File Icon",
                onRemove = onRemove,
            )
        }
    }
}

/**
 * 附件行（composer 待发条 / 用户消息附件区共用）：图片与文件**同一行**横向滚动。
 *
 * 两种卡**同尺寸同形状**（同一个 [G2CardShape]、同一圆角、同一方形边长）：
 * 预览区尺寸不一很跳跃，而文件名可以在卡内省略。
 * 外层以与卡片相同的 G2 圆角 clip——边缘卡被裁切时仍呈现圆角形态。宽度约束由调用方给。
 */
@Composable
fun HomeChatAttachmentRow(
    images: List<HomeChatImage>,
    files: List<HomeChatFile>,
    cardSize: Dp,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    onRemoveImage: ((String) -> Unit)? = null,
    onRemoveFile: ((String) -> Unit)? = null,
) {
    Row(
        modifier = modifier
            // 宽度由调用方决定（贴内容宽或撑满）：不加 fillMaxWidth，
            // 否则消息附件行无法右对齐贴内容宽
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
        files.forEach { file ->
            HomeChatFileCard(
                file = file,
                size = cardSize,
                cornerRadius = cornerRadius,
                onRemove = onRemoveFile?.let { remove -> { remove(file.id) } },
            )
        }
    }
}
