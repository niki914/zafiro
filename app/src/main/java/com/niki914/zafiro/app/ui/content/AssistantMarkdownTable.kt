package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import com.mikepenz.markdown.compose.elements.MarkdownTableBasicText
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMElementTypes.HEADER
import org.intellij.markdown.flavours.gfm.GFMElementTypes.ROW
import org.intellij.markdown.flavours.gfm.GFMTokenTypes.CELL

/** 单列内容宽度上限，超过则单元格换行，避免长段落把一列撑到无法阅读。 */
private val TableColumnMaxContentWidth = 240.dp

/**
 * 表格列宽取该列最宽单元格的自然宽度，而非等分；整表横向滚动。
 * 排版语义等价于 CSS 的 `table-layout: auto` + `width: max-content`，
 * 单元格用 [Int.MAX_VALUE] 行数渲染，因此长内容换行而不是被省略号截断。
 * 网格线由各行的 [Modifier.drawBehind] 绘制，行高即内容自然高度，不需要额外占位。
 */
@Composable
internal fun AssistantMarkdownTable(
    content: String,
    node: ASTNode,
    style: TextStyle,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)
    val annotatorSettings = annotatorSettings()
    val cellPadding = LocalMarkdownDimens.current.tableCellPadding
    val gridThickness = LocalMarkdownDimens.current.dividerThickness
    val tableBackground = LocalMarkdownColors.current.tableBackground
    val gridColor = LocalMarkdownColors.current.dividerColor
    val gridStroke =
        if (gridThickness == Dp.Hairline) 1f else with(density) { gridThickness.toPx() }

    val hasHeader = remember(node) { node.findChildOfType(HEADER) != null }
    val rows = remember(node) {
        buildList {
            node.findChildOfType(HEADER)?.let(::add)
            node.children.filterTo(this) { it.type == ROW }
        }
    }
    val cellsByRow = remember(rows) {
        rows.map { row -> row.children.filter { it.type == CELL } }
    }
    val columnCount = cellsByRow.maxOfOrNull { it.size } ?: 0

    val columnWidths = remember(node, content, style, density, textMeasurer, cellPadding) {
        List(columnCount) { column ->
            val widestContentPx = cellsByRow.indices.maxOfOrNull { rowIndex ->
                val cell = cellsByRow[rowIndex].getOrNull(column) ?: return@maxOfOrNull 0
                val isHeader = rowIndex == 0 && hasHeader
                val cellStyle = if (isHeader) style.copy(fontWeight = FontWeight.Bold) else style
                textMeasurer.measure(
                    text = content.buildMarkdownAnnotatedString(
                        textNode = cell,
                        style = cellStyle,
                        annotatorSettings = annotatorSettings,
                    ),
                    style = cellStyle,
                    maxLines = 1,
                ).size.width
            } ?: 0
            with(density) { widestContentPx.toDp() }
                .coerceAtMost(TableColumnMaxContentWidth) + cellPadding * 2
        }
    }
    val tableWidth = columnWidths.fold(0.dp) { acc, columnWidth -> acc + columnWidth }

    Column(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .width(tableWidth)
            .background(tableBackground)
            .drawBehind {
                val halfStroke = gridStroke / 2f
                drawLine(
                    color = gridColor,
                    start = Offset(halfStroke, halfStroke),
                    end = Offset(size.width - halfStroke, halfStroke),
                    strokeWidth = gridStroke,
                )
            }
            .semantics {
                collectionInfo = CollectionInfo(rowCount = rows.size, columnCount = columnCount)
            },
    ) {
        rows.forEachIndexed { rowIndex, _ ->
            val isHeader = rowIndex == 0 && hasHeader
            Row(
                modifier = Modifier
                    .width(tableWidth)
                    .drawBehind {
                        val halfStroke = gridStroke / 2f
                        val rightEdge = size.width - halfStroke
                        val bottomEdge = size.height - halfStroke
                        drawLine(
                            gridColor,
                            Offset(halfStroke, 0f),
                            Offset(halfStroke, size.height),
                            gridStroke
                        )
                        var edge = 0f
                        columnWidths.forEachIndexed { columnIndex, columnWidth ->
                            edge += columnWidth.toPx()
                            val x = if (columnIndex == columnWidths.lastIndex) rightEdge else edge
                            drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), gridStroke)
                        }
                        drawLine(
                            gridColor,
                            Offset(halfStroke, bottomEdge),
                            Offset(rightEdge, bottomEdge),
                            gridStroke
                        )
                    },
            ) {
                for (columnIndex in 0 until columnCount) {
                    val cell = cellsByRow[rowIndex].getOrNull(columnIndex)
                    val columnWidth = columnWidths.getOrElse(columnIndex) {
                        TableColumnMaxContentWidth + cellPadding * 2
                    }
                    Column(
                        modifier = Modifier
                            .width(columnWidth)
                            .padding(cellPadding)
                            .semantics {
                                if (isHeader) heading()
                                collectionItemInfo = CollectionItemInfo(
                                    rowIndex = rowIndex,
                                    rowSpan = 1,
                                    columnIndex = columnIndex,
                                    columnSpan = 1,
                                )
                            },
                    ) {
                        if (cell != null) {
                            MarkdownTableBasicText(
                                content = content,
                                cell = cell,
                                style = if (isHeader) style.copy(fontWeight = FontWeight.Bold) else style,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip,
                                annotatorSettings = annotatorSettings,
                            )
                        }
                    }
                }
            }
        }
    }
}
