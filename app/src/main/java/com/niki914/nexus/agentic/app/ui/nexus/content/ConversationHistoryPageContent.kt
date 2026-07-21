package com.niki914.nexus.agentic.app.ui.nexus.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.conversation.ConversationSummary
import com.niki914.nexus.agentic.app.ui.infra.ConfirmationLiquidDialog
import com.niki914.nexus.agentic.app.ui.infra.ProvideLiquidScreenContentForPreview
import com.niki914.nexus.agentic.app.ui.infra.component.SettingsGroupCard
import com.niki914.nexus.agentic.app.ui.infra.component.SwipeDismissSettingsItemCard
import com.niki914.nexus.agentic.app.ui.infra.liquidScreenHazeSource
import com.niki914.nexus.agentic.app.ui.infra.liquidScreenTopPadding
import com.niki914.nexus.base.BaseTheme

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

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .liquidScreenHazeSource(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = liquidScreenTopPadding(24.dp),
            end = 20.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(
            items = conversations,
            key = { conversation -> conversation.id },
        ) { conversation ->
            val title = conversation.title.ifBlank { untitledConversation }
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

        if (deleteErrorPrefix != null) {
            item {
                ConversationHistoryInlineErrorText(error = deleteErrorPrefix)
            }
        }
    }
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
            .liquidScreenHazeSource()
            .padding(horizontal = 20.dp)
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
    BaseTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            ConversationHistoryPageContent(
                uiState = ConversationHistoryUiState(
                    conversations = listOf(
                        previewConversationSummary(
                            id = "conversation-1",
                            title = "检查当前工具状态",
                            preview = "I've done the check and summarized the result.",
                        ),
                        previewConversationSummary(
                            id = "conversation-2",
                            title = "分析日志",
                            preview = "The failure path starts after the second request.",
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
): ConversationSummary {
    return ConversationSummary(
        id = id,
        title = title,
        titleEdited = false,
        createdAt = 0L,
        updatedAt = 0L,
        lastMessagePreview = preview,
        turnCount = 1,
    )
}
