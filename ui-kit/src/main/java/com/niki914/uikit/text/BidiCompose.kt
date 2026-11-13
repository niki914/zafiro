package com.niki914.uikit.text

import android.content.ClipData
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection

fun BidiDirection?.toTextDirection(): TextDirection = when (this) {
    BidiDirection.Rtl -> TextDirection.Rtl
    BidiDirection.Ltr -> TextDirection.Ltr
    null -> TextDirection.Content
}

/**
 * 按 [direction] 排版并对齐到该方向的起点。null（没有投票词、多段输入、原文自带方向控制符）时
 * 按内容的首个强字符排版，与平台行为一致。
 */
fun TextStyle.withBidiDirection(direction: BidiDirection?): TextStyle =
    if (direction == null) {
        withContentDirection()
    } else {
        copy(textDirection = direction.toTextDirection(), textAlign = TextAlign.Start)
    }

/**
 * 纯文本按每段内容决定方向，与 [rememberBidiDisplayText] 搭配（段首插入的 RLM/LRM 由此生效）。
 * Compose 默认的 TextDirection.Unspecified 固定为布局方向，不看内容。
 */
fun TextStyle.withContentDirection(): TextStyle = copy(textDirection = TextDirection.Content)

/** 纯文本的显示形态，见 [BidiText.forDisplay]。 */
@Composable
fun rememberBidiDisplayText(text: String): String = remember(text) { BidiText.forDisplay(text) }

/**
 * 显示文本插入了方向控制符时使用的 [SelectionContainer]：SelectionContainer 通过 [LocalClipboard] 复制，
 * [stripMarks] 为 true 时在写入剪贴板前去掉显示用的控制符，复制出的仍是原文。
 * 原文自带控制符时传 false（此时也不会插入）。
 */
@Composable
fun BidiSelectionContainer(
    stripMarks: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!stripMarks) {
        SelectionContainer(modifier = modifier, content = content)
        return
    }
    val clipboard = LocalClipboard.current
    val stripping = remember(clipboard) { MarkStrippingClipboard(clipboard) }
    CompositionLocalProvider(LocalClipboard provides stripping) {
        SelectionContainer(modifier = modifier, content = content)
    }
}

private class MarkStrippingClipboard(private val delegate: Clipboard) : Clipboard by delegate {
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        delegate.setClipEntry(clipEntry?.withoutInsertedMarks())
    }
}

private fun ClipEntry.withoutInsertedMarks(): ClipEntry {
    val data = clipData
    if (data.itemCount == 0) return this
    val text = data.getItemAt(0).text?.toString() ?: return this
    val stripped = BidiText.stripInsertedMarks(text)
    if (stripped == text) return this
    return ClipEntry(ClipData.newPlainText(data.description.label, stripped))
}

// Compose foundation 里 InlineTextContent 使用的注解 tag
private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

/**
 * RTL 段落的显示隔离：把 [BidiText.ltrIslands] 找到的孤岛（加上 [extraIslands]，如行内代码）包进 LRI…PDI。
 * 样式、链接与其他注解随原文一起保留；段落样式按原范围整体重新应用，插入的控制符不切开段落；
 * 与内嵌图片重叠的孤岛跳过，避免拆开占位符。
 */
fun AnnotatedString.isolateLtrIslands(extraIslands: List<IntRange> = emptyList()): AnnotatedString {
    if (BidiText.hasBidiControls(text)) return this
    val inlineContent = getStringAnnotations(INLINE_CONTENT_TAG, 0, length)
    val islands = BidiText.ltrIslands(text, extraIslands).filter { island ->
        inlineContent.none { it.start <= island.last && it.end > island.first }
    }
    if (islands.isEmpty()) return this
    val paragraphs = paragraphStyles
    val source = if (paragraphs.isEmpty()) this else flatMapAnnotations { range ->
        if (range.item is ParagraphStyle) emptyList() else listOf(range)
    }
    // 原文位置 -> 插入控制符后的位置（落在孤岛起点时位于 LRI 之前，落在孤岛之后时位于 PDI 之后）
    fun shifted(index: Int) = index + islands.count { it.first < index } + islands.count { it.last < index }
    return buildAnnotatedString {
        var cursor = 0
        for (island in islands) {
            append(source.subSequence(cursor, island.first))
            append(BidiText.LRI)
            append(source.subSequence(island.first, island.last + 1))
            append(BidiText.PDI)
            cursor = island.last + 1
        }
        append(source.subSequence(cursor, source.length))
        paragraphs.forEach { addStyle(it.item, shifted(it.start), shifted(it.end)) }
    }
}
