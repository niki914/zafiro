// githooks:ignore-file file-token-limit
package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.niki914.uikit.dialog.LocalHasActiveDialog
import com.niki914.uikit.screen.ReportTitleBarCollapsed
import com.niki914.uikit.scroll.rememberZafiroOverscrollController
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.notification.TurnNotificationManager
import com.niki914.zafiro.app.ui.helper.ZafiroToast
import com.niki914.zafiro.app.ui.content.dialog.ScreenControlConsentDialog
import com.niki914.zafiro.app.ui.content.dialog.ToolPermissionDialog
import com.niki914.zafiro.app.ui.content.dialog.UpdateAvailableDialog
import com.niki914.zafiro.app.ui.content.header.HomeConfigPopover
import com.niki914.zafiro.app.ui.content.header.HomeTitleConfigChip
import com.niki914.zafiro.app.ui.content.header.HomeTopBarContribution
import com.niki914.zafiro.app.ui.model.home.FileAttachReason
import com.niki914.zafiro.app.ui.model.home.HomeChatEffect
import com.niki914.zafiro.app.ui.model.home.HomeChatIntent
import com.niki914.zafiro.app.ui.model.home.HomeChatViewModel
import com.niki914.zafiro.app.ui.model.home.MessageActionsDisplay
import com.niki914.zafiro.business.application.ApplicationService
import com.niki914.zafiro.repo.UpdateCheckHolder
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 冷启动后仅首次进入 Home 时抢焦点弹键盘；进程内不再重复
 * （从设置页/历史页返回不打断用户）。仿 StartupPageContent.demoHasPlayed 的写法。
 */
private var composerAutoFocusDone = false
private const val AUTO_FOCUS_MAX_ATTEMPTS = 20
private const val AUTO_FOCUS_RETRY_INTERVAL_MILLIS = 150L

