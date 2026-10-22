package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.screen.ProvideZafiroScreenContentForPreview
import com.niki914.zafiro.app.conversation.TurnBranchState
import com.niki914.zafiro.app.ui.chat.composable.section.CompactedDividerItem
import com.niki914.zafiro.app.ui.chat.composable.spacing.LocalChatSpacingDebug
import com.niki914.zafiro.app.ui.chat.composable.text.AssistantOutputText
import com.niki914.zafiro.app.ui.model.home.HomeChatBlock
import com.niki914.zafiro.app.ui.model.home.HomeChatFile
import com.niki914.zafiro.app.ui.model.home.HomeChatTurn
import com.niki914.zafiro.app.ui.model.home.HomeChatUiState
import com.niki914.zafiro.app.ui.model.home.HomeToolState
import com.niki914.zafiro.app.ui.model.home.HomeToolStatus
import com.niki914.zafiro.app.ui.model.home.MessageActionsDisplay

/**
 * 真实 UI 预览（无黑条透明间距）：
 * 验证最终在手机真实呈现时的自然视觉呼吸感与信息密度。
 */
@Preview(
    name = "Chat Screen (Production Spacing)",
    showBackground = true,
    widthDp = 420,
    heightDp = 1000,
)
@Composable
private fun ChatScreenProductionPreview() {
    BaseTheme {
        ProvideZafiroScreenContentForPreview(topPadding = 0.dp) {
            HomePageContentBody(
                actionsDisplay = MessageActionsDisplay.Always,
                composerFocusRequester = remember { FocusRequester() },
                uiState = HomeChatUiState(
                    input = "继续分析",
                    turns = listOf(
                        HomeChatTurn(
                            id = 0L,
                            userText = "帮我检查一下当前工具状态并上传配置。",
                            files = listOf(
                                HomeChatFile(
                                    id = "file-1",
                                    path = "/sdcard/config.yaml",
                                ),
                            ),
                            blocks = listOf(
                                HomeChatBlock.Thinking(
                                    id = 0,
                                    text = "Analyzing system configuration and MCP server connections...",
                                    isComplete = true,
                                ),
                                HomeChatBlock.Tool(
                                    HomeToolStatus(
                                        callId = "tool-1",
                                        name = "read_session",
                                        state = HomeToolState.Succeeded,
                                    ),
                                ),
                                HomeChatBlock.Text("I've done the check and summarized the result."),
                            ),
                        ),
                        HomeChatTurn(id = 1L, userText = "继续分析一下 MCP 的配置差异。"),
                        HomeChatTurn(id = 2L, userText = "先不用管 MCP 了，讲讲会话树。"),
                        HomeChatTurn(
                            id = 3L,
                            userText = "最后重试一次 MCP 连接并报告错误。",
                            blocks = listOf(
                                HomeChatBlock.Error("Network error: Authentication Fails, Your api key is invalid"),
                            ),
                        ),
                    ),
                ),
                turnBranches = mapOf(
                    0L to TurnBranchState(
                        current = 1,
                        total = 2,
                        variantConversationIds = listOf("conv-1", "conv-2"),
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
                expandedActionTurnId = null,
                expandedActionSource = null,
                onToggleActionRow = { _, _ -> },
            )
        }
    }
}

/**
 * 间距条块面预览（保留黑条，无白字）：
 * 用于直观审查各间距所占据的几何色块大小。
 */
@Preview(
    name = "Chat Spacing Visualizer (Black Bars)",
    showBackground = true,
    widthDp = 420,
    heightDp = 1000,
)
@Composable
private fun ChatSpacingVisualizerPreview() {
    BaseTheme {
        ProvideZafiroScreenContentForPreview(topPadding = 0.dp) {
            CompositionLocalProvider(LocalChatSpacingDebug provides true) {
                HomePageContentBody(
                    actionsDisplay = MessageActionsDisplay.Always,
                    composerFocusRequester = remember { FocusRequester() },
                    uiState = HomeChatUiState(
                        input = "继续分析",
                        turns = listOf(
                            HomeChatTurn(
                                id = 0L,
                                userText = "帮我检查一下当前工具状态并上传配置。",
                                files = listOf(
                                    HomeChatFile(
                                        id = "file-1",
                                        path = "/sdcard/config.yaml",
                                    ),
                                ),
                                blocks = listOf(
                                    HomeChatBlock.Thinking(
                                        id = 0,
                                        text = "Analyzing system configuration and MCP server connections...",
                                        isComplete = true,
                                    ),
                                    HomeChatBlock.Tool(
                                        HomeToolStatus(
                                            callId = "tool-1",
                                            name = "read_session",
                                            state = HomeToolState.Succeeded,
                                        ),
                                    ),
                                    HomeChatBlock.Text("I've done the check and summarized the result."),
                                ),
                            ),
                            HomeChatTurn(id = 1L, userText = "继续分析一下 MCP 的配置差异。"),
                            HomeChatTurn(id = 2L, userText = "先不用管 MCP 了，讲讲会话树。"),
                            HomeChatTurn(
                                id = 3L,
                                userText = "最后重试一次 MCP 连接并报告错误。",
                                blocks = listOf(
                                    HomeChatBlock.Error("Network error: Authentication Fails, Your api key is invalid"),
                                ),
                            ),
                        ),
                    ),
                    turnBranches = mapOf(
                        0L to TurnBranchState(
                            current = 1,
                            total = 2,
                            variantConversationIds = listOf("conv-1", "conv-2"),
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
                    expandedActionTurnId = null,
                    expandedActionSource = null,
                    onToggleActionRow = { _, _ -> },
                )
            }
        }
    }
}

@Preview(
    name = "Compacted Divider Alignment Preview",
    showBackground = true,
    widthDp = 400,
    heightDp = 500,
)
@Composable
private fun CompactedDividerAlignmentPreview() {
    BaseTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AssistantOutputText(
                text = "这是一段用于测试两端对齐的 Markdown 文本。请观察下方分割线两端与本段文本起止边缘是否在同一条垂线上（start-to-start, end-to-end）。",
            )

            CompactedDividerItem(isCompacting = true)

            AssistantOutputText(
                text = "这是位于两条分割线中间的普通模型回复段落。",
            )

            CompactedDividerItem(isCompacting = false)

            AssistantOutputText(
                text = "这是压缩之后的新一轮回答。上方分割线展示的是「对话已压缩」完成态。",
            )
        }
    }
}
