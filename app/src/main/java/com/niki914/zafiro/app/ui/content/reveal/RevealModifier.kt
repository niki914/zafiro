package com.niki914.zafiro.app.ui.content.reveal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import java.text.BreakIterator
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 一个文本块的显现句柄：组合层持有它，向节点回传排版结果；节点反过来读它携带的
 * 块信息（源文本起点、渲染文本）。句柄按块记忆（key = sourceStart），文本增长只是
 * 更新 [text]，节点身份与路径缓存不重建。
 */
@Stable
internal class RevealState internal constructor(
    internal val timeline: RevealTimeline,
    internal val sourceStart: Int,
) {
    internal var text: String = ""
    internal var node: RevealNode? = null

    val modifier: Modifier = Modifier.then(RevealElement(this))

    /** 组合期同步：把块的源文本末偏移上报给时间轴（单调 max），并记录渲染文本。 */
    fun sync(sourceEnd: Int, renderedText: String) {
        text = renderedText
        timeline.mergeTarget(sourceEnd)
    }

    fun onTextLayout(result: TextLayoutResult?) {
        node?.onTextLayout(result)
    }
}

@Composable
internal fun rememberStreamReveal(
    timeline: RevealTimeline,
    sourceStart: Int,
): RevealState = remember(timeline, sourceStart) {
    RevealState(timeline, sourceStart)
}

private data class RevealElement(
    val state: RevealState,
) : ModifierNodeElement<RevealNode>() {
    override fun create(): RevealNode = RevealNode(state)

    override fun update(node: RevealNode) {
        node.state = state
        node.refresh()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "streamReveal"
    }
}

/**
 * 按时间轴进度显现文本：进度内的字素正常绘制，下一个字素按剩余比例渐显；
 * 高度跟随显现行增长，未显现部分不占高度，保证贴底滚动的目标稳定。
 * 进度坐标见 [RevealTimeline]：本地进度 = 全局已显现偏移 - 块的源文本起点。
 */
