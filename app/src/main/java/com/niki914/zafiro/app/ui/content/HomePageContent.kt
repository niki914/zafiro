package com.niki914.zafiro.app.ui.content

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.niki914.logging.Logger
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.ConfirmationLiquidDialog
import com.niki914.uikit.infra.LiquidDialog
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.uikit.infra.ReportTitleBarCollapsed
import com.niki914.uikit.infra.component.MaterialTintLiquidButton
import com.niki914.uikit.infra.component.OptionRow
import com.niki914.uikit.infra.component.OptionSheet
import com.niki914.uikit.infra.liquidScreenTopPadding
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.PageChromeMenuItem
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.model.home.ActionSource
import com.niki914.zafiro.app.ui.model.home.HomeChatBlock
import com.niki914.zafiro.app.ui.model.home.HomeChatEffect
import com.niki914.zafiro.app.ui.model.home.HomeChatFile
import com.niki914.zafiro.app.ui.model.home.FileAttachReason
import com.niki914.zafiro.app.ui.model.home.HomeChatImage
import com.niki914.zafiro.app.ui.model.home.HomeChatIntent
import com.niki914.zafiro.app.ui.model.home.HomeChatTurn
import com.niki914.zafiro.app.ui.model.home.HomeChatUiState
import com.niki914.zafiro.app.ui.model.home.HomeChatViewModel
import com.niki914.zafiro.app.ui.model.home.MessageActionsDisplay
import com.niki914.zafiro.app.ui.model.home.HomeToolState
import com.niki914.zafiro.app.ui.model.home.HomeToolStatus
import com.niki914.zafiro.app.ui.model.ToolPresentation
import com.niki914.zafiro.app.ui.nav.TextTitle
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.repo.UpdateCheckHolder
import com.niki914.zafiro.repo.XRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * 冷启动后仅首次进入 Home 时抢焦点弹键盘；进程内不再重复
 * （从设置页/历史页返回不打断用户）。仿 StartupPageContent.demoHasPlayed 的写法。
 */
private var composerAutoFocusDone = false
private const val AUTO_FOCUS_MAX_ATTEMPTS = 20
private const val AUTO_FOCUS_RETRY_INTERVAL_MILLIS = 150L

/** 附件入口的日志 TAG（只在相机失败这类异常路径上用）。 */
private const val ATTACH_LOG_TAG = "niki914_zafiro_Attach"

