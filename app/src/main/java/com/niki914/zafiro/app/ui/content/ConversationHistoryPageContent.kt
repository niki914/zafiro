package com.niki914.zafiro.app.ui.content

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SwipeDismissSettingsItemCard
import com.niki914.uikit.infra.liquidScreenTopPadding
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.conversation.ConversationSummary
import java.util.Calendar

internal data class ConversationHistoryUiState(
    val isLoading: Boolean = false,
    val conversations: List<ConversationSummary> = emptyList(),
    val errorMessage: String? = null,
    val deleteErrorMessage: String? = null,
)

@Composable
internal fun ConversationHistoryPageContent(
    uiState: ConversationHistoryUiState,
    activeConversationId: String?,
    onConversationClick: (String) -> Unit,
    onConversationDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteConfirmation by remember { mutableStateOf<ConversationSummary?>(null) }
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
            activeConversationId = activeConversationId,
            deleteErrorMessage = uiState.deleteErrorMessage,
            onConversationClick = onConversationClick,
            onConversationDeleteRequest = { conversation ->
                deleteConfirmation = conversation
            },
            modifier = modifier,
        )
    }

    ConversationDeleteConfirmationDialog(
        conversation = deleteConfirmation,
        onDismissRequest = {
            deleteConfirmation = null
        },
        onConfirmClick = { conversation ->
            deleteConfirmation = null
            onConversationDelete(conversation.id)
        },
    )
}