internal class RevealNode(
    internal var state: RevealState,
) : Modifier.Node(), DrawModifierNode, LayoutModifierNode {

    private val timeline get() = state.timeline
    private val sourceStart get() = state.sourceStart
    private val text get() = state.text

    private val alphaPaint = Paint()
    private var layoutResult: TextLayoutResult? = null

    // 字素边界（每个字素的起始偏移，末位为 text.length）；块级文本很短，文本变化时整体重算
    private var boundaries: IntArray = intArrayOf(0)
    private var boundariesText: String = ""

    // 路径缓存：同一排版下，已显现字素的合并路径与下一个字素路径
    private var pathLayout: TextLayoutResult? = null
    private var pathGraphemeIndex: Int = -1
    private var fullPath: Path? = null
    private var nextPath: Path? = null

    private var measuredVisibleHeight: Int = -1

    override fun onAttach() {
        timeline.attach(this)
    }

    override fun onDetach() {
        timeline.detach(this)
        layoutResult = null
        pathLayout = null
        pathGraphemeIndex = -1
        fullPath = null
        nextPath = null
    }

    fun onTextLayout(result: TextLayoutResult?) {
        if (layoutResult === result) return
        layoutResult = result
        pathLayout = null
        pathGraphemeIndex = -1
        fullPath = null
        nextPath = null
        refresh()
    }

    /** 帧时钟推进后回调：按需请求重测（跨行）并重绘。 */
    fun onRevealChanged() {
        refresh()
    }

    internal fun refresh() {
        if (!isAttached) return
        val layout = layoutResult ?: return
        val wanted = visibleHeight(fullHeight = layout.size.height)
        if (wanted != measuredVisibleHeight) {
            measuredVisibleHeight = wanted
            invalidateMeasurement()
        }
        invalidateDraw()
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        val visible = visibleHeight(fullHeight = placeable.height)
        measuredVisibleHeight = visible
        val height = visible.coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(placeable.width, height) {
            placeable.place(0, 0)
        }
    }

    override fun ContentDrawScope.draw() {
        val contentScope = this
        val local = timeline.revealedChars - sourceStart
        if (local >= text.length) {
            drawContent()
            return
        }
        if (local <= 0f) return
        val layout = layoutResult ?: run {
            drawContent()
            return
        }
        val bounds = ensureBoundaries()
        val fullIndex = lastBoundaryIndexAtMost(bounds, floor(local).toInt())
        ensurePaths(layout, bounds, fullIndex)

        // 已显现字素：裁剪到前 fullIndex 个字素的合并路径
        if (fullIndex > 0) {
            fullPath?.let { path -> clipPath(path) { contentScope.drawContent() } }
        }

        // 下一个字素：按剩余比例渐显
        val graphemeStart = bounds[fullIndex]
        val graphemeEnd = bounds.getOrNull(fullIndex + 1) ?: return
        if (graphemeEnd <= graphemeStart) return
        val alpha = ((local - graphemeStart) / (graphemeEnd - graphemeStart)).coerceIn(0f, 1f)
        if (alpha <= 0f) return
        nextPath?.let { path ->
            clipPath(path) {
                alphaPaint.alpha = alpha
                drawContext.canvas.saveLayer(Rect(Offset.Zero, size), alphaPaint)
                try {
                    contentScope.drawContent()
                } finally {
                    drawContext.canvas.restore()
                }
            }
        }
    }

    /** 显现进度对应的高度：最后一行已显现字素的行底；未开始显现为 0。 */
    private fun visibleHeight(fullHeight: Int): Int {
        val local = timeline.revealedChars - sourceStart
        if (local >= text.length) return fullHeight
        if (local <= 0f) return 0
        val layout = layoutResult ?: return fullHeight
        val bounds = ensureBoundaries()
        val index = lastBoundaryIndexAtMost(bounds, ceil(local).toInt())
        val end = bounds[index]
        if (end <= 0 || layout.lineCount == 0) return 0
        val line = layout.getLineForOffset((end - 1).coerceAtMost(text.length - 1))
        return ceil(layout.getLineBottom(line)).toInt().coerceAtMost(fullHeight)
    }

    private fun ensureBoundaries(): IntArray {
        if (boundariesText != text) {
            boundaries = graphemeStarts(text)
            boundariesText = text
        }
        return boundaries
    }

    private fun ensurePaths(
        layout: TextLayoutResult,
        bounds: IntArray,
        fullIndex: Int,
    ) {
        val sameLayout = pathLayout === layout
        if (sameLayout && pathGraphemeIndex == fullIndex) return

        fullPath = if (fullIndex <= 0) {
            null
        } else if (sameLayout &&
            fullIndex == pathGraphemeIndex + 1 &&
            bounds[fullIndex] == bounds[pathGraphemeIndex] + 1
        ) {
            // 相邻单字素推进：把上一帧的「下一个字素」并入已显现路径，只重算下一个
            val accumulated = fullPath ?: Path()
            nextPath?.let(accumulated::addPath)
            accumulated
        } else {
            layout.getPathForRange(0, bounds[fullIndex])
        }
        pathLayout = layout
        pathGraphemeIndex = fullIndex
        nextPath = nextGraphemePath(layout, bounds, fullIndex)
    }

    private fun nextGraphemePath(
        layout: TextLayoutResult,
        bounds: IntArray,
        fullIndex: Int,
    ): Path? {
        val start = bounds[fullIndex]
        val end = bounds.getOrNull(fullIndex + 1) ?: return null
        return if (end > start && end <= text.length) {
            layout.getPathForRange(start, end)
        } else {
            null
        }
    }

    /** 最后一个 <= value 的边界下标；value 小于首边界时返回 0。 */
    private fun lastBoundaryIndexAtMost(bounds: IntArray, value: Int): Int {
        val found = bounds.binarySearch(value)
        return if (found >= 0) found else -found - 2
    }
}

/** 每个字素的起始偏移数组，形如 [0, s1, s2, ..., length]。 */
private fun graphemeStarts(text: String): IntArray {
    if (text.isEmpty()) return intArrayOf(0)
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<Int>(text.length + 1)
    var boundary = iterator.first()
    while (boundary != BreakIterator.DONE) {
        result += boundary
        boundary = iterator.next()
    }
    if (result.lastOrNull() != text.length) result += text.length
    return result.toIntArray()
}
