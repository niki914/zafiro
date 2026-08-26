package com.niki914.zafiro.app.ui.content

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.R
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.LocalLiquidViewportAvoidanceController
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.uikit.infra.liquidScreenHazeSource
import com.niki914.uikit.infra.liquidScreenTopPadding
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.PageChromeMenuItem
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.model.ActionSource
import com.niki914.zafiro.app.ui.model.HomeChatBlock
import com.niki914.zafiro.app.ui.model.HomeChatIntent
import com.niki914.zafiro.app.ui.model.HomeChatTurn
import com.niki914.zafiro.app.ui.model.HomeChatUiState
import com.niki914.zafiro.app.ui.model.HomeChatViewModel
import com.niki914.zafiro.app.ui.model.HomeToolState
import com.niki914.zafiro.app.ui.model.HomeToolStatus
import com.niki914.zafiro.app.ui.model.ToolPresentation
import com.niki914.zafiro.app.ui.nav.TextTitle
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import com.niki914.zafiro.repo.UpdateCheckHolder
import com.niki914.uikit.base.BaseTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.ClipData

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
        onReGenerate = { id ->
            viewModel.sendIntent(HomeChatIntent.ReGenerateAt(id))
        },
        onFork = { id ->
            viewModel.sendIntent(HomeChatIntent.ForkAt(id))
        },
        expandedToolRuns = uiState.expandedToolRuns,
        expandedToolResults = uiState.expandedToolResults,
        expandedThinking = uiState.expandedThinking,
        onToggleToolRun = { turnId, runStartIndex ->
            viewModel.sendIntent(HomeChatIntent.ToggleToolRun(turnId, runStartIndex))
        },
        onToggleToolResult = { turnId, runStartIndex, toolIndex ->
            viewModel.sendIntent(HomeChatIntent.ToggleToolResult(turnId, runStartIndex, toolIndex))
        },
        onToggleThinking = { turnId, blockIndex ->
            viewModel.sendIntent(HomeChatIntent.ToggleThinking(turnId, blockIndex))
        },
        expandedActionTurnId = uiState.expandedActionTurnId,
        expandedActionSource = uiState.expandedActionSource,
        activeThinkingKey = uiState.activeThinkingKey,
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    onReGenerate: (Long) -> Unit,
    onFork: (Long) -> Unit,
    expandedToolRuns: Set<String>,
    expandedToolResults: Set<String>,
    expandedThinking: Set<String>,
    onToggleToolRun: (Long, Int) -> Unit,
    onToggleToolResult: (Long, Int, Int) -> Unit,
    onToggleThinking: (Long, Int) -> Unit,
    expandedActionTurnId: Long?,
    expandedActionSource: ActionSource?,
    activeThinkingKey: String? = null,
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
            itemsIndexed(
                items = uiState.turns,
                key = { _, turn -> turn.id },
            ) { index, turn ->
                // User 气泡组内位置（渲染层：相邻 turn 无 agent 内容即同组，与数据层 turn 无关）
                val position = userBubblePosition(uiState.turns, index)
                // 顶距：组中/组末用组内间隙与上一气泡连体；组首/单条维持 turn 分隔
                val turnTopPad = when {
                    index == 0 -> Modifier
                    position == UserBubblePosition.GroupMid || position == UserBubblePosition.GroupLast ->
                        Modifier.padding(top = UserBubbleGap)

                    else -> Modifier.padding(top = TurnSeparator)
                }
                HomeChatTurnItem(
                    turn = turn,
                    userBubblePosition = position,
                    onContentTap = onContentTap,
                    onReGenerate = onReGenerate,
                    onFork = onFork,
                    expandedToolRuns = expandedToolRuns,
                    expandedToolResults = expandedToolResults,
                    expandedThinking = expandedThinking,
                    onToggleToolRun = onToggleToolRun,
                    onToggleToolResult = onToggleToolResult,
                    onToggleThinking = onToggleThinking,
                    expandedActionTurnId = expandedActionTurnId,
                    expandedActionSource = expandedActionSource,
                    activeThinkingKey = activeThinkingKey,
                    onToggleActionRow = onToggleActionRow,
                    isGenerating = uiState.isGenerating,
                    modifier = turnTopPad.fillMaxWidth(),
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

        if (uiState.isLoadingConversation) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 渲染层连续 User 气泡分组：纯 User turn（blocks 为空）与相邻纯 User turn 连成一组。
 * 单条判定：自身有 agent 内容，或前后均非纯 User。
 */
private fun userBubblePosition(turns: List<HomeChatTurn>, index: Int): UserBubblePosition {
    val isBare = turns[index].blocks.isEmpty()
    val prevBare = index > 0 && turns[index - 1].blocks.isEmpty()
    val nextBare = index < turns.lastIndex && turns[index + 1].blocks.isEmpty()
    return when {
        !isBare || (!prevBare && !nextBare) -> UserBubblePosition.Single
        !prevBare -> UserBubblePosition.GroupFirst
        !nextBare -> UserBubblePosition.GroupLast
        else -> UserBubblePosition.GroupMid
    }
}

@Composable
private fun HomeChatTurnItem(
    turn: HomeChatTurn,
    userBubblePosition: UserBubblePosition,
    onContentTap: () -> Unit,
    onReGenerate: (Long) -> Unit,
    onFork: (Long) -> Unit,
    expandedToolRuns: Set<String>,
    expandedToolResults: Set<String>,
    expandedThinking: Set<String>,
    onToggleToolRun: (Long, Int) -> Unit,
    onToggleToolResult: (Long, Int, Int) -> Unit,
    onToggleThinking: (Long, Int) -> Unit,
    expandedActionTurnId: Long?,
    expandedActionSource: ActionSource?,
    activeThinkingKey: String? = null,
    onToggleActionRow: (Long, ActionSource) -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
) {
    val canToggleAction = !isGenerating && turn.blocks.isNotEmpty()

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun copyText(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
        }
        Toast.makeText(context, R.string.ui_toast_copied, Toast.LENGTH_SHORT).show()
    }

    val isActionExpanded = expandedActionTurnId == turn.id
    val actionSource = expandedActionSource
    var showActionRow by remember { mutableStateOf(false) }
    LaunchedEffect(isActionExpanded) {
        showActionRow = isActionExpanded
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(BlockSpacing),
    ) {
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
                )
                // 用户消息 → agent 内容的 turn 分隔：总间距 TurnSeparator（块间距之上补差，见参数表）；
                // 组内成员不放底部补差，组内间隙由下一 item 顶距承担（单条独立时保留）
                .padding(
                    bottom = if (userBubblePosition == UserBubblePosition.Single) {
                        TurnSeparator - BlockSpacing
                    } else {
                        0.dp
                    }
                ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            UserMessageBubble(text = turn.userText, position = userBubblePosition)
        }

        AnimatedVisibility(
            visible = showActionRow && actionSource == ActionSource.User,
            enter = expandVertically() + fadeIn(),
        ) {
            TurnActionRow(
                source = ActionSource.User,
                onCopy = {
                    copyText(turn.userText)
                },
                onReGenerate = { onReGenerate(turn.id) },
                onFork = { onFork(turn.id) },
            )
        }

        var blockIndex = 0
        while (blockIndex < turn.blocks.size) {
            // Collect consecutive Tool blocks into a run
            val runStart = blockIndex
            var runEnd = runStart
            while (runEnd < turn.blocks.size && turn.blocks[runEnd] is HomeChatBlock.Tool) {
                runEnd++
            }
            val runSize = runEnd - runStart
            if (runSize >= 1) {
                val statuses = turn.blocks.subList(runStart, runEnd)
                    .map { (it as HomeChatBlock.Tool).status }
                val runKey = "${turn.id}_${runStart}"
                val runResults = expandedToolResults
                    .filter { it.startsWith("${runKey}_") }
                    .mapNotNull { it.removePrefix("${runKey}_").toIntOrNull() }
                    .toSet()
                ToolChain(
                    tools = statuses,
                    isExpanded = runKey in expandedToolRuns,
                    expandedResults = runResults,
                    onToggleRun = { onToggleToolRun(turn.id, runStart) },
                    onToggleResult = { ti ->
                        onToggleToolResult(turn.id, runStart, ti)
                    },
                    onContentClick = {
                        onContentTap()
                        if (canToggleAction) {
                            onToggleActionRow(turn.id, ActionSource.Agent)
                        }
                    },
                )
                blockIndex = runEnd
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
                                )
                            }
                        }
                    }
                    is HomeChatBlock.Error -> {
                        AssistantErrorBlock(
                            message = block.message,
                            code = block.code,
                        )
                    }
                    is HomeChatBlock.Thinking -> {
                        // blockIndex 是 var，lambda 捕获按引用；先快照成 val 再进 lambda
                        val blockIndexNow = blockIndex
                        val thinkingKey = "${turn.id}_$blockIndexNow"
                        val isThinkingExpanded = thinkingKey in expandedThinking
                        CollapsibleBlock(
                            icon = ToolPresentation.Thinking,
                            title = "Thinking" + ToolPresentation
                                .previewOf(block.text)
                                ?.let { " · $it" }
                                .orEmpty(),
                            isExpanded = isThinkingExpanded,
                            onToggle = { onToggleThinking(turn.id, blockIndexNow) },
                        ) {
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
                                ToolResultText(
                                    text = block.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = BlockBodyAlpha),
                                    // active 思考块展开时滚到底跟随；用户可手动滚动不锁
                                    autoScrollToEnd = isThinkingExpanded && thinkingKey == activeThinkingKey,
                                )
                            }
                        }
                    }
                    is HomeChatBlock.Tool -> {} // handled above
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
                    copyText(text)
                },
                onReGenerate = { onReGenerate(turn.id) },
                onFork = { onFork(turn.id) },
            )
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
                        // 连续用户消息组：失败回合（错误卡已随新回合清除）→ 再发一条，两条纯 User 连成一组
                        HomeChatTurn(id = 1L, userText = "继续分析一下 MCP 的配置差异。"),
                        HomeChatTurn(id = 2L, userText = "先不用管 MCP 了，讲讲会话树。"),
                    ),
                ),
                listState = rememberLazyListState(),
                composerBottomPadding = 20.dp,
                onContentTap = {},
                onInputChange = {},
                onSendClick = {},
                onStopClick = {},
                onComposerFocusChanged = {},
                onReGenerate = { },
                onFork = { },
                expandedToolRuns = emptySet(),
                expandedToolResults = emptySet(),
                expandedThinking = emptySet(),
                onToggleToolRun = { _, _ -> },
                onToggleToolResult = { _, _, _ -> },
                onToggleThinking = { _, _ -> },
                expandedActionTurnId = null,
                expandedActionSource = null,
                onToggleActionRow = { _, _ -> },
            )
        }
    }
}
