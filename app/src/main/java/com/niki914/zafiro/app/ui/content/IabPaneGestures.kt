package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlin.math.abs
import kotlin.math.sqrt

// Minimum pane size in real pixels, as requested.
internal const val IAB_MIN_PANE_WIDTH_PX = 260
internal const val IAB_MIN_PANE_HEIGHT_PX = 180

/**
 * Result of a clamped pane geometry operation.
 */
internal data class PaneGeometry(
    val size: IntSize,
    val offset: IntOffset
)

/**
 * Which bottom corner is being dragged.
 *
 * BottomEnd:
 *   - top-left stays fixed
 *   - right/bottom edges move
 *
 * BottomStart:
 *   - top-right stays fixed
 *   - left/bottom edges move
 */
internal enum class IabResizeCorner {
    BottomEnd,
    BottomStart
}

/**
 * Active gesture kind. Only one pane gesture is authoritative at a time.
 */
internal enum class IabPaneGestureMode {
    Move,
    Resize
}

/**
 * Non-recomposing gesture bookkeeping.
 *
 * This object is remembered once. It stores the geometry and screen-space pointer
 * that existed when the gesture began. During the drag we never accumulate local
 * deltas inside the moving pane; we always compute:
 *
 *     liveGeometry = startGeometry + (currentWindowPointer - startWindowPointer)
 *
 * That removes the feedback loop caused by moving/resizing the same node that
 * receives pointer events.
 */
internal class IabPaneGestureState {
    var isDragging: Boolean = false
        private set

    var mode: IabPaneGestureMode? = null
        private set

    var corner: IabResizeCorner? = null
        private set

    var startSize: IntSize = IntSize.Zero
        private set

    var startOffset: IntOffset = IntOffset.Zero
        private set

    var startScreenPointer: Offset? = null
        private set

    // r19 BUG1：首次 Down 的 window 绝对坐标（PointerInputModifierNode 路径）。
    var startDragWindow: Offset = Offset.Zero
        private set

    fun beginMove(currentSize: IntSize, currentOffset: IntOffset, downWindow: Offset = Offset.Zero) {
        if (isDragging) return
        isDragging = true
        mode = IabPaneGestureMode.Move
        corner = null
        startSize = currentSize
        startOffset = currentOffset
        startScreenPointer = null
        startDragWindow = downWindow
    }

    fun beginResize(
        resizeCorner: IabResizeCorner,
        currentSize: IntSize,
        currentOffset: IntOffset,
        downWindow: Offset = Offset.Zero
    ) {
        if (isDragging) return
        isDragging = true
        mode = IabPaneGestureMode.Resize
        corner = resizeCorner
        startSize = currentSize
        startOffset = currentOffset
        startScreenPointer = null
        startDragWindow = downWindow
    }

    /**
     * The first drag event establishes the screen-space origin of the gesture.
     * This avoids relying on detectDragGestures' local onDragStart position.
     */
    fun resolveStartScreenPointer(currentScreenPointer: Offset): Offset {
        val existing = startScreenPointer
        return existing ?: currentScreenPointer.also { startScreenPointer = it }
    }

    fun end() {
        isDragging = false
        mode = null
        corner = null
        startScreenPointer = null
        startDragWindow = Offset.Zero
    }
}

/**
 * Clamp a move-only offset.
 *
 * Rules:
 * - x stays inside [0, area.width - width]
 * - y stays inside [topClearancePx, area.height - height]
 */
internal fun clampIabMoveOffset(
    offset: IntOffset,
    size: IntSize,
    area: IntSize,
    topClearancePx: Int
): IntOffset {
    if (area.width <= 0 || area.height <= 0) return offset

    val minTop = topClearancePx.coerceIn(0, area.height)
    val maxLeft = (area.width - size.width).coerceAtLeast(0)
    val maxTop = (area.height - size.height).coerceAtLeast(minTop)

    return IntOffset(
        x = offset.x.coerceIn(0, maxLeft),
        y = offset.y.coerceIn(minTop, maxTop)
    )
}