@Composable
fun HomePageContent(
    isCurrentPage: Boolean = true,
    selectedConversationId: String?,
    onConversationSelectionConsumed: (String) -> Unit,
    onActiveConversationChanged: (String?) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = pageViewModel<HomeChatViewModel>()
    val latestViewModel by rememberUpdatedState(viewModel)
    val latestOnOpenHistory by rememberUpdatedState(onOpenHistory)
    val latestOnOpenSettings by rememberUpdatedState(onOpenSettings)
    val latestOnConversationSelectionConsumed by rememberUpdatedState(
        onConversationSelectionConsumed
    )
    val latestOnActiveConversationChanged by rememberUpdatedState(onActiveConversationChanged)
    val uiState by viewModel.uiStateFlow.collectAsState()
    val latestUiState by rememberUpdatedState(uiState)

    // 附件失败提示：一次性 effect（不占状态）。
    // 三条文案按原因分（D22）：解析不出路径 / 没拿到全局文件访问权 / 读不到文件。
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is HomeChatEffect.FileAttachFailed -> ZafiroToast.show(
                    when (effect.reason) {
                        FileAttachReason.Unresolvable -> R.string.ui_home_attach_unresolvable
                        FileAttachReason.NoPermission -> R.string.ui_home_attach_no_permission
                        FileAttachReason.Unreadable -> R.string.ui_home_attach_unreadable
                    }
                )

                HomeChatEffect.EmptyInputAttempted -> ZafiroToast.show(R.string.ui_toast_empty_input)
            }
        }
    }
    val alwaysShowActions by XRepo.alwaysShowMessageActionsSetting.collectAsState()
    val alwaysShowDetailedToolOutput by XRepo.alwaysShowDetailedToolOutputSetting.collectAsState()
    val actionsDisplay = if (alwaysShowActions) {
        MessageActionsDisplay.Always
    } else {
        MessageActionsDisplay.OnTap
    }
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val overscrollController = rememberZafiroOverscrollController()

    // Home 必须显式接管顶栏折叠信号：列表位置会由程序化驱动（自动贴底），且工具结果框
    // 自带滚动子树——两者都是壳层累积量看不见的位移；当向上超滚时，内容推入顶栏区，顶栏需变实体遮挡。
    ReportTitleBarCollapsed {
        listState.firstVisibleItemIndex > 0 ||
            listState.firstVisibleItemScrollOffset > 0 ||
            overscrollController.overscrollOffset.value < 0f
    }

    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    var isComposerFocused by remember { mutableStateOf(false) }
    val effectiveImeBottom = if (isComposerFocused) imeBottom else 0.dp
    val isKeyboardActive = imeBottom > 0.dp || (isComposerFocused && uiState.input.isNotEmpty())
    LaunchedEffect(isKeyboardActive) {
        if (isKeyboardActive) {
            viewModel.sendIntent(HomeChatIntent.Warmup)
        }
    }
    // 统一视觉底间距：键盘关闭时与 composer 底距一致，不随 ime 放大——键盘打开时
    // 额外空间全部由 composerBottomPadding 提供，箭头/消息的呼吸空间保持固定
    val composerGap = navigationBottom + 20.dp
    val composerBottomPadding = (effectiveImeBottom + 12.dp).coerceAtLeast(composerGap)
    // composer 实测高度（默认 68dp = ZafiroChatComposer 的 minHeight），首帧布局后回填
    val composerHeight = remember { mutableStateOf(68.dp) }
    val bottomThresholdPx = with(density) { 24.dp.roundToPx() }
    val lastTurn = uiState.turns.lastOrNull()
    var pendingViewportAnchor by remember { mutableStateOf<ViewportAnchor?>(null) }
    var lastFollowedConversationId by rememberSaveable { mutableStateOf<String?>(null) }

    // 仅在结构性变化（新回合加入、会话切换/加载完成、生成状态变化）时触发贴底或锚定
    val bottomContentVersion = remember(
        uiState.currentConversationId,
        uiState.isLoadingConversation,
        uiState.isGenerating,
        uiState.turns.size,
        lastTurn?.id,
    ) {
        listOf(
            uiState.currentConversationId,
            uiState.isLoadingConversation,
            uiState.isGenerating,
            uiState.turns.size,
            lastTurn?.id,
        )
    }
    val isAtBottom by remember(listState, bottomThresholdPx) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            if (layoutInfo.totalItemsCount == 0 || (layoutInfo.totalItemsCount == 1 && uiState.turns.isEmpty())) {
                return@derivedStateOf true
            }
            val lastVisibleItem =
                layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            val viewportBottom = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
            val lastItemBottom = lastVisibleItem.offset + lastVisibleItem.size
            lastVisibleItem.index == layoutInfo.totalItemsCount - 1 &&
                kotlin.math.abs(lastItemBottom - viewportBottom) <= bottomThresholdPx
        }
    }
    val shouldFollowBottomState = rememberScrollFollowState(
        interactionSource = listState.interactionSource,
        isScrollInProgress = { listState.isScrollInProgress },
        isAtEnd = { isAtBottom },
    )
    var shouldFollowBottom by shouldFollowBottomState
    val dismissInputFocus = remember(focusManager, keyboardController) {
        {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    // 只在结构性内容事件发生时跳转：若存在分支切换/重生成锚点，则优先执行视口原地锚定
    LaunchedEffect(bottomContentVersion) {
        if (uiState.isLoadingConversation || uiState.turns.isEmpty()) {
            return@LaunchedEffect
        }
        val anchor = pendingViewportAnchor
        if (anchor != null) {
            val maxIndex = (uiState.turns.size - 1).coerceAtLeast(0)
            if (anchor.index in 0..maxIndex) {
                val safeOffset = anchor.offset.coerceAtLeast(0)
                listState.scrollToItem(anchor.index, safeOffset)
                pendingViewportAnchor = null
                shouldFollowBottom = anchor.followOnGeneration
                lastFollowedConversationId = uiState.currentConversationId
            }
        } else if (uiState.currentConversationId != lastFollowedConversationId) {
            lastFollowedConversationId = uiState.currentConversationId
            val unreadTurnId = uiState.unreadTurnId
            val unreadIndex = if (unreadTurnId != null) {
                uiState.turns.indexOfFirst { it.id == unreadTurnId }.takeIf { it >= 0 }
            } else null

            if (unreadIndex != null) {
                listState.scrollToItem(unreadIndex, 0)
                shouldFollowBottom = false
                viewModel.sendIntent(HomeChatIntent.ClearUnread(uiState.currentConversationId ?: ""))
            } else {
                listState.scrollToItem(uiState.turns.size)
                shouldFollowBottom = true
            }
        }
    }

    // 正文、Thinking 流式吐字与 Markdown 后续排版平滑跟进：在安全区耗尽（内容触及视口底端）后柔和微调，手势拥有最高打断权
    LaunchedEffect(shouldFollowBottom) {
        if (!shouldFollowBottom) return@LaunchedEffect
        snapshotFlow {
            if (listState.isScrollInProgress) return@snapshotFlow 0
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull() ?: return@snapshotFlow 0
            val isAtTail = last.index >= layout.totalItemsCount - 2
            if (!isAtTail) return@snapshotFlow 0

            val viewportBottom = layout.viewportEndOffset - layout.afterContentPadding
            val lastEnd = last.offset + last.size
            val overflow = lastEnd - viewportBottom
            if (overflow > 1) overflow else 0
        }.collectLatest { overflow ->
            if (overflow <= 0 || !shouldFollowBottom) return@collectLatest
            if (uiState.isLoadingConversation || uiState.turns.isEmpty()) return@collectLatest
            listState.scrollBy(overflow.toFloat())
        }
    }
    LaunchedEffect(selectedConversationId) {
        val id = selectedConversationId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        shouldFollowBottom = true
        pendingViewportAnchor = null
        latestViewModel.sendIntent(HomeChatIntent.LoadConversation(id))
        latestOnConversationSelectionConsumed(id)
        TurnNotificationManager.cancelNotification(id)
    }
    LaunchedEffect(uiState.currentConversationId) {
        latestOnActiveConversationChanged(uiState.currentConversationId)
    }
    DisposableEffect(isCurrentPage, uiState.currentConversationId) {
        findService<ApplicationService>()?.setHomeChatActive(isCurrentPage)
        TurnNotificationManager.visibleConversationId =
            if (isCurrentPage) uiState.currentConversationId else null
        onDispose {
            findService<ApplicationService>()?.setHomeChatActive(false)
            if (TurnNotificationManager.visibleConversationId == uiState.currentConversationId) {
                TurnNotificationManager.visibleConversationId = null
            }
        }
    }
    LaunchedEffect(isCurrentPage, uiState.currentConversationId, uiState.isGenerating) {
        if (isCurrentPage && !uiState.isGenerating) {
            val id = uiState.currentConversationId
            if (!id.isNullOrBlank()) {
                latestViewModel.sendIntent(HomeChatIntent.ClearUnread(id))
            }
        }
    }

    LaunchedEffect(Unit) {
        latestViewModel.sendIntent(HomeChatIntent.RefreshConfigs)
    }

    DisposableEffect(Unit) {
        onDispose {
            if (latestUiState.isConfigMenuVisible) {
                latestViewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
            }
        }
    }

    val rectSaver = remember {
        Saver<Rect?, List<Float>>(
            save = { rect -> rect?.let { listOf(it.left, it.top, it.right, it.bottom) } },
            restore = { list -> Rect(list[0], list[1], list[2], list[3]) },
        )
    }
    var titleAnchorBounds by rememberSaveable(stateSaver = rectSaver) {
        mutableStateOf<Rect?>(null)
    }

    val titleContent: @Composable () -> Unit = remember {
        {
            HomeTitleConfigChip(
                uiStateProvider = { latestUiState },
                onChipClick = {
                    latestViewModel.sendIntent(HomeChatIntent.OpenConfigMenu)
                },
                onAnchorBoundsChanged = { bounds ->
                    titleAnchorBounds = bounds
                },
            )
        }
    }

    HomeTopBarContribution(
        isConfigMenuVisible = uiState.isConfigMenuVisible,
        onDismissConfigMenu = {
            latestViewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
        },
        onOpenHistory = latestOnOpenHistory,
        onNewConversation = {
            shouldFollowBottom = true
            pendingViewportAnchor = null
            latestViewModel.sendIntent(HomeChatIntent.NewConversation)
        },
        onOpenSettings = latestOnOpenSettings,
        titleContent = titleContent,
    )

    val composerFocusRequester = remember { FocusRequester() }

    HomeConfigPopover(
        uiState = uiState,
        anchorBounds = titleAnchorBounds,
        onDismissRequest = {
            viewModel.sendIntent(HomeChatIntent.DismissConfigMenu)
        },
        onSelectConfig = { configId ->
            viewModel.sendIntent(HomeChatIntent.SelectConfig(configId))
        },
    )

    val hasActiveDialog = LocalHasActiveDialog.current
    // 冷启动键盘焦点：仅进程内首次进入 Home、无草稿输入、不在加载中且当前无弹窗时抢焦点。
    // 标志在首次 effect 执行后即置位：无论聚焦成功、attempts 耗尽还是条件不满足
    // （有草稿/正在生成），都不再重试 —— 否则回答完成时 isGenerating 翻转会重启
    // 本 effect，导致"回答完成后自动弹键盘"（冷启动时未成功聚焦过的场景）。
    if (!composerAutoFocusDone && !uiState.isLoadingConversation && !hasActiveDialog) {
        LaunchedEffect(uiState.input.isBlank(), uiState.isGenerating) {
            if (uiState.input.isBlank() && !uiState.isGenerating) {
                repeat(AUTO_FOCUS_MAX_ATTEMPTS) {
                    delay(AUTO_FOCUS_RETRY_INTERVAL_MILLIS)
                    val focused = runCatchingCancellable { composerFocusRequester.requestFocus() }.isSuccess
                    if (focused) {
                        keyboardController?.show()
                        composerAutoFocusDone = true
                        return@LaunchedEffect
                    }
                }
            }
            composerAutoFocusDone = true
        }
    }

    HomePageContentBody(
        uiState = uiState,
        actionsDisplay = actionsDisplay,
        listState = listState,
        overscrollController = overscrollController,
        composerBottomPadding = composerBottomPadding,
        composerGap = composerGap,
        composerHeight = composerHeight,
        composerFocusRequester = composerFocusRequester,
        followBottom = shouldFollowBottomState,
        isAtBottom = isAtBottom,
        onContentTap = dismissInputFocus,
        onInputChange = { value ->
            viewModel.sendIntent(HomeChatIntent.InputChanged(value))
        },
        onSendClick = {
            val query = uiState.input.trim()
            val hasAttachments =
                uiState.pendingImages.isNotEmpty() || uiState.pendingFiles.isNotEmpty()
            if (query.isNotEmpty() || hasAttachments) {
                dismissInputFocus()
                pendingViewportAnchor = ViewportAnchor(
                    index = uiState.turns.size,
                    offset = 0,
                    followOnGeneration = true,
                )
                shouldFollowBottom = true
            }
            viewModel.sendIntent(HomeChatIntent.Send)
        },
        onStopClick = {
            viewModel.sendIntent(HomeChatIntent.StopGenerating)
        },
        pendingImages = uiState.pendingImages,
        pendingFiles = uiState.pendingFiles,
        onImageAttached = { uri ->
            viewModel.sendIntent(HomeChatIntent.ImageAttached(uri))
        },
        onRemoveImage = { id ->
            viewModel.sendIntent(HomeChatIntent.ImageRemoved(id))
        },
        onCameraCaptured = { uri, path ->
            viewModel.sendIntent(HomeChatIntent.CameraCaptured(uri, path))
        },
        onFileAttached = { uri ->
            viewModel.sendIntent(HomeChatIntent.FileAttached(uri))
        },
        onRemoveFile = { id ->
            viewModel.sendIntent(HomeChatIntent.FileRemoved(id))
        },
        onComposerFocusChanged = { focused ->
            isComposerFocused = focused
        },
        onReGenerate = { id ->
            val targetIndex = uiState.turns.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                ?: listState.firstVisibleItemIndex
            pendingViewportAnchor = ViewportAnchor(targetIndex, 0, followOnGeneration = true)
            shouldFollowBottom = true
            viewModel.sendIntent(HomeChatIntent.ReGenerateAt(id))
        },
        onFork = { id ->
            val targetIndex = uiState.turns.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                ?: listState.firstVisibleItemIndex
            pendingViewportAnchor = ViewportAnchor(targetIndex, 0, followOnGeneration = false)
            shouldFollowBottom = false
            viewModel.sendIntent(HomeChatIntent.ForkAt(id))
        },
        onRewind = { id ->
            shouldFollowBottom = true
            pendingViewportAnchor = null
            lastFollowedConversationId = null
            viewModel.sendIntent(HomeChatIntent.RewindAt(id))
        },
        expandedActionTurnId = uiState.expandedActionTurnId,
        expandedActionSource = uiState.expandedActionSource,
        activeThinkingKey = uiState.activeThinkingKey,
        onToggleActionRow = { turnId, source ->
            viewModel.sendIntent(
                HomeChatIntent.ToggleActionRow(turnId, source)
            )
        },
        turnBranches = uiState.turnBranches,
        onSwitchTurnBranch = { turnId, delta ->
            val targetIndex = uiState.turns.indexOfFirst { it.id == turnId }.takeIf { it >= 0 }
                ?: listState.firstVisibleItemIndex
            pendingViewportAnchor = ViewportAnchor(targetIndex, 0, followOnGeneration = false)
            shouldFollowBottom = false
            viewModel.sendIntent(HomeChatIntent.SwitchTurnBranch(turnId, delta))
        },
        alwaysShowDetailed = alwaysShowDetailedToolOutput,
        onToggleActionKey = { key, isExpanded ->
            viewModel.sendIntent(HomeChatIntent.ToggleActionKey(key, isExpanded))
        },
    )

    val updateCheckResult by UpdateCheckHolder.result.collectAsState()
    val dismissUpdateScope = rememberCoroutineScope()
    UpdateAvailableDialog(
        update = updateCheckResult,
        onDismiss = { dismissUpdateScope.launch { UpdateCheckHolder.dismiss() } },
    )

    when (val request = uiState.pendingApproval) {
        is ApprovalRequest.ToolExecution -> {
            ToolPermissionDialog(
                request = request,
                onAllow = { viewModel.sendIntent(HomeChatIntent.ResolveApproval(ApprovalDecision.Allow)) },
                onDeny = { viewModel.sendIntent(HomeChatIntent.ResolveApproval(ApprovalDecision.Deny)) },
            )
        }

        is ApprovalRequest.ScreenControlConsent -> {
            ScreenControlConsentDialog(
                onAgree = { viewModel.sendIntent(HomeChatIntent.ResolveApproval(ApprovalDecision.Allow)) },
                onDeny = { viewModel.sendIntent(HomeChatIntent.ResolveApproval(ApprovalDecision.Deny)) },
            )
        }

        null -> {}
    }
}

private data class ViewportAnchor(
    val index: Int,
    val offset: Int,
    val followOnGeneration: Boolean = false,
)
