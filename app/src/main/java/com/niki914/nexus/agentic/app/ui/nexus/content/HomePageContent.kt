package com.niki914.nexus.agentic.app.ui.nexus.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.ConfirmationLiquidDialog
import com.niki914.nexus.agentic.app.ui.infra.LocalLiquidViewportAvoidanceController
import com.niki914.nexus.agentic.app.ui.infra.ProvideLiquidScreenContentForPreview
import com.niki914.nexus.agentic.app.ui.infra.liquidScreenHazeSource
import com.niki914.nexus.agentic.app.ui.infra.liquidScreenTopPadding
import com.niki914.nexus.agentic.app.ui.infra.nav.pageViewModel
import com.niki914.nexus.agentic.app.ui.nexus.PageChromeContribution
import com.niki914.nexus.agentic.app.ui.nexus.PageChromeMenuItem
import com.niki914.nexus.agentic.app.ui.nexus.RegisterPageChrome
import com.niki914.nexus.agentic.app.ui.nexus.model.ActionSource
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatBlock
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatIntent
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatTurn
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatUiState
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeChatViewModel
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolState
import com.niki914.nexus.agentic.repo.UpdateCheckHolder
import com.niki914.nexus.agentic.app.ui.nexus.model.HomeToolStatus
import com.niki914.nexus.agentic.app.ui.nexus.nav.TextTitle
import com.niki914.nexus.agentic.app.ui.nexus.nav.TopBarActionSpec
import com.niki914.nexus.base.BaseTheme
import kotlinx.coroutines.flow.collectLatest