/**
 * Clamp a resize gesture result while preserving the anchored edge.
 *
 * The parent does not receive a corner enum. Instead, it infers the anchored edge
 * by comparing desired geometry with the previous live geometry:
 *
 * BottomEnd:
 *   desired.left == previous.left
 *   desired.top == previous.top
 *
 * BottomStart:
 *   desired.right == previous.right
 *   desired.top == previous.top
 *
 * This keeps the API to a single:
 *   onResizeFromGesture(newSize, newOffset, commitOnEnd)
 */
internal fun clampIabResizeGeometry(
    desiredSize: IntSize,
    desiredOffset: IntOffset,
    previousSize: IntSize,
    previousOffset: IntOffset,
    area: IntSize,
    topClearancePx: Int
): PaneGeometry {
    if (area.width <= 0 || area.height <= 0) {
        return PaneGeometry(desiredSize, desiredOffset)
    }

    val minTop = topClearancePx.coerceIn(0, area.height)

    val prevLeft = previousOffset.x
    val prevRight = previousOffset.x + previousSize.width
    val prevTop = previousOffset.y
    val prevBottom = previousOffset.y + previousSize.height

    val desiredLeft = desiredOffset.x
    val desiredRight = desiredOffset.x + desiredSize.width
    val desiredTop = desiredOffset.y
    val desiredBottom = desiredOffset.y + desiredSize.height

    val widthChanged = desiredSize.width != previousSize.width
    val heightChanged = desiredSize.height != previousSize.height

    // Tolerance of 1px absorbs integer truncation from float pointer coordinates.
    val anchorLeft = widthChanged && abs(desiredLeft - prevLeft) <= 1
    val anchorRight = widthChanged && abs(desiredRight - prevRight) <= 1

    val anchorTop = heightChanged && abs(desiredTop - prevTop) <= 1
    val anchorBottom = heightChanged && abs(desiredBottom - prevBottom) <= 1

    var width = desiredSize.width
    var left = desiredOffset.x

    if (anchorLeft) {
        // BottomEnd horizontal behavior: left edge fixed.
        val fixedLeft = prevLeft.coerceIn(0, area.width)
        val maxW = (area.width - fixedLeft).coerceAtLeast(0)
        val minW = IAB_MIN_PANE_WIDTH_PX.coerceAtMost(maxW)

        width = width.coerceIn(minW, maxW)
        left = fixedLeft
    } else if (anchorRight) {
        // BottomStart horizontal behavior: right edge fixed.
        val fixedRight = prevRight.coerceIn(0, area.width)
        val maxW = fixedRight.coerceAtMost(area.width).coerceAtLeast(0)
        val minW = IAB_MIN_PANE_WIDTH_PX.coerceAtMost(maxW)

        width = width.coerceIn(minW, maxW)
        left = fixedRight - width
    } else {
        // No horizontal anchor: just keep the pane inside the chat area.
        val maxW = area.width.coerceAtLeast(0)
        val minW = IAB_MIN_PANE_WIDTH_PX.coerceAtMost(maxW)

        width = width.coerceIn(minW, maxW)
        left = left.coerceIn(0, (area.width - width).coerceAtLeast(0))
    }

    var height = desiredSize.height
    var top = desiredOffset.y

    if (anchorTop) {
        // Both bottom handles keep the top edge fixed.
        val fixedTop = prevTop.coerceIn(minTop, area.height)
        val maxH = (area.height - fixedTop).coerceAtLeast(0)
        val minH = IAB_MIN_PANE_HEIGHT_PX.coerceAtMost(maxH)

        height = height.coerceIn(minH, maxH)
        top = fixedTop
    } else if (anchorBottom) {
        // Defensive support for a future bottom-anchored resize.
        val fixedBottom = prevBottom.coerceIn(minTop, area.height)
        val maxH = (fixedBottom - minTop).coerceAtLeast(0)
        val minH = IAB_MIN_PANE_HEIGHT_PX.coerceAtMost(maxH)

        height = height.coerceIn(minH, maxH)
        top = fixedBottom - height
    } else {
        // No vertical anchor: keep pane below top clearance and inside area.
        val maxH = (area.height - minTop).coerceAtLeast(0)
        val minH = IAB_MIN_PANE_HEIGHT_PX.coerceAtMost(maxH)

        height = height.coerceIn(minH, maxH)
        top = top.coerceIn(minTop, (area.height - height).coerceAtLeast(minTop))
    }

    return PaneGeometry(
        size = IntSize(width, height),
        offset = IntOffset(left, top)
    )
}

