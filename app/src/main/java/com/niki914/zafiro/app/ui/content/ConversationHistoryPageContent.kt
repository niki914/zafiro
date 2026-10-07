package com.niki914.zafiro.app.ui.content

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.LiquidDialog
import com.niki914.uikit.infra.component.LiquidTextField
import com.niki914.uikit.infra.component.MaterialTintLiquidButton
import com.niki914.uikit.infra.component.OptionRow
import com.niki914.uikit.infra.component.OptionSheet
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemSurface
import com.niki914.uikit.infra.liquidScreenBottomPadding
import com.niki914.uikit.infra.liquidScreenTopPadding
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.conversation.ConversationFormatter
import com.niki914.zafiro.app.conversation.ConversationOriginKind
import com.niki914.zafiro.app.conversation.ConversationSummary
import com.niki914.zafiro.app.ui.model.conversation.ConversationHistoryUiState
import com.niki914.zafiro.repo.PinnedConversation
import kotlinx.coroutines.delay
import java.util.Calendar

@Composable
internal fun ConversationHistoryPageContent(
    uiState: ConversationHistoryUiState,
    onConversationClick: (String) -> Unit,
    onConversationDelete: (String) -> Unit,
    onConversationToggleSelection: (String) -> Unit,
    onConversationRename: ((String, String) -> Unit)? = null,
    onConversationFork: ((String) -> Unit)? = null,
    onConversationPin: ((String, Boolean) -> Unit)? = null,
    onConfirmBatchDelete: (() -> Unit)? = null,
    onDismissBatchDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var sheetConversation by remember { mutableStateOf<ConversationSummary?>(null) }
    var renamingConversation by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleteConfirmation by remember { mutableStateOf<ConversationSummary?>(null) }
    val pinnedIdSet = remember(uiState.pinnedConversations) {
        uiState.pinnedConversations.map { it.id }.toSet()
    }

    when {
        uiState.isLoading -> ConversationHistoryMessageContent(
            title = stringResource(R.string.ui_conversation_history_loading),
            modifier = modifier,
        )

        uiState.errorMessage != null -> ConversationHistoryMessageContent(
            title = stringResource(R.string.ui_conversation_history_error_title),
            body = uiState.errorMessage,
            modifier = modifier,
        )

        uiState.conversations.isEmpty() -> ConversationHistoryMessageContent(
            title = stringResource(R.string.ui_conversation_history_empty_title),
            body = stringResource(R.string.ui_conversation_history_empty_body),
            modifier = modifier,
        )

        else -> ConversationHistoryListContent(
            conversations = uiState.conversations,
            pinnedConversations = uiState.pinnedConversations,
            activeConversationId = uiState.activeConversationId,
            deleteErrorMessage = uiState.deleteErrorMessage,
            selecting = uiState.selecting,
            selectedIds = uiState.selectedIds,
            onConversationClick = onConversationClick,
            onConversationToggleSelection = onConversationToggleSelection,
            onConversationLongClick = { conversation ->
                sheetConversation = conversation
            },
            modifier = modifier,
        )
    }

    // 底部选项单 (OptionSheet)：载入、分叉、重命名、删除
    OptionSheet(
        visible = sheetConversation != null && renamingConversation == null && deleteConfirmation == null,
        onDismissRequest = { sheetConversation = null },
        title = sheetConversation?.let {
            ConversationFormatter.sanitizeDisplayTitle(it.title).ifBlank {
                stringResource(R.string.ui_conversation_history_untitled)
            }
        },
    ) { dismissThen ->
        OptionRow(
            title = stringResource(R.string.ui_conversation_action_load),
            leadingContent = {
                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
            },
            onClick = {
                val target = sheetConversation
                dismissThen {
                    sheetConversation = null
                    target?.let { onConversationClick(it.id) }
                }
            },
        )
        if (onConversationFork != null) {
            OptionRow(
                title = stringResource(R.string.ui_conversation_action_fork),
                leadingContent = {
                    Icon(Icons.AutoMirrored.Filled.CallSplit, contentDescription = null)
                },
                onClick = {
                    val target = sheetConversation
                    dismissThen {
                        sheetConversation = null
                        target?.let { onConversationFork(it.id) }
                    }
                },
            )
        }
        if (onConversationPin != null) {
            val sheetId = sheetConversation?.id
            val isPinned = sheetId != null && sheetId in pinnedIdSet
            OptionRow(
                title = stringResource(
                    if (isPinned) {
                        R.string.ui_conversation_action_unpin
                    } else {
                        R.string.ui_conversation_action_pin
                    },
                ),
                leadingContent = {
                    Icon(Icons.Default.PushPin, contentDescription = null)
                },
                onClick = {
                    val target = sheetConversation
                    dismissThen {
                        sheetConversation = null
                        target?.let { onConversationPin(it.id, !isPinned) }
                    }
                },
            )
        }
        OptionRow(
            title = stringResource(R.string.ui_conversation_action_rename),
            leadingContent = {
                Icon(Icons.Default.Edit, contentDescription = null)
            },
            onClick = {
                val target = sheetConversation
                dismissThen {
                    sheetConversation = target
                    renamingConversation = target
                }
            },
        )
        OptionRow(
            title = stringResource(R.string.ui_conversation_action_delete),
            leadingContent = {
                Icon(Icons.Default.Delete, contentDescription = null)
            },
            onClick = {
                val target = sheetConversation
                dismissThen {
                    sheetConversation = target
                    deleteConfirmation = target
                }
            },
        )
    }

    // 重命名对话框 (LiquidDialog)
    ConversationRenameDialog(
        conversation = renamingConversation,
        onDismissRequest = {
            renamingConversation = null
        },
        onConfirmClick = { conversation, newTitle ->
            renamingConversation = null
            sheetConversation = null
            onConversationRename?.invoke(conversation.id, newTitle)
        },
    )

    // 删除确认对话框 (ConfirmationLiquidDialog)
    ConversationDeleteConfirmationDialog(
        conversation = deleteConfirmation,
        onDismissRequest = {
            deleteConfirmation = null
        },
        onConfirmClick = { conversation ->
            deleteConfirmation = null
            sheetConversation = null
            onConversationDelete(conversation.id)
        },
    )

    // 批量删除确认对话框 (ConfirmationLiquidDialog)
    ConversationBatchDeleteConfirmationDialog(
        visible = uiState.showBatchDeleteConfirmation,
        count = uiState.selectedIds.size,
        onDismissRequest = { onDismissBatchDelete?.invoke() },
        onConfirmClick = { onConfirmBatchDelete?.invoke() },
    )
}

