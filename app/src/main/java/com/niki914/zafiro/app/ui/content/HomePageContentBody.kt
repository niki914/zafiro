package com.niki914.zafiro.app.ui.content

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.logging.Logger
import com.niki914.uikit.button.ZafiroScrollToBottomButton
import com.niki914.uikit.screen.ZafiroLocalBackdropContainer
import com.niki914.uikit.screen.zafiroScreenTopPadding
import com.niki914.uikit.scroll.ZafiroOverscrollContainer
import com.niki914.uikit.scroll.ZafiroOverscrollController
import com.niki914.uikit.scroll.rememberZafiroOverscrollController
import com.niki914.zafiro.api.util.tryIgnore
import com.niki914.zafiro.app.conversation.TurnBranchState
import com.niki914.zafiro.app.ui.chat.composable.attachment.HomeChatAttachmentRow
import com.niki914.zafiro.app.ui.chat.composable.bubble.UserBubblePosition
import com.niki914.zafiro.app.ui.chat.composable.spacing.ContinuousUserBubbleSpacing
import com.niki914.zafiro.app.ui.chat.composable.spacing.TurnSeparatorSpacing
import com.niki914.zafiro.app.ui.chat.composable.turn.HomeChatTurnItem
import com.niki914.zafiro.app.ui.chat.helper.userBubblePosition
import com.niki914.zafiro.app.ui.chat.helper.userGroupTailIndex
import com.niki914.zafiro.app.ui.chat.helper.userGroupText
import com.niki914.zafiro.app.ui.content.composer.ZafiroChatComposer
import com.niki914.zafiro.app.ui.content.helper.CameraTarget
import com.niki914.zafiro.app.ui.content.helper.GrantingTakePicture
import com.niki914.zafiro.app.ui.content.helper.createCameraTarget
import com.niki914.zafiro.app.ui.content.helper.isImportableImage
import com.niki914.zafiro.app.ui.model.home.ActionSource
import com.niki914.zafiro.app.ui.model.home.HomeChatFile
import com.niki914.zafiro.app.ui.model.home.HomeChatImage
import com.niki914.zafiro.app.ui.model.home.HomeChatUiState
import com.niki914.zafiro.app.ui.model.home.MessageActionsDisplay
import kotlinx.coroutines.launch

/** 附件入口的日志 TAG（只在相机失败这类异常路径上用）。 */
private const val ATTACH_LOG_TAG = "niki914_zafiro_Attach"

/** 图片多选的张数上限。远低于系统的 getPickImagesMaxLimit()，取一个够用且不炸上下文的数。 */
private const val PHOTO_PICK_MAX_ITEMS = 10

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomePageContentBody(
    uiState: HomeChatUiState,
    actionsDisplay: MessageActionsDisplay,
    listState: LazyListState,
    overscrollController: ZafiroOverscrollController = rememberZafiroOverscrollController(),
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
    expandedActionTurnId: Long?,
    expandedActionSource: ActionSource?,
    activeThinkingKey: String? = null,
    onToggleActionRow: (Long, ActionSource) -> Unit,
    turnBranches: Map<Long, TurnBranchState> = emptyMap(),
    onSwitchTurnBranch: (Long, Int) -> Unit = { _, _ -> },
    alwaysShowDetailed: Boolean = false,
    onToggleActionKey: (key: String, isExpanded: Boolean) -> Unit = { _, _ -> },
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
            target?.file?.let { tryIgnore { it.delete() } }
        }
    }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val topPadding = zafiroScreenTopPadding(24.dp)
        val availableChatHeight = (maxHeight - topPadding - bottomClearance).coerceAtLeast(0.dp)
        val userMessageMaxHeight = if (maxHeight > 0.dp) maxHeight * 0.35f else Dp.Unspecified
        ZafiroLocalBackdropContainer(
            modifier = Modifier.fillMaxSize(),
        ) { contentBackdropModifier ->
            ZafiroOverscrollContainer(
                overscrollController = overscrollController,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(contentBackdropModifier)
                        .background(MaterialTheme.colorScheme.background)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onContentTap,
                        ),
                    contentPadding = PaddingValues(
                        start = 20.dp,
                        top = topPadding,
                        end = 20.dp,
                        bottom = bottomClearance,
                    ),
                ) {
                itemsIndexed(
                    items = uiState.turns,
                    key = { _, turn -> turn.id },
                ) { index, turn ->
                    val position = userBubblePosition(uiState.turns, index)
                    val tailIndex = userGroupTailIndex(uiState.turns, index)
                    val tailTurn = uiState.turns[tailIndex]
                    val isLastTurn = index == uiState.turns.lastIndex
                    val safeZoneMinHeight = if (isLastTurn) {
                        availableChatHeight
                    } else {
                        0.dp
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        when {
                            index == 0 -> {}
                            turn.isCompactedDivider -> {}
                            position == UserBubblePosition.GroupMid || position == UserBubblePosition.GroupLast ->
                                ContinuousUserBubbleSpacing()

                            else ->
                                TurnSeparatorSpacing()
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
                            expandedActionTurnId = expandedActionTurnId,
                            expandedActionSource = expandedActionSource,
                            activeThinkingKey = activeThinkingKey,
                            onToggleActionRow = onToggleActionRow,
                            isGenerating = uiState.isGenerating,
                            branchIndex = turnBranches[turn.id]?.current ?: 1,
                            branchCount = turnBranches[turn.id]?.total ?: 1,
                            onSwitchBranch = { delta -> onSwitchTurnBranch(turn.id, delta) },
                            alwaysShowDetailed = alwaysShowDetailed,
                            userToggledKeys = uiState.userToggledKeys,
                            onToggleAction = onToggleActionKey,
                            safeZoneMinHeight = safeZoneMinHeight,
                            userMessageMaxHeight = userMessageMaxHeight,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item(key = "bottom_anchor") {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp),
                    )
                }
            }
        }

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

        ZafiroChatComposer(
            value = uiState.input,
            onValueChange = onInputChange,
            onSendClick = onSendClick,
            onStopClick = onStopClick,
            isGenerating = uiState.isGenerating,
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
                .onSizeChanged { size ->
                    composerHeight.value = with(density) { size.height.toDp() }
                },
            contextProgress = uiState.contextProgress,
            onAttachImageClick = { attachSheetVisible = true },
        )

        val scrollToBottomScope = rememberCoroutineScope()
        ZafiroScrollToBottomButton(
            visible = !followBottom.value && !isAtBottom,
            onClick = {
                followBottom.value = true
                scrollToBottomScope.launch {
                    listState.animateScrollToItem(uiState.turns.size)
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter),
            bottomClearance = bottomClearance,
        )

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

        HomeAttachmentSheet(
            visible = attachSheetVisible,
            onDismissRequest = { attachSheetVisible = false },
            onPhotosClick = {
                photoPicker.launch(
                    PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onCameraClick = {
                val target = createCameraTarget(context)
                if (target == null) {
                    Logger.w(ATTACH_LOG_TAG, "camera: output target unavailable")
                } else {
                    pendingCapture = target
                    takePicture.launch(target.uri)
                }
            },
            onFileClick = { filePicker.launch(arrayOf("*/*")) },
            onFolderClick = { folderPicker.launch(null) },
        )
    }
}
}
