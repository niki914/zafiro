package com.niki914.zafiro.app.ui.content.composer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.niki914.uikit.infra.ActionBarButton
import com.niki914.uikit.infra.LiquidGlassPresets
import com.niki914.uikit.infra.component.LiquidTextField
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.home.HomeChatFile
import com.niki914.zafiro.app.ui.model.home.HomeChatImage

/** 紧凑态 composer 最小高度：12dp 容器垂直 padding × 2 + 48sp 按钮 footprint。 */
internal val COMPACT_COMPOSER_MIN_HEIGHT = 72.dp

/** 展开态编辑区最大行数。 */
private const val EXPANDED_MAX_LINES = 7

/** 上下文环底轨的不透明度。 */
private const val CONTEXT_RING_TRACK_ALPHA = 0.22f

/** 上下文环线宽。 */
private val ContextRingStroke = 2.dp

/** 发送按钮的圆直径，与 ActionBarButton 的 requiredSize 同口径（sp 换算）。 */
private val ContextRingButtonDiameter = 48.sp

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