/**
 * Read a State<IntSize> inside the measure block.
 *
 * This is critical: changing sizeState.value invalidates layout, but does not
 * recompose the composable that installed this modifier.
 */
internal fun Modifier.iabPaneSize(sizeState: State<IntSize>): Modifier = layout { measurable, constraints ->
    val size = sizeState.value

    val boundedWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
    val boundedHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE

    val width = size.width.coerceAtLeast(0).coerceAtMost(boundedWidth)
    val height = size.height.coerceAtLeast(0).coerceAtMost(boundedHeight)

    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) {
        placeable.placeRelative(0, 0)
    }
}

/**
 * Read a State<IntOffset> inside the offset lambda.
 *
 * Changing offsetState.value moves the pane without recomposition.
 */
internal fun Modifier.iabPaneOffset(offsetState: State<IntOffset>): Modifier = offset {
    offsetState.value
}

/**
 * Replacement for the old iabResizeHandle.
 *
 * Each handle does not keep its own mutable anchor across frames. It uses the
 * shared [IabPaneGestureState], which captures:
 *
 *   - start pane size
 *   - start pane offset
 *   - start screen pointer
 *
 * Then every frame computes desired geometry from:
 *
 *   start + screenDelta
 *
 * and hands it to the parent for clamping.
 */
