// githooks:ignore-file file-token-limit
package com.niki914.uikit.infra.component.settings.item

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.control.ZafiroSlider
import com.niki914.uikit.control.ZafiroSwitch
import com.niki914.uikit.infra.component.SettingsItemSurface
import com.niki914.uikit.infra.shape.G2CardShape
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class SettingHeaderSlotId {
    Leading,
    Center,
    Preview,
    Action,
}

/**
 * 新一代统一设置项核心底座 (SettingCardItem)：
 * 1. 严格划分为「可展开」与「不可展开」两大类；
 * 2. 统一头部行 (Header Row)，高度完全由 Center 标题/副标题自适应驱动 (最多4行)；
 * 3. 无论行高多少，右侧操作控件 (箭头/开关) 与左侧图标绝对垂直居中；
 * 4. Preview 严格受限于有效宽度的 20% ([SettingsTokens.MaxPreviewWidthRatio])；
 * 5. 单次度量优先保证两端靶心，Center 弹性占用剩余宽度，完全移除 BoxWithConstraints 与 Subcomposition。
 */
@Composable
fun SettingCardItem(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    leadingSlot: SettingLeadingSlot = SettingLeadingSlot.None,
    trailingSlot: SettingTrailingSlot = SettingTrailingSlot.None,
    expandableSlot: SettingExpandableSlot = SettingExpandableSlot.None,
    isExpanded: Boolean = false,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
    isStandaloneCard: Boolean = false,
    shape: Shape = G2CardShape(SettingsTokens.CardCornerRadius),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    highlightPulseKey: Any? = null,
    highlightPulseDurationMillis: Int = 500,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val isExpandable = expandableSlot !is SettingExpandableSlot.None

    // Preview 槽位智能自适应：展开滑块场景默认刚性锁定 20% 宽度防抖；常规静态场景默认弹性自适应
    val effectiveTrailingSlot = remember(trailingSlot, isExpandable) {
        if (trailingSlot is SettingTrailingSlot.Preview && trailingSlot.lockWidth == null) {
            trailingSlot.copy(lockWidth = isExpandable)
        } else {
            trailingSlot
        }
    }

    val trailingAction: (() -> Unit)? = when {
        !enabled -> null
        effectiveTrailingSlot is SettingTrailingSlot.ActionText && effectiveTrailingSlot.onClick != null -> {
            effectiveTrailingSlot.onClick
        }
        effectiveTrailingSlot is SettingTrailingSlot.Toggle && effectiveTrailingSlot.onCheckedChange != null && onClick != null -> {
            val callback = effectiveTrailingSlot.onCheckedChange
            val checked = effectiveTrailingSlot.checked
            { callback(!checked) }
        }
        else -> null
    }

    val effectiveOnClick = when {
        !enabled -> null
        onClick != null -> onClick
        isExpandable && onExpandedChange != null -> {
            { onExpandedChange(!isExpanded) }
        }
        effectiveTrailingSlot is SettingTrailingSlot.Toggle && effectiveTrailingSlot.enabled && trailingAction == null -> {
            effectiveTrailingSlot.onCheckedChange?.let { callback ->
                { callback(!effectiveTrailingSlot.checked) }
            }
        }
        else -> null
    }

    var trailingActionBounds by remember { mutableStateOf<Rect?>(null) }

    // 箭头旋转动画：展开时顺时针旋转 90°
    val animatedChevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = tween(
            durationMillis = SettingsTokens.ArrowAnimationDurationMs,
            easing = FastOutSlowInEasing,
        ),
        label = "settingCardChevronRotation",
    )

    val cardModifier = if (isStandaloneCard) {
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(containerColor, shape)
    } else {
        modifier.fillMaxWidth()
    }

    Column(modifier = cardModifier) {
        // ── 顶部统一头部行 ──
        SettingsItemSurface(
            enabled = enabled,
            contentPadding = SettingsTokens.ContentPadding,
            minHeight = SettingsTokens.ItemMinHeight,
            shape = if (isStandaloneCard) shape else MaterialTheme.shapes.extraSmall,
            highlightPulseKey = highlightPulseKey,
            highlightPulseDurationMillis = highlightPulseDurationMillis,
            onClick = effectiveOnClick,
            onLongClick = onLongClick,
            onTrailingActionClick = trailingAction,
            trailingActionBoundsInWindow = if (trailingAction != null) trailingActionBounds else null,
        ) {
            SettingHeaderRowLayout(
                leadingSlot = leadingSlot,
                title = title,
                summary = summary,
                trailingSlot = effectiveTrailingSlot,
                animatedChevronRotation = animatedChevronRotation,
                enabled = enabled,
                onCardClick = effectiveOnClick,
                onTrailingBoundsChanged = { trailingActionBounds = it },
            )
        }

        // ── 下方展开插槽 (与头部度量完全解耦) ──
        if (isExpandable) {
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(
                    animationSpec = tween(SettingsTokens.ExpandAnimationDurationMs, easing = FastOutSlowInEasing),
                ) + fadeIn(
                    animationSpec = tween(SettingsTokens.ExpandAnimationDurationMs, easing = FastOutSlowInEasing),
                ),
                exit = shrinkVertically(
                    animationSpec = tween(SettingsTokens.ExpandAnimationDurationMs, easing = FastOutSlowInEasing),
                ) + fadeOut(
                    animationSpec = tween(SettingsTokens.ExpandAnimationDurationMs, easing = FastOutSlowInEasing),
                ),
            ) {
                SettingExpandableSlotContent(
                    expandableSlot = expandableSlot,
                    enabled = enabled,
                )
            }
        }
    }
}