@Composable
private fun ConversationHistoryListContent(
    conversations: List<ConversationSummary>,
    activeConversationId: String?,
    deleteErrorMessage: String?,
    onConversationClick: (String) -> Unit,
    onConversationDeleteRequest: (ConversationSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val untitledConversation = stringResource(R.string.ui_conversation_history_untitled)
    val deleteErrorPrefix = deleteErrorMessage?.let {
        stringResource(R.string.ui_conversation_history_delete_error, it)
    }
    val sections = remember(conversations) { groupByTimeline(conversations) }
    var collapsedBuckets by rememberSaveable { mutableStateOf(emptySet<TimelineBucket>()) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = liquidScreenTopPadding(24.dp),
            end = 16.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        sections.forEach { section ->
            val expanded = section.bucket !in collapsedBuckets
            item(key = "header_${section.bucket}", contentType = "timeline_header") {
                TimelineSectionHeader(
                    title = stringResource(section.bucket.labelRes()),
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
            }
            if (expanded) {
                section.conversations.forEach { conversation ->
                    val title = conversation.title.ifBlank { untitledConversation }
                    item(key = conversation.id, contentType = "conversation") {
                        SwipeDismissSettingsItemCard(
                            title = title,
                            summary = conversation.lastMessagePreview.takeIf { it.isNotBlank() },
                            showChevron = true,
                            highlightPulseKey = activeConversationId?.takeIf { it == conversation.id },
                            highlightPulseDurationMillis = 500,
                            onClick = {
                                onConversationClick(conversation.id)
                            },
                            onDismissRequest = {
                                onConversationDeleteRequest(conversation)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
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

/** 时间线 section header（方案 B，轻量文本行）：labelLarge 淡色标题 + 右 chevron，整行可点 toggle。 */
@Composable
private fun TimelineSectionHeader(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium),
        label = "timelineChevron",
    )
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
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = chevronRotation },
        )
    }
}

// ── 时间线分桶 ────────────────────────────────────────────────────────────

enum class TimelineBucket { Today, ThisWeek, ThisMonth, Older }

private fun TimelineBucket.labelRes(): Int = when (this) {
    TimelineBucket.Today -> R.string.ui_conversation_history_today
    TimelineBucket.ThisWeek -> R.string.ui_conversation_history_this_week
    TimelineBucket.ThisMonth -> R.string.ui_conversation_history_this_month
    TimelineBucket.Older -> R.string.ui_conversation_history_older
}

private data class TimelineSection(
    val bucket: TimelineBucket,
    val conversations: List<ConversationSummary>,
)

/**
 * 按 updatedAt 分桶：今天 / 本周 / 本月 / 更早（日历边界，非滚动窗口）。
 * 输入已按 updated_at DESC 排序；输出 section 按桶顺序，空桶不出现，桶内保持原序。
 */
private fun groupByTimeline(conversations: List<ConversationSummary>): List<TimelineSection> {
    if (conversations.isEmpty()) return emptyList()
    val now = Calendar.getInstance()
    return conversations
        .groupBy { bucketOf(it.updatedAt, now) }
        .let { byBucket ->
            TimelineBucket.entries.mapNotNull { bucket ->
                byBucket[bucket]?.let { TimelineSection(bucket, it) }
            }
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
            .padding(top = liquidScreenTopPadding(24.dp), bottom = 24.dp),
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
private fun ConversationDeleteConfirmationDialog(
    conversation: ConversationSummary?,
    onDismissRequest: () -> Unit,
    onConfirmClick: (ConversationSummary) -> Unit,
) {
    val untitledConversation = stringResource(R.string.ui_conversation_history_untitled)
    val title = conversation?.title?.ifBlank { untitledConversation }.orEmpty()
    ConfirmationLiquidDialog(
        visible = conversation != null,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.ui_conversation_history_delete_dialog_title),
        text = stringResource(R.string.ui_conversation_history_delete_dialog_text, title),
        negativeButtonText = stringResource(R.string.ui_conversation_history_delete_dialog_cancel),
        positiveButtonText = stringResource(R.string.ui_conversation_history_delete_dialog_confirm),
        onNegativeClick = onDismissRequest,
        onPositiveClick = {
            conversation?.let(onConfirmClick)
        },
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

@Preview(
    name = "Conversation History List",
    showBackground = true,
    widthDp = 420,
    heightDp = 900,
)
@Composable
private fun ConversationHistoryListPreview() {
    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000
    BaseTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            ConversationHistoryPageContent(
                uiState = ConversationHistoryUiState(
                    conversations = listOf(
                        previewConversationSummary(
                            id = "conversation-1",
                            title = "检查当前工具状态",
                            preview = "I've done the check and summarized the result.",
                            updatedAt = now - 30 * 60 * 1000,
                        ),
                        previewConversationSummary(
                            id = "conversation-2",
                            title = "分析日志",
                            preview = "The failure path starts after the second request.",
                            updatedAt = now - 3 * day,
                        ),
                        previewConversationSummary(
                            id = "conversation-3",
                            title = "上周的会话",
                            preview = "Summary of last week's work.",
                            updatedAt = now - 5 * day,
                        ),
                        previewConversationSummary(
                            id = "conversation-4",
                            title = "上个月",
                            preview = "Older conversation.",
                            updatedAt = now - 20 * day,
                        ),
                        previewConversationSummary(
                            id = "conversation-5",
                            title = "很久以前",
                            preview = "Very old.",
                            updatedAt = now - 120 * day,
                        ),
                    ),
                ),
                activeConversationId = "conversation-1",
                onConversationClick = {},
                onConversationDelete = {},
            )
        }
    }
}

@Preview(
    name = "Conversation History Empty",
    showBackground = true,
    widthDp = 420,
    heightDp = 900,
)
@Composable
private fun ConversationHistoryEmptyPreview() {
    BaseTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            ConversationHistoryPageContent(
                uiState = ConversationHistoryUiState(),
                activeConversationId = null,
                onConversationClick = {},
                onConversationDelete = {},
            )
        }
    }
}

@Preview(
    name = "Conversation History Error",
    showBackground = true,
    widthDp = 420,
    heightDp = 900,
)
@Composable
private fun ConversationHistoryErrorPreview() {
    BaseTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            ConversationHistoryPageContent(
                uiState = ConversationHistoryUiState(errorMessage = "Database is not ready."),
                activeConversationId = null,
                onConversationClick = {},
                onConversationDelete = {},
            )
        }
    }
}

private fun previewConversationSummary(
    id: String,
    title: String,
    preview: String,
    updatedAt: Long = 0L,
): ConversationSummary {
    return ConversationSummary(
        id = id,
        title = title,
        titleEdited = false,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        lastMessagePreview = preview,
        turnCount = 1,
    )
}