@Composable
private fun ConversationHistoryListContent(
    conversations: List<ConversationSummary>,
    pinnedConversations: List<PinnedConversation>,
    activeConversationId: String?,
    deleteErrorMessage: String?,
    selecting: Boolean,
    selectedIds: Set<String>,
    onConversationClick: (String) -> Unit,
    onConversationToggleSelection: (String) -> Unit,
    onConversationLongClick: (ConversationSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val deleteErrorPrefix = deleteErrorMessage?.let {
        stringResource(R.string.ui_conversation_history_delete_error, it)
    }
    val sections = remember(conversations, pinnedConversations) {
        groupByTimeline(conversations, pinnedConversations)
    }
    val pinnedIds = remember(pinnedConversations) {
        pinnedConversations.map { it.id }.toSet()
    }
    var collapsedBuckets by rememberSaveable { mutableStateOf(emptySet<TimelineBucket>()) }

    // 时间基准定时刷新：每 15 秒更新一次当前时间戳，驱动相对时间自然步进
    var currentTimeMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000L)
            currentTimeMillis = System.currentTimeMillis()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = liquidScreenTopPadding(24.dp),
            end = 16.dp,
            bottom = liquidScreenBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        sections.forEach { section ->
            val expanded = section.bucket !in collapsedBuckets
            // 一个 section = 一个 item：header 与它的行同住一个 item，折叠时靠 item
            // 自身的高度动画把下方 section 平滑拉上来（布局推挤，不是各行各自补间，故不会互相穿透）。
            item(key = "section_${section.bucket}", contentType = "timeline_section") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TimelineSectionHeader(
                        title = stringResource(section.bucket.labelRes()),
                        leadingIcon = if (section.bucket == TimelineBucket.Pinned) {
                            Icons.Default.PushPin
                        } else {
                            null
                        },
                        isExpanded = expanded,
                        onToggle = {
                            collapsedBuckets = if (section.bucket in collapsedBuckets) {
                                collapsedBuckets - section.bucket
                            } else {
                                collapsedBuckets + section.bucket
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            section.conversations.forEach { conversation ->
                                ConversationHistoryItem(
                                    conversation = conversation,
                                    isPinned = conversation.id in pinnedIds,
                                    inPinnedSection = section.bucket == TimelineBucket.Pinned,
                                    activeConversationId = activeConversationId,
                                    currentTimeMillis = currentTimeMillis,
                                    selected = conversation.id in selectedIds,
                                    onClick = {
                                        if (selecting) {
                                            onConversationToggleSelection(conversation.id)
                                        } else {
                                            onConversationClick(conversation.id)
                                        }
                                    },
                                    onLongClick = {
                                        if (selecting) {
                                            onConversationToggleSelection(conversation.id)
                                        } else {
                                            onConversationLongClick(conversation)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (deleteErrorPrefix != null) {
            item {
                ConversationHistoryInlineErrorText(error = deleteErrorPrefix)
            }
        }
    }
}

/**
 * 紧凑型会话行：
 * - 常态无卡片容器底色，文字直接呈现在背景上；
 * - 从主页面进入对话列表时，同一个会话短暂高亮（highlightPulseKey 触发 500ms 脉冲渐变）；
 * - 按下/长按时采用设置页同款 SettingsItemSurface 平滑渐变卡片圆角背景（G2CardShape(20.dp)）；
 * - 移除右侧 chevron，标题单行加宽；
 * - 标题右侧展示派生图标（Regenerate / Fork / Rewind）+ 相对更新时间，图标与时间浑然一体（同色、居右）；
 * - 置顶段里的行：独占容器底色 + 右上角只留时间（隐去图钉与派生前缀图标）；
 *   同一会话在时间桶里仍走普通样式，另用一个图钉标识它是置顶项。
 * - 预览单行截断，填满可用宽度，字体适度调小；
 * - 自动剥除重复的派生前缀。
 */
@Composable
private fun ConversationHistoryItem(
    conversation: ConversationSummary,
    isPinned: Boolean,
    inPinnedSection: Boolean,
    activeConversationId: String?,
    currentTimeMillis: Long,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val untitledConversation = stringResource(R.string.ui_conversation_history_untitled)
    val parsedTitle = remember(conversation.title) {
        ConversationFormatter.parseDisplayTitle(conversation.title)
    }
    val displayTitle = parsedTitle.cleanTitle.ifBlank { untitledConversation }
    // 置顶段独占样式：高亮底色 + 隐去派生前缀图标，右上角只留时间；
    // 时间桶里的同一会话照旧普通样式，另用图钉标识它是置顶项。
    val originIcon = if (inPinnedSection) null else parsedTitle.originKind?.let { originKindToIcon(it) }
    val showPinBadge = isPinned && !inPinnedSection
    val relativeTime = formatRelativeTime(conversation.updatedAt, currentTimeMillis)

    val cardShape = remember { G2CardShape(20.dp) }
    // 选中态 = 描边 + 0.98 微缩，与 bukit 的 item 多选同构；置顶段的底色不动，靠描边区分选中。
    val selectionProgress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 250),
        label = "conversationItemSelection",
    )
    val selectionScale by animateFloatAsState(
        targetValue = if (selected) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "conversationItemSelectionScale",
        visibilityThreshold = 0.0001f,
    )
    val pinnedBackground = if (inPinnedSection) {
        Modifier.background(MaterialTheme.colorScheme.secondaryContainer, cardShape)
    } else {
        Modifier
    }
    val selectionBorder = if (selectionProgress > 0f) {
        Modifier.border(
            width = 2.dp * selectionProgress,
            color = MaterialTheme.colorScheme.primary,
            shape = cardShape,
        )
    } else {
        Modifier
    }

    // 描边与底色都画在行外层：SettingsItemSurface 内部自带按压底色，边框放在它外层才不会被盖。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = selectionScale
                scaleY = selectionScale
            }
            .clip(cardShape)
            .then(pinnedBackground)
            .then(selectionBorder),
    ) {
        SettingsItemSurface(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = cardShape,
            highlightPulseKey = activeConversationId?.takeIf { it == conversation.id },
            highlightPulseDurationMillis = 500,
            minHeight = 0.dp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val timeColor = MaterialTheme.colorScheme.tertiary
                    if (originIcon != null || showPinBadge || relativeTime.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            if (originIcon != null) {
                                Icon(
                                    imageVector = originIcon,
                                    contentDescription = null,
                                    tint = timeColor,
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .size(13.dp),
                                )
                            }
                            // 时间桶里的置顶标识：与派生前缀图标、时间同色同大小
                            if (showPinBadge) {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = null,
                                    tint = timeColor,
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .size(13.dp),
                                )
                            }
                            if (relativeTime.isNotBlank()) {
                                Text(
                                    text = relativeTime,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = timeColor,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                if (conversation.lastMessagePreview.isNotBlank()) {
                    Text(
                        text = conversation.lastMessagePreview,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun originKindToIcon(kind: ConversationOriginKind): ImageVector = when (kind) {
    ConversationOriginKind.Regenerate -> Icons.Default.Refresh
    ConversationOriginKind.Fork -> Icons.AutoMirrored.Filled.CallSplit
    ConversationOriginKind.Rewind -> Icons.AutoMirrored.Filled.Undo
}

/** 相对时间格式化（刚刚 / X分钟前 / X小时前 / 昨天 / 日期）。 */
@Composable
private fun formatRelativeTime(
    updatedAt: Long,
    nowMillis: Long,
): String {
    if (updatedAt <= 0L) return ""
    val diff = (nowMillis - updatedAt).coerceAtLeast(0L)
    val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val time = Calendar.getInstance().apply { timeInMillis = updatedAt }

    return when {
        diff < 60_000L -> stringResource(R.string.ui_time_just_now)
        diff < 3_600_000L -> stringResource(R.string.ui_time_minutes_ago, (diff / 60_000L).coerceAtLeast(1))
        sameDay(time, now) -> stringResource(R.string.ui_time_hours_ago, (diff / 3_600_000L).coerceAtLeast(1))
        isYesterday(time, now) -> stringResource(R.string.ui_time_yesterday)
        time.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> {
            DateUtils.formatDateTime(
                LocalContext.current,
                updatedAt,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR,
            )
        }
        else -> {
            DateUtils.formatDateTime(
                LocalContext.current,
                updatedAt,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR,
            )
        }
    }
}

private fun isYesterday(time: Calendar, now: Calendar): Boolean {
    val yesterday = (now.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, -1)
    }
    return sameDay(time, yesterday)
}

/** 时间线 section header（轻量文本行）：labelLarge 淡色标题 + 右 chevron，整行可点 toggle。 */
@Composable
private fun TimelineSectionHeader(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "timelineChevron",
    )
    val titleColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .clip(G2CardShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = titleColor,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(16.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = titleColor,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = titleColor.copy(alpha = 0.6f),
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = chevronRotation },
        )
    }
}

// ── 时间线分桶 ────────────────────────────────────────────────────────────

enum class TimelineBucket { Pinned, Today, ThisWeek, ThisMonth, Older }

private fun TimelineBucket.labelRes(): Int = when (this) {
    TimelineBucket.Pinned -> R.string.ui_conversation_history_pinned_section
    TimelineBucket.Today -> R.string.ui_conversation_history_today
    TimelineBucket.ThisWeek -> R.string.ui_conversation_history_this_week
    TimelineBucket.ThisMonth -> R.string.ui_conversation_history_this_month
    TimelineBucket.Older -> R.string.ui_conversation_history_older
}

private data class TimelineSection(
    val bucket: TimelineBucket,
    val conversations: List<ConversationSummary>,
)

private fun groupByTimeline(
    conversations: List<ConversationSummary>,
    pinnedConversations: List<PinnedConversation>,
): List<TimelineSection> {
    if (conversations.isEmpty()) return emptyList()
    val now = Calendar.getInstance()
    // 置顶段：按 max(置顶时刻, 最后交互时刻) 倒序 —— 越晚置顶、或置顶后又有新消息的越靠前。
    val pinnedAtById = pinnedConversations.associate { it.id to it.pinnedAt }
    val pinned = conversations
        .mapNotNull { conversation ->
            pinnedAtById[conversation.id]?.let { pinnedAt ->
                conversation to maxOf(pinnedAt, conversation.updatedAt)
            }
        }
        .sortedByDescending { it.second }
        .map { it.first }
    val timeSections = conversations
        .groupBy { bucketOf(it.updatedAt, now) }
        .let { byBucket ->
            TimelineBucket.entries.mapNotNull { bucket ->
                byBucket[bucket]?.let { TimelineSection(bucket, it) }
            }
        }
    return buildList {
        add(TimelineSection(TimelineBucket.Pinned, pinned))
        addAll(timeSections)
    }
}

private fun bucketOf(updatedAt: Long, now: Calendar): TimelineBucket {
    if (updatedAt <= 0L) return TimelineBucket.Older
    val time = Calendar.getInstance().apply { timeInMillis = updatedAt }
    return when {
        sameDay(time, now) -> TimelineBucket.Today
        withinThisWeek(time, now) -> TimelineBucket.ThisWeek
        sameMonth(time, now) -> TimelineBucket.ThisMonth
        else -> TimelineBucket.Older
    }
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

private fun sameMonth(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.MONTH) == b.get(Calendar.MONTH)

private fun withinThisWeek(time: Calendar, now: Calendar): Boolean {
    val weekStart = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -((get(Calendar.DAY_OF_WEEK) + 5) % 7))
    }
    return time.timeInMillis >= weekStart.timeInMillis
}

@Composable
private fun ConversationHistoryMessageContent(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = liquidScreenTopPadding(24.dp), bottom = liquidScreenBottomPadding()),
        contentAlignment = Alignment.TopCenter,
    ) {
        SettingsGroupCard {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                if (!body.isNullOrBlank()) {
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationRenameDialog(
    conversation: ConversationSummary?,
    onDismissRequest: () -> Unit,
    onConfirmClick: (ConversationSummary, String) -> Unit,
) {
    var retainedConversation by remember { mutableStateOf<ConversationSummary?>(null) }
    var titleInput by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(conversation) {
        if (conversation != null) {
            retainedConversation = conversation
            titleInput = ConversationFormatter.sanitizeDisplayTitle(conversation.title)
            delay(100)
            focusRequester.requestFocus()
        }
    }

    val activeConversation = conversation ?: retainedConversation

    LiquidDialog(
        visible = conversation != null,
        onDismissRequest = onDismissRequest,
        title = {
            Text(
                text = stringResource(R.string.ui_conversation_rename_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Start,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        content = {
            Box(modifier = Modifier.padding(horizontal = 2.dp)) {
                Text(
                    text = stringResource(R.string.ui_conversation_rename_dialog_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LiquidTextField(
                value = titleInput,
                onValueChange = { titleInput = it },
                placeholder = stringResource(R.string.ui_conversation_rename_dialog_hint),
                singleLine = true,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        actions = {
            MaterialTintLiquidButton(
                text = stringResource(R.string.ui_conversation_rename_dialog_cancel),
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            MaterialTintLiquidButton(
                text = stringResource(R.string.ui_conversation_rename_dialog_confirm),
                enabled = titleInput.isNotBlank(),
                onClick = {
                    activeConversation?.let { onConfirmClick(it, titleInput.trim()) }
                },
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    )
}

@Composable
private fun ConversationDeleteConfirmationDialog(
    conversation: ConversationSummary?,
    onDismissRequest: () -> Unit,
    onConfirmClick: (ConversationSummary) -> Unit,
) {
    var retainedConversation by remember { mutableStateOf<ConversationSummary?>(null) }
    LaunchedEffect(conversation) {
        if (conversation != null) {
            retainedConversation = conversation
        }
    }
    val activeConversation = conversation ?: retainedConversation
    val untitledConversation = stringResource(R.string.ui_conversation_history_untitled)
    val title = activeConversation?.title?.let { ConversationFormatter.sanitizeDisplayTitle(it) }
        ?.ifBlank { untitledConversation }
        .orEmpty()
    ConfirmationLiquidDialog(
        visible = conversation != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.ui_conversation_history_delete_dialog_title),
        text = stringResource(R.string.ui_conversation_history_delete_dialog_text, title),
        negativeButtonText = stringResource(R.string.ui_conversation_history_delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.ui_conversation_history_delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = {
            activeConversation?.let(onConfirmClick)
        },
    )
}

@Composable
private fun ConversationBatchDeleteConfirmationDialog(
    visible: Boolean,
    count: Int,
    onDismissRequest: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    var retainedCount by remember { mutableStateOf(count) }
    LaunchedEffect(count) {
        if (count > 0) {
            retainedCount = count
        }
    }
    ConfirmationLiquidDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.ui_conversation_history_delete_dialog_title),
        text = stringResource(
            R.string.ui_conversation_history_batch_delete_dialog_text,
            retainedCount,
        ),
        negativeButtonText = stringResource(R.string.ui_conversation_history_delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.ui_conversation_history_delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = onConfirmClick,
    )
}

@Composable
private fun ConversationHistoryInlineErrorText(
    error: String,
) {
    Text(
        text = error,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Start,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    )
}
