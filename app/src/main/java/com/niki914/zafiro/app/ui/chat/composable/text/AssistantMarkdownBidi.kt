package com.niki914.zafiro.app.ui.chat.composable.text

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.LayoutDirection
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownA11yLabels
import com.mikepenz.markdown.compose.LocalMarkdownAnnotator
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownComponents
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.LocalMarkdownPadding
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownBulletList
import com.mikepenz.markdown.compose.elements.MarkdownOrderedList
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.compose.elements.listDepth
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.niki914.uikit.text.BidiDirection
import com.niki914.uikit.text.BidiText
import com.niki914.uikit.text.isolateLtrIslands
import com.niki914.uikit.text.withBidiDirection
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMTokenTypes

/*
 * 助手 Markdown 的双向文本（RTL）支持：每个块（段落、标题、列表、引用、表格）按自己的词投票决定方向，
 * 而不是整条消息一个方向；代码始终 LTR。只改文字方向，不镜像应用布局。
 * 判为 RTL 的块，在库生成的 AnnotatedString 上隔离 LTR 孤岛（行内代码、路径、参数、数字区间），
 * 样式与链接注解保持不变；LTR 块的文本与库默认渲染完全一致。
 */

// 不参与投票的节点：代码、链接目标与引用定义、图片
private val nonVotingTypes: Set<IElementType> = setOf(
    MarkdownElementTypes.CODE_SPAN,
    MarkdownElementTypes.CODE_FENCE,
    MarkdownElementTypes.CODE_BLOCK,
    MarkdownElementTypes.IMAGE,
    MarkdownElementTypes.LINK_DESTINATION,
    MarkdownElementTypes.LINK_TITLE,
    MarkdownElementTypes.LINK_DEFINITION,
    MarkdownElementTypes.AUTOLINK,
    GFMTokenTypes.GFM_AUTOLINK,
)

/**
 * 是否允许在显示文本里插入隔离符。整条消息自带方向控制符时为 false，
 * 与复制时是否清理控制符（[com.niki914.uikit.text.BidiSelectionContainer]）保持一致。
 */
internal val LocalBidiIsolation = staticCompositionLocalOf { false }

/** 块内可读文字（TEXT 叶子，以空格分隔），供方向投票。 */
internal fun readableText(content: String, node: ASTNode): String = buildString {
    fun visit(current: ASTNode) {
        if (current.type in nonVotingTypes) return
        // [可见文字][标识] 里的第二个方括号只是引用标识；[可见文字] 简写引用的标签是正文
        if (current.type == MarkdownElementTypes.LINK_LABEL &&
            current.parent?.type == MarkdownElementTypes.FULL_REFERENCE_LINK
        ) {
            return
        }
        if (current.type == MarkdownTokenTypes.TEXT) {
            append(content, current.startOffset, current.endOffset)
            append(' ')
            return
        }
        current.children.forEach(::visit)
    }
    visit(node)
}

/** 以块的源文本为 key：流式输出时未变化的块不重复投票。 */
@Composable
internal fun rememberBlockDirection(content: String, node: ASTNode): BidiDirection? {
    val source = content.substring(node.startOffset, node.endOffset)
    return remember(source) { BidiText.directionOf(readableText(content, node), backtickCode = false) }
}

/**
 * 与库的 MarkdownParagraph / MarkdownText 相同的 AnnotatedString，额外隔离 LTR 孤岛。
 * 行内代码按库给它的 codeSpanStyle 定位，整段（含两侧留白）作为一个孤岛。
 */
@Composable
internal fun rememberIsolatedMarkdownText(content: String, node: ASTNode, style: TextStyle): AnnotatedString {
    val settings = annotatorSettings()
    val styled = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(content = content, node = node, annotatorSettings = settings)
        pop()
    }
    return remember(styled, settings.codeSpanStyle) {
        val codeSpans = styled.spanStyles
            .filter { it.item == settings.codeSpanStyle && it.end > it.start }
            .map { it.start until it.end }
        styled.isolateLtrIslands(codeSpans)
    }
}

@Composable
internal fun ProvideLayoutDirection(direction: BidiDirection?, content: @Composable () -> Unit) {
    val layoutDirection = when (direction) {
        BidiDirection.Rtl -> LayoutDirection.Rtl
        BidiDirection.Ltr -> LayoutDirection.Ltr
        null -> LocalLayoutDirection.current
    }
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection, content = content)
}

@Composable
internal fun BidiParagraph(model: MarkdownComponentModel) {
    val direction = rememberBlockDirection(model.content, model.node)
    val style = model.typography.paragraph.withBidiDirection(direction)
    if (direction != BidiDirection.Rtl || !LocalBidiIsolation.current) {
        MarkdownParagraph(model.content, model.node, Modifier.fillMaxWidth(), style)
        return
    }
    MarkdownText(
        content = rememberIsolatedMarkdownText(model.content, model.node, style),
        node = model.node,
        modifier = Modifier.fillMaxWidth(),
        style = style,
        sourceContent = model.content,
    )
}