@Composable
internal fun IabResizeHandle(
    corner: IabResizeCorner,
    liveSize: State<IntSize>,
    liveOffset: State<IntOffset>,
    gesture: IabPaneGestureState,
    onResizeFromGesture: (IntSize, IntOffset, Boolean) -> Unit,
    handleFill: Color,
    handleLineColor: Color,
    handleStrokePx: Float
) {
    val currentOnResize by rememberUpdatedState(onResizeFromGesture)

    // r19 BUG1（tremor 根因修复，Qwen 指定方案 line 84「remember { } of the current
    // LayoutCoordinates」）：legacy pointerInput {} 的 PointerInputScope 没有坐标转换
    // （编译期已证实，本 Compose 版本 positionInWindow 仅无参扩展函数，成员方法只有
    // localToWindow），节点 API 又在本项目 Compose 版本不可用（addPointerInputNode 未
    // 编译通过）。改用 onGloballyPositioned 把本节点的 LayoutCoordinates 写进 holder，
    // 再在 pointerInput 里读它调 localToWindow（window 绝对坐标）；面板移动 / 缩放时
    // 本地坐标自我反馈漂移（tremor 根因）随之消失。holder 在重组前已就位，不重启手势、不改语义。
    var handleCoordinates: LayoutCoordinates? = null

    Box(
        modifier = Modifier
            .onGloballyPositioned { handleCoordinates = it }
            .size(24.dp)
            .clip(CircleShape)
            .background(handleFill)
            .drawBehind {
                // Draw a three-line diagonal grip.
                // BottomEnd grip slopes like "\", BottomStart grip slopes like "/".
                val stroke = handleStrokePx.coerceAtLeast(1f)
                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                val halfLength = size.minDimension * 0.30f
                val spacing = size.minDimension * 0.18f

                val direction = if (corner == IabResizeCorner.BottomEnd) {
                    Offset(1f, 1f)
                } else {
                    Offset(-1f, 1f)
                }

                val length = sqrt(direction.x * direction.x + direction.y * direction.y)
                val norm = Offset(direction.x / length, direction.y / length)
                val perpendicular = Offset(-norm.y, norm.x)

                for (index in -1..1) {
                    val shift = perpendicular * (spacing * index)
                    val start = center + shift - norm * halfLength
                    val end = center + shift + norm * halfLength

                    drawLine(
                        color = handleLineColor,
                        start = start,
                        end = end,
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
            // r19 BUG1：window 绝对坐标取自 onGloballyPositioned 捕获的本节点
            // LayoutCoordinates（holder），调 localToWindow（成员方法），
            // 逐帧 window delta 不再受面板自身移动 / 缩放反馈（tremor 根因）。
            .pointerInput(corner, liveSize, liveOffset, gesture) {
                detectDragGestures(
                    onDragStart = { startLocal ->
                        // r19 BUG1：Down 时刻的本地坐标→window 绝对坐标，逐帧
                        // window delta 不再受面板自身移动 / 缩放反馈。
                        val windowPos = handleCoordinates?.localToWindow(startLocal) ?: Offset.Zero
                        gesture.beginResize(
                            resizeCorner = corner,
                            currentSize = liveSize.value,
                            currentOffset = liveOffset.value,
                            downWindow = windowPos,
                        )
                    },
                    onDrag = { change, _ ->
                        if (
                            gesture.isDragging &&
                            gesture.mode == IabPaneGestureMode.Resize &&
                            gesture.corner == corner
                        ) {
                            val windowPos = handleCoordinates?.localToWindow(change.position)
                            if (windowPos == null) return@detectDragGestures
                            val dx = (windowPos.x - gesture.startDragWindow.x).toInt()
                            val dy = (windowPos.y - gesture.startDragWindow.y).toInt()

                            when (corner) {
                                IabResizeCorner.BottomEnd -> {
                                    // Right-bottom handle:
                                    // top-left stays fixed, width/height follow delta.
                                    val desiredSize = IntSize(
                                        width = gesture.startSize.width + dx,
                                        height = gesture.startSize.height + dy
                                    )

                                    currentOnResize(desiredSize, gesture.startOffset, false)
                                }

                                IabResizeCorner.BottomStart -> {
                                    // Left-bottom handle:
                                    // top-right stays fixed.
                                    //
                                    // Dragging left means dx < 0:
                                    //   width increases
                                    //   offset.x decreases
                                    //
                                    // Dragging right means dx > 0:
                                    //   width decreases
                                    //   offset.x increases
                                    val desiredWidth = gesture.startSize.width - dx
                                    val desiredHeight = gesture.startSize.height + dy

                                    val fixedRight = gesture.startOffset.x + gesture.startSize.width
                                    val desiredOffset = IntOffset(
                                        x = fixedRight - desiredWidth,
                                        y = gesture.startOffset.y,
                                    )

                                    currentOnResize(
                                        IntSize(desiredWidth, desiredHeight),
                                        desiredOffset,
                                        false,
                                    )
                                }
                            }

                            change.consume()
                        }
                    },
                    onDragEnd = {
                        if (
                            gesture.isDragging &&
                            gesture.mode == IabPaneGestureMode.Resize &&
                            gesture.corner == corner
                        ) {
                            // Commit final live geometry.
                            currentOnResize(liveSize.value, liveOffset.value, true)
                            gesture.end()
                        }
                    },
                    onDragCancel = {
                        if (
                            gesture.isDragging &&
                            gesture.mode == IabPaneGestureMode.Resize &&
                            gesture.corner == corner
                        ) {
                            // Cancel restores the exact geometry captured at drag start.
                            currentOnResize(gesture.startSize, gesture.startOffset, true)
                            gesture.end()
                        }
                    }
                )
            }
    )
}
