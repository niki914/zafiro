package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.BaseTheme
import com.niki914.uikit.infra.ProvideLiquidScreenContentForPreview
import com.niki914.zafiro.app.ui.chat.composable.section.CompactedDividerItem
import com.niki914.zafiro.app.ui.chat.composable.text.AssistantOutputText
import com.niki914.zafiro.app.ui.model.home.HomeChatBlock
import com.niki914.zafiro.app.ui.model.home.HomeChatTurn
import com.niki914.zafiro.app.ui.model.home.HomeChatUiState
import com.niki914.zafiro.app.ui.model.home.HomeToolState
import com.niki914.zafiro.app.ui.model.home.HomeToolStatus
import com.niki914.zafiro.app.ui.model.home.MessageActionsDisplay

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
                expandedActionTurnId = null,
                expandedActionSource = null,
                onToggleActionRow = { _, _ -> },
            )
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
                text = "这是一段用于测试两端对齐的 Markdown 文本。请观察下方分割线两端与本段文本起止边缘是否在同一条垂线上（start-to-start, end-to-end）。"
            )

            CompactedDividerItem(isCompacting = true)

            AssistantOutputText(
                text = "这是位于两条分割线中间的普通模型回复段落。"
            )

            CompactedDividerItem(isCompacting = false)

            AssistantOutputText(
                text = "这是压缩之后的新一轮回答。上方分割线展示的是「对话已压缩」完成态。"
            )
        }
    }
}