/**
 * 头部单次度量自适应 Layout：
 * 消除 BoxWithConstraints，用原生单次 MeasurePass 贯彻优先级分配与绝对居中。
 */
@Composable
internal fun SettingHeaderRowLayout(
    leadingSlot: SettingLeadingSlot,
    title: String,
    summary: String?,
    trailingSlot: SettingTrailingSlot,
    animatedChevronRotation: Float = 0f,
    enabled: Boolean = true,
    titleColor: Color? = null,
    summaryColor: Color? = null,
    onCardClick: (() -> Unit)? = null,
    onTrailingBoundsChanged: ((Rect?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val gutterPx = with(LocalDensity.current) { SettingsTokens.SlotGutter.roundToPx() }
    val density = LocalDensity.current

    var rowBounds by remember { mutableStateOf<Rect?>(null) }
    var previewBounds by remember { mutableStateOf<Rect?>(null) }
    var actionBounds by remember { mutableStateOf<Rect?>(null) }

    LaunchedEffect(rowBounds, previewBounds, actionBounds, trailingSlot) {
        if (onTrailingBoundsChanged == null) return@LaunchedEffect

        val hasTrailingAction = when (trailingSlot) {
            is SettingTrailingSlot.ActionText -> trailingSlot.onClick != null
            is SettingTrailingSlot.Toggle -> trailingSlot.onCheckedChange != null
            else -> false
        }

        if (!hasTrailingAction) {
            onTrailingBoundsChanged(null)
            return@LaunchedEffect
        }

        val p = previewBounds?.takeIf { it.width > 0f && it.height > 0f }
        val a = actionBounds?.takeIf { it.width > 0f && it.height > 0f }

        if (p == null && a == null) {
            onTrailingBoundsChanged(null)
            return@LaunchedEffect
        }

        val row = rowBounds ?: return@LaunchedEffect

        val left = minOf(p?.left ?: Float.MAX_VALUE, a?.left ?: Float.MAX_VALUE)
        val right = maxOf(p?.right ?: Float.MIN_VALUE, a?.right ?: Float.MIN_VALUE)

        val minTargetWidthPx = with(density) { 64.dp.toPx() }
        val rightPaddingPx = with(density) { SettingsTokens.ContentPadding.calculateRightPadding(LayoutDirection.Ltr).toPx() }
        val topPaddingPx = with(density) { SettingsTokens.ContentPadding.calculateTopPadding().toPx() }
        val bottomPaddingPx = with(density) { SettingsTokens.ContentPadding.calculateBottomPadding().toPx() }

        val finalRight = maxOf(right + rightPaddingPx, row.right + rightPaddingPx)
        val finalLeft = minOf(left - with(density) { 8.dp.toPx() }, finalRight - minTargetWidthPx)
        val finalTop = row.top - topPaddingPx
        val finalBottom = row.bottom + bottomPaddingPx

        onTrailingBoundsChanged(Rect(finalLeft, finalTop, finalRight, finalBottom))
    }

    Layout(
        content = {
            // 1. Leading Slot
            Box(modifier = Modifier.layoutId(SettingHeaderSlotId.Leading)) {
                SettingLeadingSlotContent(leadingSlot = leadingSlot, enabled = enabled)
            }

            // 2. Center Content Slot
            Box(modifier = Modifier.layoutId(SettingHeaderSlotId.Center)) {
                SettingCenterContent(
                    title = title,
                    summary = summary,
                    enabled = enabled,
                    titleColor = titleColor,
                    summaryColor = summaryColor,
                )
            }

            // 3. Trailing Preview Slot (限额 20%，End-to-End 右对齐向左生长)
            Box(
                modifier = Modifier
                    .layoutId(SettingHeaderSlotId.Preview)
                    .onGloballyPositioned { coords ->
                        previewBounds = coords.boundsInWindow()
                    }
            ) {
                SettingTrailingPreviewContent(
                    trailingSlot = trailingSlot,
                    enabled = enabled,
                    summaryColor = summaryColor,
                )
            }

            // 4. Trailing Action Slot (箭头或开关，固定尺寸，刚性优先)
            Box(
                modifier = Modifier
                    .layoutId(SettingHeaderSlotId.Action)
                    .onGloballyPositioned { coords ->
                        actionBounds = coords.boundsInWindow()
                    }
            ) {
                SettingTrailingActionContent(
                    trailingSlot = trailingSlot,
                    animatedChevronRotation = animatedChevronRotation,
                    enabled = enabled,
                    actionColor = summaryColor,
                    onCardClick = onCardClick,
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                rowBounds = coords.boundsInWindow()
            },
    ) { measurables, constraints ->
        val effectiveWidth = constraints.maxWidth

        // ── 步骤 1：度量 Trailing Action (箭头 / 开关，刚性优先) ──
        val actionMeasurable = measurables.first { it.layoutId == SettingHeaderSlotId.Action }
        val actionPlaceable = actionMeasurable.measure(
            constraints.copy(minWidth = 0, minHeight = 0)
        )
        val actionWidth = actionPlaceable.width

        // ── 步骤 2：度量 Trailing Preview (ActionText 保证至少 minTargetWidthPx，常规 Preview clamp 在有效宽度的 20% 以内) ──
        val previewMeasurable = measurables.first { it.layoutId == SettingHeaderSlotId.Preview }
        val maxPreviewWidthRatio = (effectiveWidth * SettingsTokens.MaxPreviewWidthRatio).roundToInt()
        val minTargetWidthPx = with(this) { 64.dp.roundToPx() }
        val maxPreviewWidthPx = when (trailingSlot) {
            is SettingTrailingSlot.ActionText -> maxOf(maxPreviewWidthRatio, minTargetWidthPx)
            else -> maxPreviewWidthRatio
        }
        val shouldLockWidth = when (trailingSlot) {
            is SettingTrailingSlot.Preview -> trailingSlot.lockWidth == true
            else -> false
        }
        val previewPlaceable = previewMeasurable.measure(
            Constraints(
                minWidth = if (shouldLockWidth) maxPreviewWidthPx else 0,
                maxWidth = maxPreviewWidthPx,
                minHeight = 0,
                maxHeight = constraints.maxHeight,
            )
        )
        val previewWidth = previewPlaceable.width

        // ── 步骤 3：度量 Leading Slot (刚性优先) ──
        val leadingMeasurable = measurables.first { it.layoutId == SettingHeaderSlotId.Leading }
        val leadingPlaceable = leadingMeasurable.measure(
            constraints.copy(minWidth = 0, minHeight = 0)
        )
        val leadingWidth = leadingPlaceable.width

        // ── 步骤 4：计算剩余宽度，赋予 Center Slot 弹性度量 ──
        val leadingGutter = if (leadingWidth > 0) gutterPx else 0
        val trailingGap = if (previewWidth > 0 && actionWidth > 0) with(this) { 8.dp.roundToPx() } else 0
        val trailingTotalWidth = previewWidth + trailingGap + actionWidth
        val trailingGutter = if (trailingTotalWidth > 0) gutterPx else 0

        val centerRemainingWidth = (effectiveWidth - leadingWidth - leadingGutter - trailingTotalWidth - trailingGutter)
            .coerceAtLeast(0)

        val centerMeasurable = measurables.first { it.layoutId == SettingHeaderSlotId.Center }
        val centerPlaceable = centerMeasurable.measure(
            Constraints(
                minWidth = 0,
                maxWidth = centerRemainingWidth,
                minHeight = 0,
                maxHeight = constraints.maxHeight,
            )
        )

        // ── 步骤 5：整行总高度由各部分真实内容最大高度确定 (纯 wrap_content 自适应) ──
        val rowHeight = maxOf(
            centerPlaceable.height,
            leadingPlaceable.height,
            previewPlaceable.height,
            actionPlaceable.height,
        )

        // ── 步骤 6：布局排版 (两端绝对垂直居中) ──
        layout(effectiveWidth, rowHeight) {
            var currentX = 0

            // 放置 Leading (绝对居中)
            if (leadingWidth > 0) {
                val leadingY = (rowHeight - leadingPlaceable.height) / 2
                leadingPlaceable.placeRelative(currentX, leadingY)
                currentX += leadingWidth + leadingGutter
            }

            // 放置 Center (绝对居中)
            val centerY = (rowHeight - centerPlaceable.height) / 2
            centerPlaceable.placeRelative(currentX, centerY)

            // 放置 Trailing Group (绝对居中)
            if (actionWidth > 0) {
                val actionX = effectiveWidth - actionWidth
                val actionY = (rowHeight - actionPlaceable.height) / 2
                actionPlaceable.placeRelative(actionX, actionY)
            }

            if (previewWidth > 0) {
                val previewX = effectiveWidth - actionWidth - (if (actionWidth > 0) trailingGap else 0) - previewWidth
                val previewY = (rowHeight - previewPlaceable.height) / 2
                previewPlaceable.placeRelative(previewX, previewY)
            }
        }
    }
}

/** 左侧插槽内容渲染 */
@Composable
private fun SettingLeadingSlotContent(
    leadingSlot: SettingLeadingSlot,
    enabled: Boolean,
) {
    val defaultTint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

    when (leadingSlot) {
        is SettingLeadingSlot.None -> Unit

        is SettingLeadingSlot.PlainIcon -> {
            Icon(
                imageVector = leadingSlot.imageVector,
                contentDescription = leadingSlot.contentDescription,
                tint = leadingSlot.tint ?: defaultTint,
                modifier = Modifier.size(SettingsTokens.PlainIconSize),
            )
        }

        is SettingLeadingSlot.PlainPainter -> {
            Icon(
                painter = leadingSlot.painter,
                contentDescription = leadingSlot.contentDescription,
                tint = leadingSlot.tint ?: defaultTint,
                modifier = Modifier.size(SettingsTokens.PlainIconSize),
            )
        }

        is SettingLeadingSlot.CircularIcon -> {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(SettingsTokens.CircularIconContainerSize)
                    .clip(CircleShape)
                    .background(leadingSlot.containerColor ?: MaterialTheme.colorScheme.surfaceContainerHighest),
            ) {
                if (leadingSlot.painter != null) {
                    Icon(
                        painter = leadingSlot.painter,
                        contentDescription = leadingSlot.contentDescription,
                        tint = leadingSlot.tint ?: defaultTint,
                        modifier = Modifier.size(SettingsTokens.CircularIconInnerSize),
                    )
                } else if (leadingSlot.imageVector != null) {
                    Icon(
                        imageVector = leadingSlot.imageVector,
                        contentDescription = leadingSlot.contentDescription,
                        tint = leadingSlot.tint ?: defaultTint,
                        modifier = Modifier.size(SettingsTokens.CircularIconInnerSize),
                    )
                }
            }
        }

        is SettingLeadingSlot.Selector -> {
            val haptics = LocalHapticFeedback.current
            Icon(
                imageVector = if (leadingSlot.selected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (leadingSlot.selected) MaterialTheme.colorScheme.primary else defaultTint.copy(alpha = 0.45f),
                modifier = Modifier
                    .size(SettingsTokens.SelectorSize)
                    .then(
                        if (leadingSlot.onSelect != null) {
                            Modifier.clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                                leadingSlot.onSelect.invoke()
                            }
                        } else Modifier
                    ),
            )
        }

        is SettingLeadingSlot.Custom -> {
            leadingSlot.content()
        }
    }
}

/** 中间文本内容渲染 */
@Composable
private fun SettingCenterContent(
    title: String,
    summary: String?,
    enabled: Boolean,
    titleColor: Color? = null,
    summaryColor: Color? = null,
) {
    val finalTitleColor = titleColor ?: if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val finalSummaryColor = summaryColor ?: if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

    Column(verticalArrangement = Arrangement.spacedBy(SettingsTokens.TextSpacing)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = SettingsTokens.TitleFontSize),
            color = finalTitleColor,
            maxLines = SettingsTokens.MaxTitleLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (!summary.isNullOrBlank()) {
            Text(
                text = summary,
                modifier = Modifier.padding(start = SettingsTokens.SummaryStartIndent),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = SettingsTokens.SummaryFontSize,
                    lineHeight = SettingsTokens.SummaryLineHeight,
                ),
                color = finalSummaryColor,
                maxLines = SettingsTokens.MaxSummaryLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 右侧 Preview 状态渲染 (受 20% 约束，End-to-End 右对齐向左生长) */
@Composable
private fun SettingTrailingPreviewContent(
    trailingSlot: SettingTrailingSlot,
    enabled: Boolean,
    summaryColor: Color? = null,
) {
    val defaultTextColor = summaryColor ?: if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

    when (trailingSlot) {
        is SettingTrailingSlot.Preview -> {
            Text(
                text = trailingSlot.text,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = SettingsTokens.PreviewFontSize),
                color = trailingSlot.textColor ?: defaultTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = if (trailingSlot.lockWidth == true) Modifier.fillMaxWidth() else Modifier,
            )
        }

        is SettingTrailingSlot.ActionText -> {
            Text(
                text = trailingSlot.text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = SettingsTokens.PreviewFontSize,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = summaryColor ?: MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
        }

        else -> Unit
    }
}

/** 右侧 Action 操作靶心渲染 (箭头、开关) */
@Composable
private fun SettingTrailingActionContent(
    trailingSlot: SettingTrailingSlot,
    animatedChevronRotation: Float,
    enabled: Boolean,
    actionColor: Color? = null,
    onCardClick: (() -> Unit)? = null,
) {
    val defaultIconTint = actionColor ?: if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

    when (trailingSlot) {
        is SettingTrailingSlot.None -> Unit

        is SettingTrailingSlot.Chevron -> {
            val rotation = if (trailingSlot.rotationZ != 0f) trailingSlot.rotationZ else animatedChevronRotation
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = trailingSlot.tint ?: defaultIconTint,
                modifier = Modifier
                    .size(SettingsTokens.ChevronSize)
                    .graphicsLayer { rotationZ = rotation },
            )
        }

        is SettingTrailingSlot.Preview -> {
            if (trailingSlot.showChevron) {
                val rotation = if (trailingSlot.chevronRotationZ != 0f) trailingSlot.chevronRotationZ else animatedChevronRotation
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = defaultIconTint,
                    modifier = Modifier
                        .size(SettingsTokens.ChevronSize)
                        .graphicsLayer { rotationZ = rotation },
                )
            }
        }

        is SettingTrailingSlot.Toggle -> {
            ZafiroSwitch(
                checked = trailingSlot.checked,
                enabled = enabled && trailingSlot.enabled,
                onCheckedChange = trailingSlot.onCheckedChange ?: { onCardClick?.invoke(); Unit },
            )
        }

        is SettingTrailingSlot.ActionText -> {
            if (trailingSlot.showChevron) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = defaultIconTint,
                    modifier = Modifier.size(SettingsTokens.ChevronSize),
                )
            }
        }

        is SettingTrailingSlot.Custom -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                content = trailingSlot.content,
            )
        }
    }
}

