package com.niki914.zafiro.app.ui.route

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.ui.PageBackHandler
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.content.ConversationHistoryPageContent
import com.niki914.zafiro.app.ui.content.ConversationHistoryUiState
import com.niki914.zafiro.app.ui.nav.TextTitle
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import kotlinx.coroutines.launch

@Composable
internal fun ConversationHistoryPageRoute(
    activeConversationId: String?,
    activeConversationTitle: String?,
    onBack: () -> Unit,
    onConversationSelected: (String) -> Unit,
    onCurrentConversationDeleted: suspend (String) -> Unit,
) {
    var uiState by remember {
        mutableStateOf(ConversationHistoryUiState(isLoading = true))
    }
    val latestOnBack by rememberUpdatedState(onBack)
    val latestOnConversationSelected by rememberUpdatedState(onConversationSelected)
    val latestActiveConversationId by rememberUpdatedState(activeConversationId)
    val latestOnCurrentConversationDeleted by rememberUpdatedState(onCurrentConversationDeleted)
    val scope = rememberCoroutineScope()
    val backContentDescription = stringResource(
        R.string.ui_conversation_history_back_content_description,
    )

    val pageChromeContribution = remember(backContentDescription, activeConversationTitle) {
        PageChromeContribution(
            titleSpec = activeConversationTitle
                ?.takeIf { it.isNotBlank() }
                ?.let { TextTitle(it) },
            rightAction = TopBarActionSpec(
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                onClick = { latestOnBack() },
                contentDescription = backContentDescription,
            ),
            backHandler = PageBackHandler(
                shouldConsumeBack = { true },
                onConsumeBack = { latestOnBack() },
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    LaunchedEffect(Unit) {
        uiState = loadConversationHistoryState()
    }

    ConversationHistoryPageContent(
        uiState = uiState,
        activeConversationId = activeConversationId,
        onConversationClick = { id ->
            latestOnConversationSelected(id)
        },
        onConversationDelete = { id ->
            scope.launch {
                uiState = uiState.copy(deleteErrorMessage = null)
                runCatching {
                    if (latestActiveConversationId == id) {
                        latestOnCurrentConversationDeleted(id)
                    } else {
                        ConversationRepo.deleteConversation(id)
                    }
                }.onSuccess {
                    uiState = loadConversationHistoryState()
                }.onFailure { throwable ->
                    uiState = uiState.copy(
                        deleteErrorMessage = throwable.message ?: throwable::class.java.simpleName,
                    )
                }
            }
        },
    )
}

private suspend fun loadConversationHistoryState(): ConversationHistoryUiState {
    return runCatching {
        ConversationRepo.listConversations()
    }.fold(
        onSuccess = { conversations ->
            ConversationHistoryUiState(conversations = conversations)
        },
        onFailure = { throwable ->
            ConversationHistoryUiState(
                errorMessage = throwable.message ?: throwable::class.java.simpleName,
            )
        },
    )
}