/** 图片多选的张数上限。远低于系统的 getPickImagesMaxLimit()，取一个够用且不炸上下文的数。 */
private const val PHOTO_PICK_MAX_ITEMS = 10

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

    // 附件失败提示：一次性 effect（不占状态）。
    // 三条文案按原因分（D22）：解析不出路径 / 没拿到全局文件访问权 / 读不到文件。
    val attachToastContext = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is HomeChatEffect.FileAttachFailed -> Toast.makeText(
                    attachToastContext,
                    when (effect.reason) {
                        FileAttachReason.Unresolvable -> R.string.ui_home_attach_unresolvable
                        FileAttachReason.NoPermission -> R.string.ui_home_attach_no_permission
                        FileAttachReason.Unreadable -> R.string.ui_home_attach_unreadable
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
    val alwaysShowActions by XRepo.alwaysShowMessageActionsSetting.collectAsState()
    val actionsDisplay = if (alwaysShowActions) {
        MessageActionsDisplay.Always
    } else {
        MessageActionsDisplay.OnTap
    }
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // Home 必须显式接管顶栏折叠信号：列表位置会由程序化驱动（自动贴底），且工具结果框
    // 自带滚动子树——两者都是壳层累积量看不见的位移。其余页面保持自动感知。
    ReportTitleBarCollapsed {
        listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
    }

    val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val navigationBottom = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }
    var isComposerFocused by remember { mutableStateOf(false) }
    val effectiveImeBottom = if (isComposerFocused) imeBottom else 0.dp
    // 统一视觉底间距：键盘关闭时与 composer 底距一致，不随 ime 放大——键盘打开时
    // 额外空间全部由 composerBottomPadding 提供，箭头/消息的呼吸空间保持固定
    val composerGap = navigationBottom + 20.dp
    val composerBottomPadding = (effectiveImeBottom + 12.dp).coerceAtLeast(composerGap)
    // composer 实测高度（默认 68dp = LiquidChatComposer 的 minHeight），首帧布局后回填
    val composerHeight = remember { mutableStateOf(68.dp) }
    val bottomThresholdPx = with(density) { 24.dp.roundToPx() }
    val lastTurn = uiState.turns.lastOrNull()
    // 贴底由「滚动位置 + contentPadding」共同决定：composer 几何（ime 动画、多行输入
    // 长高）变化时 padding 跟着变，也必须重新贴底，否则最后一条消息被 composer 遮住
    val bottomContentVersion = remember(
        uiState.turns.size,
        uiState.conversationVersion,
        lastTurn?.id,
        lastTurn?.blocks?.size,
        composerBottomPadding,
        composerHeight.value,
    ) {
        listOf(
            uiState.turns.size,
            uiState.conversationVersion,
            lastTurn?.id,
            lastTurn?.blocks?.size,
            composerBottomPadding,
            composerHeight.value,
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

    // 只在内容事件（bottomContentVersion）变化时跳转：恢复跟随本身不触发滚动，
    // 避免「小幅上滚停在阈值内 → 恢复跟随 → 无内容也被拉回底部」
    LaunchedEffect(bottomContentVersion) {
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
    val composerFocusRequester = remember { FocusRequester() }

    RegisterPageChrome(pageChromeContribution)

    // 冷启动键盘焦点：仅进程内首次进入 Home、无草稿输入且不在加载中时抢焦点。
    // 标志在首次 effect 执行后即置位：无论聚焦成功、attempts 耗尽还是条件不满足
    // （有草稿/正在生成），都不再重试 —— 否则回答完成时 isGenerating 翻转会重启
    // 本 effect，导致"回答完成后自动弹键盘"（冷启动时未成功聚焦过的场景）。
    if (!composerAutoFocusDone && !uiState.isLoadingConversation) {
        LaunchedEffect(uiState.input.isBlank(), uiState.isGenerating) {
            if (uiState.input.isBlank() && !uiState.isGenerating) {
                repeat(AUTO_FOCUS_MAX_ATTEMPTS) {
                    delay(AUTO_FOCUS_RETRY_INTERVAL_MILLIS)
                    val focused = runCatching { composerFocusRequester.requestFocus() }.isSuccess
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
            dismissInputFocus()
            shouldFollowBottom = true
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
            viewModel.sendIntent(HomeChatIntent.ReGenerateAt(id))
        },
        onFork = { id ->
            viewModel.sendIntent(HomeChatIntent.ForkAt(id))
        },
        onRewind = { id ->
            viewModel.sendIntent(HomeChatIntent.RewindAt(id))
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
    val uriHandler = LocalUriHandler.current
    val remoteVersion = updateCheckResult?.remoteVersion.orEmpty()
    val releaseUrl = updateCheckResult?.releaseUrl.orEmpty()
    ConfirmationLiquidDialog(
        visible = updateCheckResult?.hasUpdate == true,
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

/**
 * 屏幕控制知情同意对话框（无障碍 + 悬浮窗）。
 */
@Composable
private fun ScreenControlConsentDialog(
    onAgree: () -> Unit,
    onDeny: () -> Unit,
) {
    LiquidDialog(
        visible = true,
        onDismissRequest = onDeny,
        dismissOnBackgroundTap = false,
        title = {
            Text(
                text = stringResource(R.string.screen_control_consent_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                text = stringResource(R.string.screen_control_consent_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        actions = {
            MaterialTintLiquidButton(
                text = stringResource(R.string.screen_control_consent_deny),
                onClick = onDeny,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            MaterialTintLiquidButton(
                text = stringResource(R.string.screen_control_consent_agree),
                onClick = onAgree,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    )
}

/**
 * CONFIRM 型执行规则的用户确认对话框（永不超时，PRD §3）。
 */
@Composable
private fun ToolPermissionDialog(
    request: ApprovalRequest.ToolExecution,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    LiquidDialog(
        visible = true,
        onDismissRequest = onDeny,
        dismissOnBackgroundTap = false,
        title = {
            Text(
                text = stringResource(R.string.tool_permission_dialog_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.tool_permission_request_intro, request.toolName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = request.command,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(10.dp),
                )
                Text(
                    text = stringResource(
                        R.string.tool_permission_matched_rule,
                        request.ruleName
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        actions = {
            MaterialTintLiquidButton(
                text = stringResource(R.string.tool_permission_deny),
                onClick = onDeny,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            MaterialTintLiquidButton(
                text = stringResource(R.string.tool_permission_allow),
                onClick = onAllow,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomePageContentBody(
    uiState: HomeChatUiState,
    actionsDisplay: MessageActionsDisplay,
    listState: LazyListState,
    composerBottomPadding: Dp,
    composerGap: Dp,
    composerHeight: MutableState<Dp>,
    composerFocusRequester: FocusRequester,
    followBottom: MutableState<Boolean>,
    isAtBottom: Boolean,
    onContentTap: () -> Unit,
    onInputChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    pendingImages: List<HomeChatImage>,
    pendingFiles: List<HomeChatFile>,
    onImageAttached: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onCameraCaptured: (String, String) -> Unit,
    onFileAttached: (String) -> Unit,
    onRemoveFile: (String) -> Unit,
    onComposerFocusChanged: (Boolean) -> Unit,
    onReGenerate: (Long) -> Unit,
    onFork: (Long) -> Unit,
    onRewind: (Long) -> Unit,
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

    // 底部避让总高：composer 底距 + 实测高度 + 统一视觉间距。列表贴底留白与箭头
    // 位置同源；键盘关闭时（composerBottomPadding == composerGap）即为
    // composerBottomPadding*2 + composerHeight，composer 顶上方留一个视觉间距
    val bottomClearance = composerBottomPadding + composerHeight.value + composerGap
    val density = LocalDensity.current

    // 附件入口：加号 → 选项单（Photos / Camera / File / Folder）。
    val context = LocalContext.current
    var attachSheetVisible by remember { mutableStateOf(false) }
    var pendingCapture by remember { mutableStateOf<CameraTarget?>(null) }

    // 系统图片选择器（photo picker，无权限）：可多选，选完逐张 ingest 落盘 → pendingImages
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(PHOTO_PICK_MAX_ITEMS),
    ) { uris ->
        uris.forEach { onImageAttached(it.toString()) }
    }

    // SAF 文档 / 目录选择器：可多选；把 content uri 原样交给 VM，
    // 由 business:files 逐个解析路径 + 要全局文件访问权，这里不做判断
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        uris.forEach { uri ->
            // 图片走图片管线（不需要路径、不需要存储权限），其余仍走文件引用
            val type = context.contentResolver.getType(uri)
            val asImage = isImportableImage(context, uri, type)
            Logger.i(ATTACH_LOG_TAG, "file picked uri=$uri type=$type asImage=$asImage")
            if (asImage) onImageAttached(uri.toString()) else onFileAttached(uri.toString())
        }
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { onFileAttached(it.toString()) }
    }

    // 系统相机：输出目标是我们自己 cache 里的临时文件（FileProvider uri）。
    // 删那个临时文件由 VM 在 ingest 完成之后做，这里不能当场删（原文件还没被拷走）。
    //
    // 为什么不用 MediaStore：官方 TakePicture contract 只塞 EXTRA_OUTPUT，本就不带
    // URI 授权；补上 clipData + grant 之后依然不行——我们刚 insert 的记录是
    // IS_PENDING=1，pending 项对**非所有者**是 owner-only（MediaProvider 自己拦，
    // URI 授权也压不过），相机是另一个 uid，写不进去。真机实测：相机直接回 NOT_OK。
    // FileProvider 的 uri 落在我们自己的目录上，授权就是唯一的门槛，也是官方文档的配方。
    val takePicture = rememberLauncherForActivityResult(
        contract = GrantingTakePicture(),
    ) { result ->
        val target = pendingCapture
        pendingCapture = null
        if (result.resultCode == Activity.RESULT_OK && target != null) {
            onCameraCaptured(target.uri.toString(), target.file.path)
        } else {
            // 相机没写成（用户取消 / 相机拒绝）：清掉临时文件
            Logger.w(ATTACH_LOG_TAG, "camera result not ok resultCode=${result.resultCode}")
            target?.file?.let { runCatching { it.delete() } }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
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
                bottom = bottomClearance,
            ),
        ) {
            itemsIndexed(
                items = uiState.turns,
                key = { _, turn -> turn.id },
            ) { index, turn ->
                // User 气泡组内位置（渲染层：两个相邻 UserBubble 之间无任何内容即同组，见 userBubblePosition）
                val position = userBubblePosition(uiState.turns, index)
                // 组归一化：点组内任意一条都弹/聚合到组尾（操作行在组尾 turn 下渲染）
                val tailIndex = userGroupTailIndex(uiState.turns, index)
                val tailTurn = uiState.turns[tailIndex]
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
                    isLastTurn = index == uiState.turns.lastIndex,
                    actionsDisplay = actionsDisplay,
                    userGroupTailTurnId = tailTurn.id,
                    userGroupTappable = tailTurn.blocks.isNotEmpty() || tailIndex == uiState.turns.lastIndex,
                    userGroupText = userGroupText(uiState.turns, index),
                    onContentTap = onContentTap,
                    onReGenerate = onReGenerate,
                    onFork = onFork,
                    onRewind = onRewind,
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

        // 待发附件条：图片与文件**同一行**（两种卡同尺寸同形状），宽 = composer 本体，
        // 位于 composer 上方 8dp；声明在 composer 之前，万一重合 composer 层级更高盖住它们。
        // composer 拉长/被 IME 顶起时随 composerHeight/composerBottomPadding 精确跟随。
        // 不可点击（TODO: 后续接入点开大图 / 打开文件）
        if (pendingImages.isNotEmpty() || pendingFiles.isNotEmpty()) {
            HomeChatAttachmentRow(
                images = pendingImages,
                files = pendingFiles,
                cardSize = 60.dp,
                cornerRadius = 18.dp,
                onRemoveImage = onRemoveImage,
                onRemoveFile = onRemoveFile,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp)
                    .fillMaxWidth()
                    .padding(bottom = composerBottomPadding + composerHeight.value + 8.dp),
            )
        }

        LiquidChatComposer(
            value = uiState.input,
            onValueChange = onInputChange,
            onSendClick = onSendClick,
            onStopClick = onStopClick,
            isGenerating = uiState.isGenerating,
            pendingImages = pendingImages,
            pendingFiles = pendingFiles,
            onAttachImageClick = { attachSheetVisible = true },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onFocusChanged { focusState ->
                    onComposerFocusChanged(focusState.hasFocus)
                }
                .focusRequester(composerFocusRequester)
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = composerBottomPadding,
                )
                // 放在 padding 之后：只测 composer 本体高度，不含底边距
                .onSizeChanged { size ->
                    composerHeight.value = with(density) { size.height.toDp() }
                },
        )

        // 解除贴底锚定且不在底部时出现：点击恢复跟随并平滑滚回底部
        val scrollToBottomScope = rememberCoroutineScope()
        AnimatedVisibility(
            visible = !followBottom.value && !isAtBottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomClearance),
            enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.82f),
            exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.86f),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable {
                        followBottom.value = true
                        scrollToBottomScope.launch {
                            listState.animateScrollToItem(uiState.turns.size)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_down),
                    contentDescription = stringResource(
                        R.string.ui_home_scroll_to_bottom_content_description,
                    ),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
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

        OptionSheet(
            visible = attachSheetVisible,
            onDismissRequest = { attachSheetVisible = false },
            title = stringResource(R.string.ui_home_attach_sheet_title),
        ) { dismissThen ->
            OptionRow(
                title = stringResource(R.string.ui_home_attach_photos),
                leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
                onClick = {
                    dismissThen {
                        photoPicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    }
                },
            )
            // 相机项：不再有版本门。旧门存在的理由是「MediaStore 的 RELATIVE_PATH/IS_PENDING
            // 要 29+」，改用 FileProvider 之后这个前提没了，26+ 都能跑。
            OptionRow(
                title = stringResource(R.string.ui_home_attach_camera),
                leadingContent = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                onClick = {
                    dismissThen {
                        val target = createCameraTarget(context)
                        if (target == null) {
                            Logger.w(ATTACH_LOG_TAG, "camera: output target unavailable")
                        } else {
                            pendingCapture = target
                            takePicture.launch(target.uri)
                        }
                    }
                },
            )
            // File / Folder 全版本放出：30+ 走 all-files，<30 走运行时权限（机制与版本分叉见 PermissionSpec）
            OptionRow(
                title = stringResource(R.string.ui_home_attach_file),
                leadingContent = { Icon(Icons.Default.Description, contentDescription = null) },
                onClick = { dismissThen { filePicker.launch(arrayOf("*/*")) } },
            )
            OptionRow(
                title = stringResource(R.string.ui_home_attach_folder),
                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                onClick = { dismissThen { folderPicker.launch(null) } },
            )
        }
    }
}

/**
 * 相机输出目标：FileProvider 的 uri（给相机写）+ 它背后的临时文件（ingest 完成后删）。
 * 文件落在 `cacheDir/capture/`（见 `res/xml/file_paths.xml`）。
 */
private data class CameraTarget(val uri: Uri, val file: File)

/** cache 下専给相机输出的子目录（与 `file_paths.xml` 的 `path` 一致）。 */
private const val CAMERA_CAPTURE_DIR = "capture"

/**
 * 文件入口分流：这个 uri 能不能走图片管线。
 *
 * 判据优先看后缀（docId 通常就带着文件名），没有后缀才回落到 provider 报的 MIME——
 * 「下载」抽屉里在 DownloadManager 数据库中的文件就是裸数字 docId，只能靠 MIME。
 */
private fun isImportableImage(context: Context, uri: Uri, declaredType: String?): Boolean {
    val extension = uri.lastPathSegment.orEmpty()
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    val isImage = if (extension.isNotEmpty()) {
        extension in IMPORTABLE_IMAGE_EXTENSIONS
    } else {
        declaredType?.startsWith("image/") == true
    }
    if (!isImage) return false
    // 超过图片管线上限的图会被 ingest 拒，而拒了之后草稿项是**静默消失**的，
    // 比「当一个文件附件」更糟。体积未知（-1）时放行，交给一致的上限去判。
    val size = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
    }.getOrNull()
    return size == null || size <= IMAGE_MAX_BYTES
}

/**
 * 分流时认作图片的后缀。
 *
 * 不含 HEIC / HEIF：26/27 的 `BitmapFactory` 解不了，进来会在图片管线里静默失败，
 * 所以让它们继续走文件路径（= 今天的形态）。
 */
private val IMPORTABLE_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

/** 与图片管线的 `ImageFormat.MAX_IMAGE_BYTES` 同口径（那个常数在 agent-runtime 内部）。 */
private const val IMAGE_MAX_BYTES = 12 * 1024 * 1024L

/**
 * 造一个相机可以写的输出目标。路径与 provider 都对不上时返回 null（理论上不会）。
 */
private fun createCameraTarget(context: Context): CameraTarget? = runCatching {
    val dir = File(context.cacheDir, CAMERA_CAPTURE_DIR).apply { mkdirs() }
    val file = File(dir, "zafiro_capture_${System.currentTimeMillis()}.jpg")
    CameraTarget(
        uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
        file = file,
    )
}.onFailure {
    Logger.w(ATTACH_LOG_TAG, "camera target create failed error=${it.message}")
}.getOrNull()

/**
 * 带写授权的 [ActivityResultContract]。
 *
 * 官方 `TakePicture` 只塞 `EXTRA_OUTPUT`，不带 clipData、不带 grant 标志；FileProvider 的
 * uri 必须显式授权给相机（另一个 uid），否则相机打开它会被拒。所以这里照官方「Take photos」
 * 文档补上 clipData + `FLAG_GRANT_READ/WRITE_URI_PERMISSION`。
 *
 * 回传原始 [resultCode] 而不是 Boolean：排障时必须区分 CANCELED 与其它码，
 * 官方 contract 把它丢了。
 */
private class GrantingTakePicture : ActivityResultContract<Uri, ActivityResult>() {

    override fun createIntent(context: Context, input: Uri): Intent {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, input)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // setClipData 返回 void，不能链式；grant 标志要配 clipData 才会落到被授的 uri 上
        intent.clipData = ClipData.newUri(context.contentResolver, "camera_output", input)
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): ActivityResult =
        ActivityResult(resultCode, intent)
}

/**
 * 渲染层连续 User 气泡分组：两个相邻 UserBubble 之间有无内容，取决于上一 turn 的 blocks
 * 是否为空（内容夹在上一气泡与下一气泡之间）。规则：
 * - 对下一条黏（自身成为组首/组中）：自身 blocks 为空——自身内容会显示在自身气泡与下一条之间；
 * - 对上一条黏（自身成为组末/组中）：上一 turn blocks 为空。
 * 因此流式内容到达只会改变"自身→下一条"一侧，组首一侧的黏性由上一 turn 决定，组不随内容跳变。
 */
private fun userBubblePosition(turns: List<HomeChatTurn>, index: Int): UserBubblePosition {
    val stickyUp = index > 0 && turns[index - 1].blocks.isEmpty()
    val stickyDown = index < turns.lastIndex && turns[index].blocks.isEmpty()
    return when {
        stickyUp && stickyDown -> UserBubblePosition.GroupMid
        stickyUp -> UserBubblePosition.GroupLast
        stickyDown -> UserBubblePosition.GroupFirst
        else -> UserBubblePosition.Single
    }
}

/**
 * 渲染层连续 User 气泡组（见 [userBubblePosition]）：index 所在组的组尾下标。
 * 组向下延伸的判定与 userBubblePosition 一致：自身 blocks 为空则下一气泡与本气泡相邻。
 */
internal fun userGroupTailIndex(turns: List<HomeChatTurn>, index: Int): Int {
    var tail = index
    while (tail < turns.lastIndex && turns[tail].blocks.isEmpty()) tail++
    return tail
}

/** index 所在 User 组的组内全部用户文本：按序、双换行分隔、跳过空白（纯图片 turn）。 */
internal fun userGroupText(turns: List<HomeChatTurn>, index: Int): String {
    var head = index
    while (head > 0 && turns[head - 1].blocks.isEmpty()) head--
    val tail = userGroupTailIndex(turns, index)
    return turns.subList(head, tail + 1)
        .map { it.userText }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}

@Composable
private fun HomeChatTurnItem(
    turn: HomeChatTurn,
    userBubblePosition: UserBubblePosition,
    isLastTurn: Boolean,
    actionsDisplay: MessageActionsDisplay,
    /** 点组内任意一条时操作行归一到组尾 turn；非组尾成员仅作点击归一目标。 */
    userGroupTailTurnId: Long,
    /** 组尾 turn 的操作行资格（blocks 非空或为最后一条）。 */
    userGroupTappable: Boolean,
    /** 组内全部用户文本（双换行分隔），供组尾操作行复制。 */
    userGroupText: String,
    onContentTap: () -> Unit,
    onReGenerate: (Long) -> Unit,
    onFork: (Long) -> Unit,
    onRewind: (Long) -> Unit,
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
    val alwaysVisible = actionsDisplay == MessageActionsDisplay.Always
    // Agent 操作行：仅正生成中的末轮隐藏；已结束的旧 turn 常显（复制无害，
    // 重新生成/分叉/回退在生成中点按弹 toast，见 guarded 回调）
    val canToggleAction = turn.blocks.isNotEmpty() && (!isGenerating || !isLastTurn)
    // User 操作行资格：生成中同样放开（历史遗留修复：复制无害，重新生成/回退由 Controller 护栏拦截）；
    // 最后一条 turn 即使无内容也放开，使本条 query 仍可复制
    val userRowEligible = turn.blocks.isNotEmpty() || isLastTurn
    val isUserGroupTail = turn.id == userGroupTailTurnId

    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun copyText(text: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
        }
        Toast.makeText(context, R.string.ui_toast_copied, Toast.LENGTH_SHORT).show()
    }

    fun toastGenerating() {
        Toast.makeText(context, R.string.ui_toast_generating, Toast.LENGTH_SHORT).show()
    }
    // 生成中：危险动作（重新生成/分叉/回退）弹 toast 提示，不透传给 Controller（其护栏会静默吞掉）
    val guardedReGenerate: (Long) -> Unit = { id ->
        if (isGenerating) toastGenerating() else onReGenerate(id)
    }
    val guardedFork: (Long) -> Unit = { id ->
        if (isGenerating) toastGenerating() else onFork(id)
    }
    val guardedRewind: (Long) -> Unit = { id ->
        if (isGenerating) toastGenerating() else onRewind(id)
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
        // 附件行：图片与文件同一行（同尺寸同形状），镜像 UserMessageBubble 的
        // 对齐方式——BoxWithConstraints 右对齐，Row 贴内容宽、max 同 bubble（0.82f）；
        // 外层与卡片同圆角 clip——边缘卡被裁时仍呈圆角。多附件时初始 scroll=0 优先展示左边的，整行靠右。
        // 不可点击（TODO: 点开大图 / 打开文件）
        if (turn.images.isNotEmpty() || turn.files.isNotEmpty()) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                HomeChatAttachmentRow(
                    images = turn.images,
                    files = turn.files,
                    cardSize = 120.dp,
                    cornerRadius = UserBubbleCornerRadius,
                    modifier = Modifier.widthIn(max = maxWidth * 0.82f),
                )
            }
        }

        // 纯图片 turn：文本为空（isEmpty 而非 isBlank——历史里用户发过的空白文本
        // turn 仍显示气泡）；连 clickable 一起隐藏，空 bubble 点击弹操作行无意义
        if (turn.userText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            onContentTap()
                            // Always 模式永不收回；OnTap 模式点组内任意一条都归一到组尾
                            if (!alwaysVisible && userGroupTappable) {
                                onToggleActionRow(userGroupTailTurnId, ActionSource.User)
                            }
                        },
                    ),
                contentAlignment = Alignment.CenterEnd,
            ) {
                UserMessageBubble(text = turn.userText, position = userBubblePosition)
            }
        }

        AnimatedVisibility(
            visible = isUserGroupTail && userRowEligible &&
                    (alwaysVisible || (showActionRow && actionSource == ActionSource.User)),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            TurnActionRow(
                source = ActionSource.User,
                display = actionsDisplay,
                onCopy = {
                    copyText(userGroupText)
                },
                onReGenerate = { guardedReGenerate(turn.id) },
                onFork = { guardedFork(turn.id) },
                onRewind = { guardedRewind(turn.id) },
            )
        }

        // 内容区：turn 分隔补差（TurnSeparator - BlockSpacing）放在内容区顶部而非气泡底部，
        // 使气泡→用户操作行（复制按钮）的间距仅剩 BlockSpacing 12dp，与 markdown→操作行一致；
        // 组内成员（非 Single）不设补差，与下一气泡保持组内紧凑
        if (turn.blocks.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(
                    top = if (userBubblePosition == UserBubblePosition.Single) {
                        TurnSeparator - BlockSpacing
                    } else {
                        0.dp
                    }
                ),
                verticalArrangement = Arrangement.spacedBy(BlockSpacing),
            ) {
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
                                if (!alwaysVisible && canToggleAction) {
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
                                                    if (!alwaysVisible && canToggleAction) {
                                                        onToggleActionRow(
                                                            turn.id,
                                                            ActionSource.Agent
                                                        )
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
                                val errorUi = toAssistantErrorUi(
                                    message = block.message,
                                    code = block.code,
                                    attempts = block.attempts,
                                )
                                CollapsibleBlock(
                                    icon = Icons.Filled.ErrorOutline,
                                    title = stringResource(errorUi.titleRes),
                                    isExpanded = true,
                                    onToggle = {},
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    nonCollapsible = true,
                                ) {
                                    val body = errorUi.bodyRes?.let { stringResource(it) }
                                        ?: errorUi.body.orEmpty()
                                    if (body.isNotEmpty()) {
                                        ToolResultText(
                                            text = body,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            is HomeChatBlock.Retrying -> {
                                // 常展开、不可折叠（与 Error 块不同：retry 是进行中的转态，
                                // 收起无意义）；重试成功即整个块消失
                                CollapsibleBlock(
                                    icon = Icons.Filled.Refresh,
                                    title = stringResource(R.string.ui_home_retrying_title),
                                    isExpanded = true,
                                    onToggle = {},
                                    isRunning = true,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    nonCollapsible = true,
                                ) {
                                    val count = stringResource(
                                        R.string.ui_home_retrying_attempt,
                                        block.attempt,
                                        block.maxAttempts,
                                    )
                                    ToolResultText(
                                        text = if (block.reason.isBlank()) count
                                        else "$count: ${block.reason.trim()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
                                                    if (!alwaysVisible && canToggleAction) {
                                                        onToggleActionRow(
                                                            turn.id,
                                                            ActionSource.Agent
                                                        )
                                                    }
                                                },
                                            ),
                                    ) {
                                        ToolResultText(
                                            text = block.text,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            }
        }

        AnimatedVisibility(
            visible = (alwaysVisible && canToggleAction) ||
                    (showActionRow && actionSource == ActionSource.Agent),
            enter = expandVertically() + fadeIn(),
        ) {
            TurnActionRow(
                source = ActionSource.Agent,
                display = actionsDisplay,
                onCopy = {
                    val text = turn.blocks
                        .filterIsInstance<HomeChatBlock.Text>()
                        .joinToString("\n\n") { it.text }
                    copyText(text)
                },
                onReGenerate = { guardedReGenerate(turn.id) },
                onFork = { guardedFork(turn.id) },
            )
        }
    }
}

/**
 * 手势信任的贴底跟随状态机（外层聊天列表与内层 thinking/工具结果文本共用）。
 * 用户开始拖拽立即暂停跟随——流式新内容不再抢占滚动；
 * 滚动完全停止后按 [isAtEnd] 判定是否恢复跟随。
 * 返回 MutableState：发送消息等场景可主动置回 true 恢复跟随。
 */
@Composable
internal fun rememberScrollFollowState(
    interactionSource: InteractionSource,
    isScrollInProgress: () -> Boolean,
    isAtEnd: () -> Boolean,
): MutableState<Boolean> {
    val follow = remember { mutableStateOf(true) }
    var hasPendingUserScrollDecision by remember { mutableStateOf(false) }
    val isUserDragging by interactionSource.collectIsDraggedAsState()

    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            follow.value = false
            hasPendingUserScrollDecision = true
        }
    }
    LaunchedEffect(interactionSource) {
        snapshotFlow { isScrollInProgress() }
            .collectLatest { isScrollInProgress ->
                if (!isScrollInProgress && hasPendingUserScrollDecision) {
                    follow.value = isAtEnd()
                    hasPendingUserScrollDecision = false
                }
            }
    }
    return follow
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
                actionsDisplay = MessageActionsDisplay.OnTap,
                composerFocusRequester = remember { FocusRequester() },
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
                composerGap = 20.dp,
                composerHeight = remember { mutableStateOf(68.dp) },
                followBottom = remember { mutableStateOf(true) },
                isAtBottom = true,
                onContentTap = {},
                onInputChange = {},
                onSendClick = {},
                onStopClick = {},
                pendingImages = emptyList(),
                pendingFiles = emptyList(),
                onImageAttached = {},
                onRemoveImage = {},
                onCameraCaptured = { _, _ -> },
                onFileAttached = {},
                onRemoveFile = {},
                onComposerFocusChanged = {},
                onReGenerate = { },
                onFork = { },
                onRewind = { },
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