/** 展开插槽内容渲染 (Slider 等) */
@Composable
private fun SettingExpandableSlotContent(
    expandableSlot: SettingExpandableSlot,
    enabled: Boolean,
) {
    when (expandableSlot) {
        is SettingExpandableSlot.None -> Unit

        is SettingExpandableSlot.Slider -> {
            val themeStyle = LocalThemeStyle.current
            val isLiquid = themeStyle == ThemeStyle.LiquidGlass

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = SettingsTokens.ContentPaddingStart,
                        vertical = if (isLiquid && expandableSlot.steps > 0) 12.dp else 8.dp,
                    ),
                verticalAlignment = if (isLiquid && expandableSlot.steps > 0) Alignment.Top else Alignment.CenterVertically,
            ) {
                // 左侧极值文字
                if (!expandableSlot.startLabel.isNullOrBlank()) {
                    Box(
                        modifier = if (isLiquid && expandableSlot.steps > 0) Modifier.height(44.dp) else Modifier,
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = expandableSlot.startLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                        )
                    }
                }

                // 滑块轨道
                ZafiroSlider(
                    value = expandableSlot.value,
                    onValueChange = expandableSlot.onValueChange,
                    onValueCommit = expandableSlot.onValueCommit,
                    onValueChangeFinished = expandableSlot.onValueChangeFinished,
                    valueRange = expandableSlot.valueRange,
                    steps = expandableSlot.steps,
                    enabled = enabled && expandableSlot.enabled,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                )

                // 右侧极值文字
                if (!expandableSlot.endLabel.isNullOrBlank()) {
                    Box(
                        modifier = if (isLiquid && expandableSlot.steps > 0) Modifier.height(44.dp) else Modifier,
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text(
                            text = expandableSlot.endLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            softWrap = false,
                        )
                    }
                }
            }
        }

        is SettingExpandableSlot.Custom -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsTokens.ContentPaddingStart, vertical = 12.dp),
                content = expandableSlot.content,
            )
        }
    }
}