@Composable
fun HomePageContent(
    selectedConversationId: String?,
    onConversationSelectionConsumed: (String) -> Unit,
    onActiveConversationChanged: (String?, String?) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = pageViewModel<HomeChatViewModel>()
    val newConversationMenuLabel = stringResource(R.string.ui_home_menu_new_conversation)
    val settingsMenuLabel = stringResource(R.string.ui_settings_menu_entry)
    val historyContentDescription = stringResource(R.string.ui_home_history_content_description)
    val latestViewModel by rememberUpdatedState(viewModel)
    val latestOnOpenHistory by rememberUpdatedState(onOpenHistory)
    val latestOnOpenSettings by rememberUpdatedState(onOpenSettings)
    val latestOnConversationSelectionConsumed by rememberUpdatedState(
        onConversationSelectionConsumed
    )
    val latestOnActiveConversationChanged by rememberUpdatedState(onActiveConversationChanged)
    val uiState by viewModel.uiStateFlow.collectAsState()
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    var isComposerFocused by remember { mutableStateOf(false) }
    val effectiveImeBottom = if (isComposerFocused) imeBottom else 0.dp
    val composerBottomPadding = (effectiveImeBottom + 12.dp).coerceAtLeast(navigationBottom + 20.dp)
    val bottomThresholdPx = with(density) { 24.dp.roundToPx() }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var shouldFollowBottom by remember { mutableStateOf(true) }
    var hasPendingUserScrollDecision by remember { mutableStateOf(false) }
    val lastTurn = uiState.turns.lastOrNull()
    val bottomContentVersion = remember(
        uiState.turns.size,
        uiState.streamEventCount,
        lastTurn?.id,
        lastTurn?.blocks?.size,
    ) {
        listOf(
            uiState.turns.size,
            uiState.streamEventCount,
            lastTurn?.id,
            lastTurn?.blocks?.size,
        )
    }
    val isAtBottom by remember(listState, bottomThresholdPx) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleItem =
                layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            val viewportEnd = layoutInfo.viewportEndOffset
            lastVisibleItem.index == layoutInfo.totalItemsCount - 1 &&
                    lastVisibleItem.offset + lastVisibleItem.size <= viewportEnd + bottomThresholdPx
        }
    }
    val dismissInputFocus = remember(focusManager, keyboardController) {
        {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            hasPendingUserScrollDecision = true
        }
    }
    LaunchedEffect(listState, isAtBottom) {
        snapshotFlow { listState.isScrollInProgress }
            .collectLatest { isScrollInProgress ->
                if (!isScrollInProgress && hasPendingUserScrollDecision) {
                    shouldFollowBottom = isAtBottom
                    hasPendingUserScrollDecision = false
                }
            }
    }
    LaunchedEffect(bottomContentVersion, shouldFollowBottom) {
        if (shouldFollowBottom) {
            listState.scrollToItem(uiState.turns.size)
        }
    }
    LaunchedEffect(selectedConversationId) {
        val id = selectedConversationId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        latestViewModel.sendIntent(HomeChatIntent.LoadConversation(id))
        latestOnConversationSelectionConsumed(id)
    }
    LaunchedEffect(uiState.currentConversationId, uiState.currentConversationTitle) {
        latestOnActiveConversationChanged(
            uiState.currentConversationId,
            uiState.currentConversationTitle,
        )
    }

    val pageChromeContribution = remember(
        uiState.currentConversationTitle,
        newConversationMenuLabel,
        settingsMenuLabel,
        historyContentDescription,
    ) {
        PageChromeContribution(
            titleSpec = uiState.currentConversationTitle
                ?.takeIf { it.isNotBlank() }
                ?.let { TextTitle(it) },
            leftAction = TopBarActionSpec(
                icon = Icons.Default.History,
                onClick = { latestOnOpenHistory() },
                contentDescription = historyContentDescription,
            ),
            menuItems = listOf(
                PageChromeMenuItem(
                    key = "new_conversation",
                    title = newConversationMenuLabel,
                    onClick = {
                        latestViewModel.sendIntent(HomeChatIntent.NewConversation)
                    },
                ),
                PageChromeMenuItem(
                    key = "settings",
                    title = settingsMenuLabel,
                    onClick = { latestOnOpenSettings() },
                ),
            ),
        )
    }
    RegisterPageChrome(pageChromeContribution)

    HomePageContentBody(
        uiState = uiState,
        listState = listState,
        composerBottomPadding = composerBottomPadding,
        onContentTap = dismissInputFocus,
        onInputChange = { value ->
            viewModel.sendIntent(HomeChatIntent.InputChanged(value))
        },
        onSendClick = {
            dismissInputFocus()
            shouldFollowBottom = true
            viewModel.sendIntent(HomeChatIntent.Send)
        },
        onStopClick = {
            viewModel.sendIntent(HomeChatIntent.StopGenerating)
        },
        onComposerFocusChanged = { focused ->
            isComposerFocused = focused
        },
        onToggleToolRun = { turnId, runStartIndex ->
            viewModel.sendIntent(
                HomeChatIntent.ToggleToolRunExpanded(turnId, runStartIndex)
            )
        },
        onReGenerate = { id ->
            viewModel.sendIntent(HomeChatIntent.ReGenerateAt(id))
        },
        onFork = { id ->
            viewModel.sendIntent(HomeChatIntent.ForkAt(id))
        },
        expandedActionTurnId = uiState.expandedActionTurnId,
        expandedActionSource = uiState.expandedActionSource,
        onToggleActionRow = { turnId, source ->
            viewModel.sendIntent(
                HomeChatIntent.ToggleActionRow(turnId, source)
            )
        },
    )

    val updateCheckResult by UpdateCheckHolder.result.collectAsState()
    if (updateCheckResult?.hasUpdate == true) {
        val uriHandler = LocalUriHandler.current
        val remoteVersion = updateCheckResult!!.remoteVersion.orEmpty()
        val releaseUrl = updateCheckResult!!.releaseUrl.orEmpty()
        ConfirmationLiquidDialog(
            visible = true,
            onDismissRequest = { UpdateCheckHolder.dismiss() },
            title = stringResource(R.string.update_dialog_title),
            text = stringResource(R.string.update_dialog_text, remoteVersion),
            positiveButtonText = stringResource(R.string.update_dialog_confirm),
            negativeButtonText = stringResource(R.string.update_dialog_cancel),
            onPositiveClick = {
                uriHandler.openUri(releaseUrl)
                UpdateCheckHolder.dismiss()
            },
            onNegativeClick = { UpdateCheckHolder.dismiss() },
            dismissOnBackgroundTap = false,
        )
    }
}

