package com.niki914.zafiro.app.ui.route

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageBackHandler
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.content.ConversationHistoryPageContent
import com.niki914.zafiro.app.ui.model.conversation.ConversationHistoryEffect
import com.niki914.zafiro.app.ui.model.conversation.ConversationHistoryIntent
import com.niki914.zafiro.app.ui.model.conversation.ConversationHistoryViewModel
import com.niki914.zafiro.app.ui.nav.TextTitle
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec

@Composable
internal fun ConversationHistoryPageRoute(
    activeConversationId: String?,
    activeConversationTitle: String?,
    onBack: () -> Unit,
    onConversationSelected: (String) -> Unit,
    onCurrentConversationDeleted: suspend (String) -> Unit,
    onActiveConversationRenamed: ((String) -> Unit)? = null,
) {
    val viewModel = pageViewModel<ConversationHistoryViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val latestOnBack by rememberUpdatedState(onBack)
    val latestOnConversationSelected by rememberUpdatedState(onConversationSelected)
    val latestOnCurrentConversationDeleted by rememberUpdatedState(onCurrentConversationDeleted)
    val latestOnActiveConversationRenamed by rememberUpdatedState(onActiveConversationRenamed)
    val backContentDescription = stringResource(
        R.string.ui_conversation_history_back_content_description,
    )

    LaunchedEffect(viewModel) {
        viewModel.sendIntent(ConversationHistoryIntent.Load)
    }
    LaunchedEffect(viewModel, activeConversationId) {
        viewModel.sendIntent(ConversationHistoryIntent.SetActiveConversation(activeConversationId))
    }
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is ConversationHistoryEffect.OpenConversation ->
                    latestOnConversationSelected(effect.id)

                // 活动会话的删除由宿主（ZafiroApp）执行，完成后回报结果，再走与普通删除一致的收尾。
                is ConversationHistoryEffect.DeleteActiveConversation ->
                    runCatching { latestOnCurrentConversationDeleted(effect.id) }
                        .onSuccess {
                            viewModel.sendIntent(
                                ConversationHistoryIntent.ActiveConversationDeleted(effect.id),
                            )
                        }
                        .onFailure { throwable ->
                            viewModel.sendIntent(
                                ConversationHistoryIntent.DeleteFailed(
                                    throwable.message ?: throwable::class.java.simpleName,
                                ),
                            )
                        }

                is ConversationHistoryEffect.ActiveConversationRenamed ->
                    latestOnActiveConversationRenamed?.invoke(effect.title)
            }
        }
    }

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

    ConversationHistoryPageContent(
        uiState = uiState,
        onConversationClick = { id -> latestOnConversationSelected(id) },
        onConversationDelete = { id ->
            viewModel.sendIntent(ConversationHistoryIntent.Delete(id))
        },
        onConversationRename = { id, newTitle ->
            viewModel.sendIntent(ConversationHistoryIntent.Rename(id, newTitle))
        },
        onConversationFork = { id ->
            viewModel.sendIntent(ConversationHistoryIntent.Fork(id))
        },
        onConversationPin = { id, pinned ->
            viewModel.sendIntent(ConversationHistoryIntent.SetPinned(id, pinned))
        },
    )
}