@Composable
internal fun BidiHeader(
    model: MarkdownComponentModel,
    style: TextStyle,
    contentChildType: IElementType = MarkdownTokenTypes.ATX_CONTENT,
) {
    val direction = rememberBlockDirection(model.content, model.node)
    val headerStyle = style.withBidiDirection(direction)
    val modifier = Modifier
        .fillMaxWidth()
        .semantics { heading() }
    if (direction != BidiDirection.Rtl || !LocalBidiIsolation.current) {
        MarkdownText(model.content, model.node, headerStyle, modifier, contentChildType)
        return
    }
    val textNode = model.node.findChildOfType(contentChildType) ?: model.node
    MarkdownText(
        content = rememberIsolatedMarkdownText(model.content, textNode, headerStyle),
        node = model.node,
        modifier = modifier,
        style = headerStyle,
        sourceContent = model.content,
    )
}

/**
 * RTL 表格单元格：隔离后的文本走 MarkdownText，但与 MarkdownTableBasicText 一样保持图片内联，
 * 不把较高的图片提升为块级（表格单元格里不应换成 Column）。
 */
@Composable
internal fun BidiTableCellText(content: String, cell: ASTNode, style: TextStyle) {
    val text = rememberIsolatedMarkdownText(content, cell, style)
    val annotator = LocalMarkdownAnnotator.current
    val inlineImages = remember(annotator) {
        val config = annotator.config
        markdownAnnotator(
            config = markdownAnnotatorConfig(
                eolAsNewLine = config.eolAsNewLine,
                inlineImageAsBlock = false,
                showImageAltTooltip = config.showImageAltTooltip,
                imageAltTooltipHoverDelayMs = config.imageAltTooltipHoverDelayMs,
            ),
            annotate = annotator.annotate,
        )
    }
    CompositionLocalProvider(LocalMarkdownAnnotator provides inlineImages) {
        MarkdownText(content = text, node = cell, style = style, sourceContent = content)
    }
}

/** 列表的标记位置随整份列表的投票（同一列表内标记对齐），每一项的文字仍按自己的段落方向排版。 */
@Composable
internal fun BidiOrderedList(model: MarkdownComponentModel) {
    ProvideLayoutDirection(rememberBlockDirection(model.content, model.node)) {
        MarkdownOrderedList(model.content, model.node, style = model.typography.ordered, depth = model.listDepth)
    }
}

@Composable
internal fun BidiBulletList(model: MarkdownComponentModel) {
    ProvideLayoutDirection(rememberBlockDirection(model.content, model.node)) {
        MarkdownBulletList(model.content, model.node, style = model.typography.bullet, depth = model.listDepth)
    }
}

/** 代码块固定 LTR，包括出现在 RTL 列表或引用里的代码块。 */
@Composable
internal fun LtrCode(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

/**
 * 引用块：结构与库自带的 MarkdownBlockQuote 一致，竖线画在引用方向的起点一侧
 * （库实现把竖线固定画在左侧）。
 */
@Composable
internal fun BidiBlockQuote(model: MarkdownComponentModel) {
    ProvideLayoutDirection(rememberBlockDirection(model.content, model.node)) {
        BlockQuoteBody(content = model.content, node = model.node, style = model.typography.quote)
    }
}

@Composable
private fun BlockQuoteBody(content: String, node: ASTNode, style: TextStyle) {
    val barColor = if (style.color.isSpecified) style.color else LocalMarkdownColors.current.text
    val barThickness = LocalMarkdownDimens.current.blockQuoteThickness
    val padding = LocalMarkdownPadding.current
    val components = LocalMarkdownComponents.current
    val label = LocalMarkdownA11yLabels.current.blockquote
    val emptyLineHeight = with(LocalDensity.current) {
        when {
            style.lineHeight.isSp -> style.lineHeight.toDp()
            style.fontSize.isSp -> style.fontSize.toDp()
            else -> padding.blockQuoteText.calculateTopPadding() + padding.blockQuoteText.calculateBottomPadding()
        }
    }
    Column(
        modifier = Modifier
            .semantics { contentDescription = label }
            .drawBehind {
                val inset = padding.blockQuoteBar.calculateStartPadding(layoutDirection).toPx()
                val x = if (layoutDirection == LayoutDirection.Ltr) inset else size.width - inset
                drawLine(
                    color = barColor,
                    strokeWidth = barThickness.toPx(),
                    start = Offset(x, padding.blockQuoteBar.calculateTopPadding().toPx()),
                    end = Offset(x, size.height - padding.blockQuoteBar.calculateBottomPadding().toPx()),
                )
            }
            .padding(padding.blockQuote),
    ) {
        var priorNestedQuote = false
        node.children.forEachIndexed { index, child ->
            key(child.startOffset) {
                when (child.type) {
                    MarkdownElementTypes.BLOCK_QUOTE -> {
                        if (!priorNestedQuote && index != 0) {
                            Spacer(Modifier.height(padding.blockQuoteText.calculateBottomPadding()))
                        }
                        ProvideLayoutDirection(rememberBlockDirection(content, child)) {
                            BlockQuoteBody(content = content, node = child, style = style)
                        }
                        priorNestedQuote = true
                    }

                    MarkdownTokenTypes.EOL -> Spacer(Modifier.height(emptyLineHeight))

                    else -> {
                        if (index == 0 || priorNestedQuote) {
                            Spacer(Modifier.height(padding.blockQuoteText.calculateTopPadding()))
                        }
                        priorNestedQuote = false
                        MarkdownElement(node = child, components = components, content = content, includeSpacer = false)
                        if (index == node.children.lastIndex) {
                            Spacer(Modifier.height(padding.blockQuoteText.calculateBottomPadding()))
                        }
                    }
                }
            }
        }
    }
}