@Composable
private fun HomePageContentBody(
    uiState: HomeChatUiState,
    listState: LazyListState,
    composerBottomPadding: Dp,
    onContentTap: () -> Unit,
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    onComposerFocusChanged: (Boolean) -> Unit,
    onToggleToolRun: (Long, Int) -> Unit,
    onReGenerate: (Long) -> Unit,
    onFork: (Long) -> Unit,
    expandedActionTurnId: Long?,
    expandedActionSource: ActionSource?,
    onToggleActionRow: (Long, ActionSource) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .liquidScreenHazeSource(),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onContentTap,
                ),
            contentPadding = PaddingValues(
                start = 20.dp,
                top = liquidScreenTopPadding(24.dp),
                end = 20.dp,
                bottom = 128.dp,
            ),
        ) {
            items(
                items = uiState.turns,
                key = { turn -> turn.id },
            ) { turn ->
                HomeChatTurnItem(
                    turn = turn,
                    onContentTap = onContentTap,
                    expandedToolRunKeys = uiState.expandedToolRunKeys,
                    onToggleToolRun = onToggleToolRun,
                    onReGenerate = onReGenerate,
                    onFork = onFork,
                    expandedActionTurnId = expandedActionTurnId,
                    expandedActionSource = expandedActionSource,
                    onToggleActionRow = onToggleActionRow,
                    isGenerating = uiState.isGenerating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 18.dp),
                )
            }
            item(key = "bottom_anchor") {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp),
                )
            }
        }

        CompositionLocalProvider(LocalLiquidViewportAvoidanceController provides null) {
            LiquidChatComposer(
                value = uiState.input,
                onValueChange = onInputChange,
                onSendClick = onSendClick,
                onStopClick = onStopClick,
                isGenerating = uiState.isGenerating,
                maxLines = 10,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onFocusChanged { focusState ->
                        onComposerFocusChanged(focusState.hasFocus)
                    }
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = composerBottomPadding,
                    ),
            )
        }
    }
}

private data class ToolRun(
    val startIndex: Int,
    val endIndex: Int, // exclusive
) {
    val count: Int get() = endIndex - startIndex
}

private fun List<HomeChatBlock>.findConsecutiveToolRuns(): List<ToolRun> {
    val runs = mutableListOf<ToolRun>()
    var i = 0
    while (i < size) {
        if (this[i] is HomeChatBlock.Tool) {
            val start = i
            while (i < size && this[i] is HomeChatBlock.Tool) {
                i++
            }
            val count = i - start
            if (count >= 2) {
                runs.add(ToolRun(start, i))
            }
        } else {
            i++
        }
    }
    return runs
}

@Composable
private fun HomeChatTurnItem(
    turn: HomeChatTurn,
    onContentTap: () -> Unit,
    expandedToolRunKeys: Set<String>,
    onToggleToolRun: (Long, Int) -> Unit,
    onReGenerate: (Long) -> Unit,
    onFork: (Long) -> Unit,
    expandedActionTurnId: Long?,
    expandedActionSource: ActionSource?,
    onToggleActionRow: (Long, ActionSource) -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
) {
    val canToggleAction = !isGenerating && turn.blocks.isNotEmpty()
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val isActionExpanded = expandedActionTurnId == turn.id
    val actionSource = expandedActionSource
    var showActionRow by remember { mutableStateOf(false) }
    LaunchedEffect(isActionExpanded) {
        showActionRow = isActionExpanded
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        onContentTap()
                        if (canToggleAction) {
                            onToggleActionRow(turn.id, ActionSource.User)
                        }
                    },
                ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            UserMessageBubble(text = turn.userText)
        }

        AnimatedVisibility(
            visible = showActionRow && actionSource == ActionSource.User,
            enter = expandVertically() + fadeIn(),
        ) {
            TurnActionRow(
                source = ActionSource.User,
                onCopy = {
                    val text = turn.userText
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, R.string.ui_toast_copied, Toast.LENGTH_SHORT).show()
                },
                onReGenerate = { onReGenerate(turn.id) },
                onFork = { onFork(turn.id) },
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        val toolRuns = remember(turn.blocks) {
            turn.blocks.findConsecutiveToolRuns()
        }
        var blockIndex = 0
        while (blockIndex < turn.blocks.size) {
            val run = toolRuns.find { it.startIndex == blockIndex }
            if (run != null) {
                val tools = turn.blocks.subList(run.startIndex, run.endIndex)
                    .map { it as HomeChatBlock.Tool }
                ToolRunItem(
                    tools = tools,
                    expanded = "${turn.id}_${run.startIndex}" in expandedToolRunKeys,
                    onToggle = { onToggleToolRun(turn.id, run.startIndex) },
                    modifier = Modifier.padding(top = 12.dp),
                )
                blockIndex = run.endIndex
            } else {
                when (val block = turn.blocks[blockIndex]) {
                    is HomeChatBlock.Text -> {
                        if (block.text.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = {
                                            onContentTap()
                                            if (canToggleAction) {
                                                onToggleActionRow(turn.id, ActionSource.Agent)
                                            }
                                        },
                                    ),
                            ) {
                                AssistantOutputText(
                                    text = block.text,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                    }

                    is HomeChatBlock.Tool -> {
                        ToolStatusPill(
                            status = block.status,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }

                    is HomeChatBlock.Error -> {
                        AssistantErrorBlock(
                            message = block.message,
                            code = block.code,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
                blockIndex++
            }
        }

        AnimatedVisibility(
            visible = showActionRow && actionSource == ActionSource.Agent,
            enter = expandVertically() + fadeIn(),
        ) {
            TurnActionRow(
                source = ActionSource.Agent,
                onCopy = {
                    val text = turn.blocks
                        .filterIsInstance<HomeChatBlock.Text>()
                        .joinToString("\n\n") { it.text }
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, R.string.ui_toast_copied, Toast.LENGTH_SHORT).show()
                },
                onReGenerate = { onReGenerate(turn.id) },
                onFork = { onFork(turn.id) },
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun ToolRunItem(
    tools: List<HomeChatBlock.Tool>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!expanded) {
        UsedNToolsPill(
            count = tools.size,
            onClick = onToggle,
            modifier = modifier,
        )
    } else {
        Column(modifier = modifier) {
            ToolStatusPill(status = tools[0].status)
            var showRemaining by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { showRemaining = true }
            AnimatedVisibility(
                visible = showRemaining,
                enter = expandVertically() + fadeIn(),
            ) {
                Column {
                    tools.drop(1).forEach { tool ->
                        ToolStatusPill(
                            status = tool.status,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Preview(
    name = "Home Page Preview",
    showBackground = true,
    widthDp = 420,
    heightDp = 900,
)
@Composable
private fun HomePageContentPreview() {
    BaseTheme {
        ProvideLiquidScreenContentForPreview(topPadding = 0.dp) {
            HomePageContentBody(
                uiState = HomeChatUiState(
                    input = "继续分析",
                    turns = listOf(
                        HomeChatTurn(
                            id = 0L,
                            userText = "帮我检查一下当前工具状态。",
                            blocks = listOf(
                                HomeChatBlock.Text("I'll call the available tools first."),
                                HomeChatBlock.Tool(
                                    HomeToolStatus(
                                        callId = "tool-1",
                                        name = "read_session",
                                        state = HomeToolState.Succeeded,
                                    )
                                ),
                                HomeChatBlock.Tool(
                                    HomeToolStatus(
                                        callId = "tool-2",
                                        name = "update_config",
                                        state = HomeToolState.Running,
                                    )
                                ),
                                HomeChatBlock.Tool(
                                    HomeToolStatus(
                                        callId = "tool-3",
                                        name = "sync_mcp",
                                        state = HomeToolState.Failed,
                                    )
                                ),
                                HomeChatBlock.Error("MCP 工具调用失败，请检查服务配置。"),
                                HomeChatBlock.Text("I've done the check and summarized the result."),
                            ),
                        ),
                    ),
                ),
                listState = rememberLazyListState(),
                composerBottomPadding = 20.dp,
                onContentTap = {},
                onInputChange = {},
                onSendClick = {},
                onStopClick = {},
                onComposerFocusChanged = {},
                onToggleToolRun = { _, _ -> },
                onReGenerate = { },
                onFork = { },
                expandedActionTurnId = null,
                expandedActionSource = null,
                onToggleActionRow = { _, _ -> },
            )
        }
    }
}
